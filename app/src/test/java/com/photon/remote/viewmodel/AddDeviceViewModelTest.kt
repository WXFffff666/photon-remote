package com.photon.remote.viewmodel

import com.photon.remote.codebase.CodeResolver
import com.photon.remote.codebase.IrdbCsvParser
import com.photon.remote.codebase.IrextArea
import com.photon.remote.codebase.IrextIndexLoader
import com.photon.remote.codebase.IrextOperator
import com.photon.remote.codebase.location.LocationResolver
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.repository.DeviceRepository
import com.photon.remote.ir.transmitter.IrDispatcher
import com.photon.remote.ir.transmitter.TransmitterManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * AddDeviceViewModel 单元测试（FIX-1 / FIX-2）。
 *
 * FIX-1：验证 nextEnabled（combine 推导的实时 StateFlow）随选择变化自动更新——
 * 选类型→选品牌→选型号/运营商 后按钮使能从 false 变 true（修复选品牌后按钮
 * 不刷新的 Compose 观察缺陷）。
 * FIX-2：验证 STB 无地区品牌时 brandHasAreas=false（驱动 UI 仅自动跳过一次地区页，
 * 用户"上一步"可返回品牌页的状态逻辑）。
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
}
