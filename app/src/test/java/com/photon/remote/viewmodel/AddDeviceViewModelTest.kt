package com.photon.remote.viewmodel

import com.photon.remote.codebase.CodeResolver
import com.photon.remote.codebase.IrdbCsvParser
import com.photon.remote.codebase.IrextArea
import com.photon.remote.codebase.IrextBrand
import com.photon.remote.codebase.IrextCity
import com.photon.remote.codebase.IrextIndexLoader
import com.photon.remote.codebase.IrextOperator
import com.photon.remote.codebase.IrextRemote
import com.photon.remote.codebase.location.LocationResolver
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.repository.DeviceRepository
import com.photon.remote.ir.transmitter.IrDispatcher
import com.photon.remote.ir.transmitter.TransmitterManager
import kotlinx.coroutines.runBlocking
import net.irext.decode.sdk.utils.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicReference

/**
 * AddDeviceViewModel 单元测试（FIX-1 ~ FIX-5）。
 *
 * FIX-1：验证 nextEnabled（combine 推导的实时 StateFlow）随选择变化自动更新——
 * 选类型→选品牌→选型号/运营商 后按钮使能从 false 变 true（修复选品牌后按钮
 * 不刷新的 Compose 观察缺陷）。
 * FIX-2：验证 STB 无地区品牌时 brandHasAreas=false（驱动 UI 仅自动跳过一次地区页，
 * 用户"上一步"可返回品牌页的状态逻辑）。
 * FIX-3：验证 loadBrands 过滤无码组品牌（FAN 仅留有 remotes 的品牌）。
 * FIX-4：验证跨源按英文名归一（IREXT"先锋"+irdb"Pioneer"合并为 displayName
 * "先锋 Pioneer"，无重复条目；未匹配 irdb 品牌单独保留）。
 * FIX-5：验证 saveDevice 设备名留空时默认用品牌中文名。
 */
@RunWith(RobolectricTestRunner::class)
class AddDeviceViewModelTest {

    private lateinit var repository: DeviceRepository
    private lateinit var indexLoader: IrextIndexLoader
    private lateinit var irdbParser: IrdbCsvParser
    private lateinit var codeResolver: CodeResolver
    private lateinit var dispatcher: IrDispatcher
    private lateinit var transmitter: TransmitterManager
    private lateinit var locationResolver: LocationResolver
    private lateinit var vm: AddDeviceViewModel

    @Before
    fun setUp() {
        repository = mock(DeviceRepository::class.java)
        indexLoader = mock(IrextIndexLoader::class.java)
        irdbParser = mock(IrdbCsvParser::class.java)
        codeResolver = mock(CodeResolver::class.java)
        dispatcher = mock(IrDispatcher::class.java)
        transmitter = mock(TransmitterManager::class.java)
        locationResolver = mock(LocationResolver::class.java)
        vm = AddDeviceViewModel(
            repository = repository,
            indexLoader = indexLoader,
            irdbParser = irdbParser,
            codeResolver = codeResolver,
            dispatcher = dispatcher,
            transmitter = transmitter,
            locationResolver = locationResolver,
        )
    }

    // ---------- FIX-1：nextEnabled 实时推导 ----------

    /**
     * nextEnabled 由 stateIn(viewModelScope, SharingStarted.Eagerly) 在 Main 调度器
     * 上收集 combine；Robolectric 下必须 idle 主线程 Looper 让 StateFlow 新值传播到
     * nextEnabled.value。
     */
    private fun awaitNextEnabled() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /** 初始（第 0 页未选类型）→ nextEnabled=false；选类型→true；翻到品牌页未选品牌→false */
    @Test
    fun fix1_未选类型_按钮禁用_选类型后启用() {
        assertFalse("初始未选类型应禁用", vm.nextEnabled.value)

        vm.selectType(DeviceType.TV)
        awaitNextEnabled()
        assertTrue("选中类型后（第 0 页）应启用", vm.nextEnabled.value)

        vm.nextPage()
        awaitNextEnabled()
        assertFalse("品牌页未选品牌应禁用", vm.nextEnabled.value)
    }

    /** FIX-1 主验收：选类型→翻品牌页→选品牌→nextEnabled 从 false 变 true */
    @Test
    fun fix1_选品牌后_nextEnabled_从false变true() {
        vm.selectType(DeviceType.TV)
        vm.nextPage()
        awaitNextEnabled()

        assertFalse("品牌页未选品牌应禁用", vm.nextEnabled.value)

        vm.selectBrand(BrandOption("小米", CodeSource.IREXT, 1))
        awaitNextEnabled()
        assertTrue("选品牌后应启用（FIX-1 主 Bug）", vm.nextEnabled.value)
    }

    /** 非 STB：型号页选码组后启用 */
    @Test
    fun fix1_选型号后_按钮启用() {
        vm.selectType(DeviceType.TV)
        vm.nextPage()
        vm.selectBrand(BrandOption("小米", CodeSource.IREXT, 1))
        vm.nextPage()
        awaitNextEnabled()

        assertFalse("型号页未选码组应禁用", vm.nextEnabled.value)

        vm.selectCode(CodeOption("小米电视", CodeSource.IREXT, "xiaomi.bin", "小米电视"))
        awaitNextEnabled()
        assertTrue("选码组后应启用", vm.nextEnabled.value)
    }

    /** STB：地区页选运营商后启用 */
    @Test
    fun fix1_stb选运营商后_按钮启用() {
        // STB 品牌带地区数据：getAreas 返回非空
        val brandId = 1
        `when`(indexLoader.getAreas(brandId))
            .thenReturn(listOf(IrextArea("广东省", emptyList())))

        vm.selectType(DeviceType.STB)
        vm.nextPage()
        vm.selectBrand(BrandOption("广电盒子", CodeSource.IREXT, brandId))
        assertTrue("带地区品牌 brandHasAreas 应为 true", vm.brandHasAreas.value)
        vm.nextPage()
        awaitNextEnabled()

        assertFalse("地区页未选运营商应禁用", vm.nextEnabled.value)

        vm.selectOperator(IrextOperator("中国移动", emptyList()))
        awaitNextEnabled()
        assertTrue("STB 选运营商后应启用", vm.nextEnabled.value)
    }

    // ---------- FIX-2：STB 无地区品牌的状态逻辑 ----------

    /** STB 无地区品牌：brandHasAreas=false（驱动 UI 仅自动跳过地区页一次） */
    @Test
    fun fix2_stb无地区品牌_brandHasAreas为false() {
        val brandId = 2
        `when`(indexLoader.getAreas(brandId)).thenReturn(emptyList())

        vm.selectType(DeviceType.STB)
        vm.selectBrand(BrandOption("无地区盒子", CodeSource.IREXT, brandId))

        assertFalse("无地区品牌 brandHasAreas 应为 false", vm.brandHasAreas.value)
        // 无地区品牌型号直接挂在品牌下（不依赖运营商），地区页不应成为必经步骤
        assertFalse("无地区品牌不应有省份候选", vm.provinces.value.isNotEmpty())
    }

    /** 有地区 STB 品牌与无地区 STB 品牌互不影响（选择状态隔离） */
    @Test
    fun fix2_有地区与无地区品牌_状态隔离() {
        val withAreaId = 1
        val noAreaId = 2
        `when`(indexLoader.getAreas(withAreaId))
            .thenReturn(listOf(IrextArea("浙江省", emptyList())))
        `when`(indexLoader.getAreas(noAreaId)).thenReturn(emptyList())

        vm.selectType(DeviceType.STB)
        vm.selectBrand(BrandOption("有地区", CodeSource.IREXT, withAreaId))
        assertTrue(vm.brandHasAreas.value)

        vm.selectBrand(BrandOption("无地区", CodeSource.IREXT, noAreaId))
        assertFalse(vm.brandHasAreas.value)
        assertEquals("换品牌应重置已选运营商", null, vm.selectedOperator.value)
    }

    // ---------- FIX-3：无码组品牌过滤 ----------

    /** FAN 品牌中无 remotes 的品牌被过滤，只留有码组品牌（修复选中后型号列表空的死路） */
    @Test
    fun fix3_无码组品牌被过滤_只留有码组品牌() {
        val brands = listOf(
            IrextBrand(id = 1, name = "艾美特", nameEn = "Airmate", remotes = listOf(IrextRemote(1, "r1", "a.bin"))),
            IrextBrand(id = 2, name = "美的", nameEn = "Midea", remotes = emptyList()),
            IrextBrand(id = 3, name = "先锋", nameEn = "Pioneer", remotes = listOf(IrextRemote(2, "r2", "b.bin"))),
        )
        `when`(indexLoader.getBrands(Constants.CategoryID.FAN.getValue())).thenReturn(brands)

        vm.selectType(DeviceType.FAN)
        awaitNextEnabled()

        val names = vm.brands.value.map { it.name }.toSet()
        assertEquals("无码组品牌（美的）应被过滤", setOf("艾美特", "先锋"), names)
    }

    /** STB 品牌直挂 remotes 为空但地区链路有运营商 remotes → 不应被过滤（保留 STB 可用性） */
    @Test
    fun fix3_stb品牌走地区链路_不应被过滤() {
        val stbBrand = IrextBrand(
            id = 1,
            name = "运营商机顶盒",
            nameEn = "STB",
            remotes = emptyList(),
            areas = listOf(
                IrextArea(
                    "广东省",
                    cities = listOf(
                        IrextCity(
                            "广州市",
                            operators = listOf(
                                IrextOperator("中国移动", listOf(IrextRemote(1, "r1", "a.bin"))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        `when`(indexLoader.getBrands(Constants.CategoryID.STB.getValue())).thenReturn(listOf(stbBrand))
        `when`(indexLoader.getAreas(1)).thenReturn(stbBrand.areas)

        vm.selectType(DeviceType.STB)
        awaitNextEnabled()

        assertEquals("地区链路有码组的 STB 品牌应保留", 1, vm.brands.value.size)
        assertEquals("运营商机顶盒", vm.brands.value.single().name)
    }

    // ---------- FIX-4：跨源英文名归一 + 中英并显 ----------

    /** IREXT"先锋/Pioneer" + irdb"Pioneer" → 合并为一条 displayName="先锋 Pioneer"，irdb 未匹配品牌单独保留 */
    @Test
    fun fix4_跨源英文名归一并中英并显() {
        `when`(indexLoader.getBrands(Constants.CategoryID.TV.getValue())).thenReturn(
            listOf(
                IrextBrand(id = 1, name = "先锋", nameEn = "Pioneer", remotes = listOf(IrextRemote(1, "r1", "a.bin"))),
            ),
        )
        `when`(irdbParser.listBrands()).thenReturn(listOf("Pioneer", "Onkyo"))
        `when`(irdbParser.listTypes("Pioneer")).thenReturn(listOf("tv"))
        `when`(irdbParser.listTypes("Onkyo")).thenReturn(listOf("tv"))

        vm.selectType(DeviceType.TV)
        awaitNextEnabled()

        val brands = vm.brands.value
        assertEquals("IREXT 合并条目 + 未匹配 irdb 品牌 = 2 条", 2, brands.size)
        val pioneer = brands.first { it.source == CodeSource.IREXT }
        assertEquals("先锋", pioneer.name)
        assertEquals("先锋 Pioneer", pioneer.displayName)
        assertEquals("Pioneer", pioneer.enName)
        // 不应存在重复的 irdb "Pioneer" 条目
        assertFalse("irdb 匹配品牌不应重复显示", brands.any { it.name == "Pioneer" && it.source == CodeSource.IRDB })
        val onkyo = brands.first { it.name == "Onkyo" }
        assertEquals("未匹配 irdb 品牌单独保留", CodeSource.IRDB, onkyo.source)
        assertEquals("Onkyo", onkyo.displayName)
    }

    /** 纯英文 IREXT 品牌（TCL）displayName 不重复拼接 */
    @Test
    fun fix4_纯英文品牌_displayName不重复() {
        `when`(indexLoader.getBrands(Constants.CategoryID.TV.getValue())).thenReturn(
            listOf(
                IrextBrand(id = 1, name = "TCL", nameEn = "TCL", remotes = listOf(IrextRemote(1, "r1", "a.bin"))),
            ),
        )
        `when`(irdbParser.listBrands()).thenReturn(emptyList())

        vm.selectType(DeviceType.TV)
        awaitNextEnabled()

        assertEquals("TCL", vm.brands.value.single().displayName)
    }

    // ---------- FIX-5：设备默认名用品牌中文名 ----------

    /** saveDevice 设备名留空 → name=品牌中文名（"先锋"） */
    @Test
    fun fix5_设备名留空_默认用品牌中文名() {
        // Mockito 的 any() 对 suspend 函数返回 null，Kotlin 会对非空参数插空值检查抛
        // "any(...) must not be null"；用 `any(Class) ?: 占位实例` 提供非空值，匹配器
        // 在调用 any() 时已注册、实际值被忽略——等价 mockito-kotlin 的 any() 行为。
        val captured = AtomicReference<Device?>()
        val dummy = Device(name = "", type = DeviceType.TV, brand = "", codeSource = CodeSource.IREXT, codeRef = "")
        runBlocking {
            doAnswer { invocation ->
                captured.set(invocation.getArgument(0))
                1L
            }.`when`(repository).addDevice(any(Device::class.java) ?: dummy)
        }
        vm.selectType(DeviceType.TV)
        vm.selectBrand(BrandOption("先锋", CodeSource.IREXT, 1, displayName = "先锋 Pioneer", enName = "Pioneer"))
        vm.selectCode(CodeOption("r1", CodeSource.IREXT, "a.bin", "r1"))
        vm.deviceName.value = "   "

        vm.saveDevice()
        awaitNextEnabled()

        assertEquals("设备名留空应默认用品牌中文名", "先锋", captured.get()?.name)
    }
}
