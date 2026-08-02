package com.photon.remote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photon.remote.codebase.CodeResolver
import com.photon.remote.codebase.IrdbCode
import com.photon.remote.codebase.IrdbCsvParser
import com.photon.remote.codebase.IrextIndexLoader
import com.photon.remote.codebase.IrextOperator
import com.photon.remote.codebase.IrextRemote
import com.photon.remote.codebase.location.AreaNameMatcher
import com.photon.remote.codebase.location.LocationResolver
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonAction
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.Operator
import com.photon.remote.data.model.toJson
import com.photon.remote.data.repository.DeviceRepository
import com.photon.remote.ir.irext.IrextDecoder
import com.photon.remote.ir.transmitter.IrDispatcher
import com.photon.remote.ir.transmitter.TransmitterManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.irext.decode.sdk.utils.Constants

/**
 * 添加设备向导 ViewModel（计划 §5.4 / Todo 27-28）。
 *
 * 步骤状态机：设备类型 → 品牌 →（机顶盒专属：省→市→运营商）→ 型号/码组 → 测试保存。
 * - 品牌数据源：IREXT 索引（该类型 brands）∪ irdb manifest（该类型目录品牌），按名去重、IREXT 优先；
 * - 型号数据源：IREXT remotes（STB 走运营商链路）∪ irdb 码组 CSV；
 * - 测试发送：CodeResolver.resolveOneShot（open→decode→close 自包含）+ TransmitterManager，经 IrDispatcher 串行；
 * - 保存：写入 Device（codeSource=IREXT/IRDB）+ 默认按键集（IrextKey / SendProtocol 按码源）。
 */
class AddDeviceViewModel(
    private val repository: DeviceRepository,
    private val indexLoader: IrextIndexLoader,
    private val irdbParser: IrdbCsvParser,
    private val codeResolver: CodeResolver,
    private val dispatcher: IrDispatcher,
    private val transmitter: TransmitterManager,
    private val locationResolver: LocationResolver,
) : ViewModel() {

    // ---------- 步骤状态 ----------

    /** 已选设备类型 */
    val selectedType = MutableStateFlow<DeviceType?>(null)

    /** 已选品牌 */
    val selectedBrand = MutableStateFlow<BrandOption?>(null)

    /** 已选省（STB） */
    val selectedProvince = MutableStateFlow<String?>(null)

    /** 已选城市（STB） */
    val selectedCity = MutableStateFlow<String?>(null)

    /** 已选运营商（STB，IREXT 原始运营商记录） */
    val selectedOperator = MutableStateFlow<IrextOperator?>(null)

    /** 已选型号/码组 */
    val selectedCode = MutableStateFlow<CodeOption?>(null)

    /** 当前分页位置（0..pageCount-1） */
    val currentPage = MutableStateFlow(0)

    /** 用户命名的设备名 */
    val deviceName = MutableStateFlow("")

    /** 是否正在保存 */
    val isSaving = MutableStateFlow(false)

    /** 保存成功的设备 id（非 null 即成功，UI 据此退出向导） */
    val savedDeviceId = MutableStateFlow<Long?>(null)

    /** 测试发送结果（"已发送"/"发送失败"，UI 短暂展示） */
    val testResult = MutableStateFlow<String?>(null)

    /** 当前品牌是否有 IREXT 地区数据（无则跳过地区页） */
    val brandHasAreas = MutableStateFlow(false)

    /** 定位状态（Todo 49：使用定位自动匹配省市） */
    private val _locatingState = MutableStateFlow<LocatingState>(LocatingState.Idle)
    val locatingState: StateFlow<LocatingState> = _locatingState.asStateFlow()

    /** 品牌候选（按类型加载） */
    private val _brands = MutableStateFlow<List<BrandOption>>(emptyList())
    val brands: StateFlow<List<BrandOption>> = _brands.asStateFlow()

    /** 型号候选（按品牌/地区/运营商加载） */
    private val _models = MutableStateFlow<List<CodeOption>>(emptyList())
    val models: StateFlow<List<CodeOption>> = _models.asStateFlow()

    /** 省的候选（STB 品牌） */
    private val _provinces = MutableStateFlow<List<String>>(emptyList())
    val provinces: StateFlow<List<String>> = _provinces.asStateFlow()

    /** 城市候选（选中省后） */
    private val _cities = MutableStateFlow<List<String>>(emptyList())
    val cities: StateFlow<List<String>> = _cities.asStateFlow()

    /** 运营商候选（选中城市后） */
    private val _operators = MutableStateFlow<List<IrextOperator>>(emptyList())
    val operators: StateFlow<List<IrextOperator>> = _operators.asStateFlow()

    /**
     * 分页数：未选类型 1 页；机顶盒 5 页（类型/品牌/地区/型号/测试）；
     * 其余 4 页（类型/品牌/型号/测试）。
     */
    val pageCount: Int
        get() = when {
            selectedType.value == null -> 1
            selectedType.value == DeviceType.STB -> 5
            else -> 4
        }

    /** 是否为机顶盒（决定是否有地区分页） */
    val isStbAreaStep: Boolean
        get() = selectedType.value == DeviceType.STB

    // ---------- 步骤推进 ----------

    /** 选择设备类型 → 加载品牌（翻页由底部"下一步"控制） */
    fun selectType(type: DeviceType) {
        selectedType.value = type
        selectedBrand.value = null
        selectedCode.value = null
        resetStbSelection()
        loadBrands(type)
    }

    /** 选择品牌 → 加载型号/地区（翻页由底部"下一步"控制） */
    fun selectBrand(brand: BrandOption) {
        selectedBrand.value = brand
        selectedCode.value = null
        resetStbSelection()
        brandHasAreas.value = brand.irextBrandId?.let { indexLoader.getAreas(it).isNotEmpty() } == true
        if (selectedType.value == DeviceType.STB) {
            if (brandHasAreas.value) loadProvinces(brand)
            else loadModels()
        } else {
            loadModels()
        }
    }

    /** 选择省 → 加载城市（不翻页，同页联动） */
    fun selectProvince(province: String) {
        selectedProvince.value = province
        selectedCity.value = null
        selectedOperator.value = null
        selectedCode.value = null
        val brand = selectedBrand.value ?: return
        _cities.value = indexLoader.getCities(brand.irextBrandId ?: -1, province).map { it.name }
    }

    /** 选择城市 → 加载运营商（不翻页，同页联动） */
    fun selectCity(city: String) {
        selectedCity.value = city
        selectedOperator.value = null
        selectedCode.value = null
        val brand = selectedBrand.value ?: return
        _operators.value = indexLoader.getOperators(brand.irextBrandId ?: -1, selectedProvince.value ?: "", city)
    }

    /** 选择运营商 → 加载型号（翻页由底部"下一步"控制） */
    fun selectOperator(operator: IrextOperator) {
        selectedOperator.value = operator
        selectedCode.value = null
        loadModels()
    }

    // ---------- 定位（Todo 49：使用定位自动匹配省市） ----------

    /**
     * 定位当前位置并自动匹配 IREXT 省市（权限已由 UI 层处理）。
     *
     * 流程：LocationResolver.resolveProvinceCity() → 匹配 irext areas/cities → 预填
     * selectedProvince/selectedCity → 联动加载运营商列表（用户继续手动细分选运营商，
     * 或手动改选省市）。任一步失败降级为 [LocatingState.Failed]，UI 提示手动选择，
     * 不抛异常；成功（含仅省匹配）为 [LocatingState.Found]。
     */
    fun locateProvinceCity() {
        val brandId = selectedBrand.value?.irextBrandId ?: return
        if (_locatingState.value == LocatingState.Locating) return
        viewModelScope.launch {
            _locatingState.value = LocatingState.Locating
            val result = locationResolver.resolveProvinceCity()
            if (result == null) {
                _locatingState.value = LocatingState.Failed("定位失败（无定位或定位服务关闭），请手动选择")
                return@launch
            }
            val (provinceRaw, cityRaw) = result
            val areas = indexLoader.getAreas(brandId).map { it.name }
            val province = AreaNameMatcher.matchArea(areas, provinceRaw)
            if (province == null) {
                _locatingState.value = LocatingState.Failed("未能匹配到所在省份（$provinceRaw），请手动选择")
                return@launch
            }
            // 选中省 + 联动城市列表（与 selectProvince 语义一致）
            selectedProvince.value = province
            selectedCity.value = null
            selectedOperator.value = null
            selectedCode.value = null
            val cityList = indexLoader.getCities(brandId, province).map { it.name }
            _cities.value = cityList
            // 匹配市：先按定位市名，再按省名兜底（直辖市 irext 城市节点与省同名，如"北京市"）
            val city = AreaNameMatcher.matchCity(cityList, cityRaw)
                ?: AreaNameMatcher.matchCity(cityList, provinceRaw)
            if (city != null) {
                selectedCity.value = city
                _operators.value = indexLoader.getOperators(brandId, province, city)
            }
            _locatingState.value = LocatingState.Found(province, city)
        }
    }

    /** 定位权限被拒（UI 层回调）→ 提示手动选择 */
    fun locatePermissionDenied() {
        _locatingState.value = LocatingState.Failed("定位权限被拒绝，请手动选择")
    }

    /** 选择型号/码组（翻页由底部"下一步"控制） */
    fun selectCode(code: CodeOption) {
        selectedCode.value = code
    }

    /** 上一步（类型页不可再退） */
    fun previousPage() {
        if (currentPage.value > 0) currentPage.value -= 1
    }

    /** 下一步（末页不可再进） */
    fun nextPage() {
        if (currentPage.value < pageCount - 1) currentPage.value += 1
    }

    /** 当前页是否满足"下一步"条件（底部按钮使能） */
    fun isNextEnabled(page: Int): Boolean = when (page) {
        0 -> selectedType.value != null
        1 -> selectedBrand.value != null
        2 -> if (isStbAreaStep) selectedOperator.value != null else selectedCode.value != null
        3 -> if (isStbAreaStep) selectedCode.value != null else false
        else -> false
    }

    /** 设置当前页（分页器回同步；仅接受合法值） */
    fun setPage(page: Int) {
        if (page in 0 until pageCount && page != currentPage.value) currentPage.value = page
    }

    /** 更新设备名 */
    fun setDeviceName(name: String) {
        deviceName.value = name
    }

    // ---------- 数据加载 ----------

    /** 品牌列表：IREXT（该类型）∪ irdb（含该类型目录）；同名去重，IREXT 优先 */
    private fun loadBrands(type: DeviceType) {
        viewModelScope.launch {
            val result = linkedMapOf<String, BrandOption>()
            // irdb 部分：品牌存在对应类型目录（tv/ac/stb/audio/projector/other）
            val irdbType = type.irdbType()
            if (irdbType != null) {
                irdbParser.listBrands().filter { irdbType in irdbParser.listTypes(it) }
                    .forEach { result[it] = BrandOption(it, CodeSource.IRDB, null) }
            }
            // irext 部分：按设备大类取品牌（覆盖同名 irdb 品牌）
            type.irextCategoryId()?.let { categoryId ->
                indexLoader.getBrands(categoryId).forEach { brand ->
                    result[brand.name] = BrandOption(brand.name, CodeSource.IREXT, brand.id)
                }
            }
            _brands.value = result.values.sortedBy { it.name }
        }
    }

    /** 省列表（STB，IREXT 品牌） */
    private fun loadProvinces(brand: BrandOption) {
        viewModelScope.launch {
            _provinces.value = indexLoader.getAreas(brand.irextBrandId ?: -1).map { it.name }
        }
    }

    /**
     * 型号列表：IREXT remotes（STB 已选运营商时取运营商链路）+ irdb 码组。
     * 非 STB / STB 非运营商品牌直接取品牌直属 remotes。
     */
    private fun loadModels() {
        val type = selectedType.value ?: return
        val brand = selectedBrand.value ?: return
        viewModelScope.launch {
            val result = linkedMapOf<String, CodeOption>()
            // irdb 部分（按品牌 + 类型目录）
            val irdbType = type.irdbType()
            if (irdbType != null && irdbType in irdbParser.listTypes(brand.name)) {
                irdbParser.listModels(brand.name, irdbType).forEach { model ->
                    val ref = "${brand.name}/$irdbType/$model"
                    result[ref] = CodeOption(model, CodeSource.IRDB, ref, model)
                }
            }
            // irext 部分
            brand.irextBrandId?.let { brandId ->
                val remotes: List<IrextRemote> = if (type == DeviceType.STB && selectedOperator.value != null) {
                    selectedOperator.value!!.remotes
                } else {
                    indexLoader.getRemotes(brandId)
                }
                remotes.forEach { remote ->
                    result[remote.bin] = CodeOption(remote.name, CodeSource.IREXT, remote.bin, remote.name)
                }
            }
            _models.value = result.values.sortedBy { it.name }
        }
    }

    // ---------- 测试发送（步骤 4，resolveOneShot 自包含） ----------

    /**
     * 测试发送指定按键（电源/音量+ 等）。
     * 走 resolveOneShot（open→decode→close 自包含，遵守 CodeResolver 规则 b/d），
     * 结果经 IrDispatcher 串行，UI 显示"已发送/发送失败"。
     */
    fun testSend(button: RemoteButton) {
        viewModelScope.launch {
            val device = buildPreviewDevice() ?: return@launch
            val ok = dispatcher.onQueue {
                codeResolver.resolveOneShot(device, button)?.let { transmitter.transmit(it) } ?: false
            }
            testResult.value = if (ok) "已发送" else "发送失败"
            delay(1200)
            testResult.value = null
        }
    }

    // ---------- 保存 ----------

    /**
     * 测试页按键（电源/音量+ 等，id=0 不入库）：按 keyId 从默认按键集取。
     * 返回 null 表示该设备无此键（如 irdb 码组缺少对应功能）。
     */
    fun testButton(keyId: String): RemoteButton? {
        val device = buildPreviewDevice() ?: return null
        return defaultButtons(0L, device).firstOrNull { it.keyId == keyId }
    }

    /** 保存设备 + 默认按键集，成功后记录 savedDeviceId */
    fun saveDevice() {
        val type = selectedType.value ?: return
        val brand = selectedBrand.value ?: return
        val code = selectedCode.value ?: return
        if (isSaving.value) return
        viewModelScope.launch {
            isSaving.value = true
            try {
                val device = Device(
                    name = deviceName.value.trim().ifBlank { type.label },
                    type = type,
                    brand = brand.name,
                    region = selectedProvince.value,
                    city = selectedCity.value,
                    operator = operatorFromName(selectedOperator.value?.operator),
                    model = code.model,
                    codeSource = code.source,
                    codeRef = code.codeRef,
                    layoutId = "default",
                    // 卡片取色种子：品牌名哈希（计划 §2.2 colorSeed）
                    colorSeed = (brand.name.hashCode().toLong() and 0xFFFFFFL) or 0xFF000000L,
                )
                val id = repository.addDevice(device)
                repository.addButtons(defaultButtons(id, device))
                savedDeviceId.value = id
            } finally {
                isSaving.value = false
            }
        }
    }

    // ---------- 内部 ----------

    /** 预览设备（测试页用，id=0 不入库） */
    private fun buildPreviewDevice(): Device? {
        val type = selectedType.value ?: return null
        val brand = selectedBrand.value ?: return null
        val code = selectedCode.value ?: return null
        return Device(
            id = 0L,
            name = deviceName.value.trim().ifBlank { type.label },
            type = type,
            brand = brand.name,
            region = selectedProvince.value,
            city = selectedCity.value,
            operator = operatorFromName(selectedOperator.value?.operator),
            model = code.model,
            codeSource = code.source,
            codeRef = code.codeRef,
        )
    }

    private fun resetStbSelection() {
        selectedProvince.value = null
        selectedCity.value = null
        selectedOperator.value = null
        _cities.value = emptyList()
        _operators.value = emptyList()
    }

    /**
     * 默认按键集（计划 §5.5 模板）：
     * - IREXT：POWER/VOL_UP/VOL_DOWN/CH_UP/CH_DOWN/MUTE/NUM_0-9/OK/UP/DOWN/LEFT/RIGHT/BACK/MENU/INPUT
     *   （ButtonAction.IrextKey，binaryRef 以 Device.codeRef 为准，见 §2.3）；
     * - IRDB：从 CSV 按功能名映射同款键位（ButtonAction.SendProtocol，hex 经 IrdbHexConverter）；
     * - AC 设备仅生成电源/静音（空调走 AcPanel，不生成通用键）。
     */
    private fun defaultButtons(deviceId: Long, device: Device): List<RemoteButton> {
        return if (device.codeSource == CodeSource.IREXT) {
            if (device.type == DeviceType.AC) {
                listOf(
                    irextButton(deviceId, "POWER", "电源", 0, IrextDecoder.APP_KEY_POWER, device.codeRef),
                    irextButton(deviceId, "MUTE", "静音", 1, IrextDecoder.APP_KEY_MUTE, device.codeRef),
                )
            } else {
                COMMON_KEY_ORDER.mapIndexed { index, (keyId, label, keyCode) ->
                    irextButton(deviceId, keyId, label, index, keyCode, device.codeRef)
                }
            }
        } else {
            irdbButtons(deviceId, device)
        }
    }

    /** IREXT 默认按键（电源圆形，其余圆角矩形） */
    private fun irextButton(
        deviceId: Long, keyId: String, label: String, order: Int, keyCode: Int, codeRef: String,
    ): RemoteButton = RemoteButton(
        deviceId = deviceId,
        keyId = keyId,
        label = label,
        actionJson = ButtonAction.IrextKey(keyCode, codeRef).toJson(),
        order = order,
        shape = if (keyId == "POWER") ButtonShape.CIRCLE else ButtonShape.ROUNDED,
    )

    /** IRDB 默认按键：解析 CSV 按功能名映射到标准键位，首个命中优先；协议映射不了的键跳过 */
    private fun irdbButtons(deviceId: Long, device: Device): List<RemoteButton> {
        val type = device.type.irdbType() ?: return emptyList()
        val model = device.model ?: return emptyList()
        val codes = irdbParser.codes(device.brand, type, model)
        val picked = linkedMapOf<String, IrdbCode>()

        // 电源优先 "POWER ON"，其次 "POWER OFF"，再任意 POWER
        val power = codes.firstOrNull { it.functionName.uppercase().contains("POWER ON") }
            ?: codes.firstOrNull { it.functionName.uppercase().contains("POWER OFF") }
            ?: codes.firstOrNull { it.functionName.uppercase().contains("POWER") }
        if (power != null) picked["POWER"] = power

        for (code in codes) {
            val keyId = mapIrdbFunction(code.functionName) ?: continue
            if (keyId != "POWER" && !picked.containsKey(keyId)) picked[keyId] = code
        }

        // 按标准键序输出（AC 设备仅电源/静音）
        val sequence = if (device.type == DeviceType.AC) listOf("POWER", "MUTE")
        else COMMON_KEY_ORDER.map { it.first }
        return sequence.mapNotNull { keyId ->
            val code = picked[keyId] ?: return@mapNotNull null
            val hex = IrdbHexConverter.toHex(code) ?: return@mapNotNull null   // UNKNOWN 协议跳过
            RemoteButton(
                deviceId = deviceId,
                keyId = keyId,
                label = if (keyId == "POWER") "电源" else code.functionName.trim(),
                actionJson = ButtonAction.SendProtocol(code.mappedProtocol!!, hex).toJson(),
                order = sequence.indexOf(keyId),
                shape = if (keyId == "POWER") ButtonShape.CIRCLE else ButtonShape.ROUNDED,
            )
        }
    }

    companion object {
        /** 标准键序：keyId / 显示名 / IREXT 应用层键码（IrextDecoder.APP_KEY_*） */
        private val COMMON_KEY_ORDER: List<Triple<String, String, Int>> = listOf(
            Triple("POWER", "电源", IrextDecoder.APP_KEY_POWER),
            Triple("MUTE", "静音", IrextDecoder.APP_KEY_MUTE),
            Triple("VOL_UP", "音量+", IrextDecoder.APP_KEY_VOL_UP),
            Triple("VOL_DOWN", "音量-", IrextDecoder.APP_KEY_VOL_DOWN),
            Triple("CH_UP", "频道+", IrextDecoder.APP_KEY_CH_UP),
            Triple("CH_DOWN", "频道-", IrextDecoder.APP_KEY_CH_DOWN),
            Triple("OK", "确定", IrextDecoder.APP_KEY_OK),
            Triple("UP", "上", IrextDecoder.APP_KEY_UP),
            Triple("DOWN", "下", IrextDecoder.APP_KEY_DOWN),
            Triple("LEFT", "左", IrextDecoder.APP_KEY_LEFT),
            Triple("RIGHT", "右", IrextDecoder.APP_KEY_RIGHT),
            Triple("BACK", "返回", IrextDecoder.APP_KEY_BACK),
            Triple("MENU", "菜单", IrextDecoder.APP_KEY_MENU),
            Triple("INPUT", "输入源", IrextDecoder.APP_KEY_INPUT),
            Triple("NUM_0", "0", IrextDecoder.APP_KEY_NUM_0),
            Triple("NUM_1", "1", IrextDecoder.APP_KEY_NUM_0 + 1),
            Triple("NUM_2", "2", IrextDecoder.APP_KEY_NUM_0 + 2),
            Triple("NUM_3", "3", IrextDecoder.APP_KEY_NUM_0 + 3),
            Triple("NUM_4", "4", IrextDecoder.APP_KEY_NUM_0 + 4),
            Triple("NUM_5", "5", IrextDecoder.APP_KEY_NUM_0 + 5),
            Triple("NUM_6", "6", IrextDecoder.APP_KEY_NUM_0 + 6),
            Triple("NUM_7", "7", IrextDecoder.APP_KEY_NUM_0 + 7),
            Triple("NUM_8", "8", IrextDecoder.APP_KEY_NUM_8),
            Triple("NUM_9", "9", IrextDecoder.APP_KEY_NUM_9),
        )
    }
}

/** 向导候选模型：品牌（IREXT 带索引 id；IRDB 为 null） */
data class BrandOption(
    val name: String,
    val source: CodeSource,
    val irextBrandId: Int? = null,
)

/** 向导候选模型：型号/码组（IREXT=bin 文件；IRDB=品牌/类型/型号 CSV 路径） */
data class CodeOption(
    val name: String,
    val source: CodeSource,
    val codeRef: String,
    val model: String? = null,
)

/**
 * 定位状态（Todo 49）：Idle=未定位 / Locating=定位中 / Found=已定位(省,市，市可能为 null) /
 * Failed=失败原因（UI 提示手动选择）。
 */
sealed interface LocatingState {
    /** 未发起定位 */
    data object Idle : LocatingState

    /** 定位中（按钮转圈 + 禁用） */
    data object Locating : LocatingState

    /** 已定位成功（city=null 表示省已匹配但市未匹配，仍可手动选市） */
    data class Found(val province: String?, val city: String?) : LocatingState

    /** 定位失败 / 权限被拒 / 省份未匹配（展示 reason 并引导手动选择） */
    data class Failed(val reason: String) : LocatingState
}

/** 设备类型 → IREXT 设备大类 id（OTHER 无对应大类） */
private fun DeviceType.irextCategoryId(): Int? = when (this) {
    DeviceType.AC -> Constants.CategoryID.AIR_CONDITIONER.getValue()    // 1
    DeviceType.TV -> Constants.CategoryID.TV.getValue()                  // 2
    DeviceType.STB -> Constants.CategoryID.STB.getValue()                // 3
    DeviceType.FAN -> Constants.CategoryID.FAN.getValue()                // 7
    DeviceType.PROJECTOR -> Constants.CategoryID.PROJECTOR.getValue()    // 8
    DeviceType.AUDIO -> Constants.CategoryID.STEREO.getValue()           // 9
    DeviceType.PURIFIER -> Constants.CategoryID.AIR_CLEANER.getValue()   // 13
    DeviceType.OTHER -> null
}

/** 设备类型 → irdb 目录名（FAN / PURIFIER 无 irdb 目录） */
private fun DeviceType.irdbType(): String? = when (this) {
    DeviceType.TV -> "tv"
    DeviceType.STB -> "stb"
    DeviceType.AC -> "ac"
    DeviceType.AUDIO -> "audio"
    DeviceType.PROJECTOR -> "projector"
    DeviceType.OTHER -> "other"
    else -> null
}

/** 运营商名 → Operator 枚举（"移动/联通/电信"，其余按广电） */
private fun operatorFromName(name: String?): Operator? = when {
    name.isNullOrBlank() -> null
    name.contains("移动") -> Operator.CMCC
    name.contains("联通") -> Operator.CUCC
    name.contains("电信") -> Operator.CTCC
    else -> Operator.CABLENET   // 广电/有线/天威/华数等
}

/** irdb 功能名 → 标准键位（映射不了的返回 null，跳过该键） */
private fun mapIrdbFunction(functionName: String): String? {
    val name = functionName.trim().uppercase().removePrefix("KEY_").removePrefix("KEY ")
    return when {
        name.contains("POWER") -> "POWER"
        name == "VOLUME+" || name.contains("VOL+") -> "VOL_UP"
        name == "VOLUME-" || name.contains("VOL-") -> "VOL_DOWN"
        name == "CHANNEL+" || name == "PAGE UP" || name == "PAGE_UP" || name.contains("CH+") -> "CH_UP"
        name == "CHANNEL-" || name == "PAGE DOWN" || name == "PAGE_DOWN" || name.contains("CH-") -> "CH_DOWN"
        name.contains("MUTE") -> "MUTE"
        name == "OK" || name == "ENTER" || name == "SELECT" -> "OK"
        name == "UP" -> "UP"
        name == "DOWN" -> "DOWN"
        name == "LEFT" -> "LEFT"
        name == "RIGHT" -> "RIGHT"
        name == "BACK" || name == "EXIT" || name == "RETURN" -> "BACK"
        name.contains("MENU") -> "MENU"
        name.contains("INPUT") || name.contains("SOURCE") || name.contains("AV") -> "INPUT"
        name.toIntOrNull()?.let { it in 0..9 } == true -> "NUM_$name"
        else -> null
    }
}
