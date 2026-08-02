package com.photon.remote.ir.irext

import net.irext.decode.sdk.bean.ACStatus
import net.irext.decode.sdk.bean.TemperatureRange
import net.irext.decode.sdk.utils.Constants

/**
 * 空调状态纯状态机（计划 §3.3 / Todo 15）。
 *
 * 职责：对 ACStatus 做**校验**（非法返回 false）与**钳制**（越界修正到合法范围），
 * 并负责**应用层语义 ↔ irext 原生层语义**的转换。不持有任何存储（持久化由
 * AppContainer 内 ACStatusCache 承担），全部为纯函数，可在 JVM 单测直接验证。
 *
 * ## 语义对照（关键！两套约定不同，必须转换）
 *
 * 应用层（本 App / 计划 §3.3 字段语义）：
 *   - acPower：0=关，1=开
 *   - acTemp：真实温度（℃），典型 16..30
 *   - acMode：0制冷 1制热 2自动 3送风 4除湿（与 irext 一致）
 *   - acWindSpeed：0自动 1低 2中 3高（与 irext 一致）
 *   - acWindDir / changeWindDir：0/1（与 irext 一致）
 *
 * irext 原生层（已对照 irext/core decoder/src 源码核实）：
 *   - acPower：**0=开 1=关**（Constants.ACPower.POWER_ON(0)/POWER_OFF(1)；
 *     C 层 apply_ac_power 直接以 ac_power 索引 power1.comp_data[power]，
 *     索引 0 即开机波形）→ 与应用层恰好相反，入 JNI 前必须反转
 *   - acTemp：**温度表索引 0..14**（Constants.ACTemperature.TEMP_16(0)..TEMP_30(14)；
 *     C 层 validate_ac_status 校验 [0,15)，apply_ac_temperature 直接以
 *     ac_temp 索引 temp1.comp_data[]）→ 应用层 16..30℃ 需转成索引（-16）
 *   - getTemperatureRange 原生返回的也是索引区间，需转回 ℃
 *
 * 若不做这两处转换，空调"开机"会发出"关机"帧、温度会被原生层校验拒绝
 * （>14 直接返回空波形），空调控制将整体失效。
 */
object ACStatusHelper {

    // ---------- 应用层字段合法区间（计划 §3.3） ----------

    /** 电源：0=关 */
    const val POWER_OFF = 0

    /** 电源：1=开 */
    const val POWER_ON = 1

    /** 模式：0=制冷 */
    const val MODE_COOL = 0

    /** 模式：1=制热 */
    const val MODE_HEAT = 1

    /** 模式：2=自动 */
    const val MODE_AUTO = 2

    /** 模式：3=送风 */
    const val MODE_FAN = 3

    /** 模式：4=除湿 */
    const val MODE_DEHUMIDITY = 4

    /** 模式数量（0..4 共 5 种） */
    const val MODE_COUNT = 5

    /** 温度绝对下限（℃） */
    const val TEMP_ABSOLUTE_MIN = 16

    /** 温度绝对上限（℃） */
    const val TEMP_ABSOLUTE_MAX = 30

    /** 风速：0=自动 */
    const val SPEED_AUTO = 0

    /** 风速：1=低 */
    const val SPEED_LOW = 1

    /** 风速：2=中 */
    const val SPEED_MEDIUM = 2

    /** 风速：3=高 */
    const val SPEED_HIGH = 3

    /** 风速数量（0..3 共 4 档） */
    const val SPEED_COUNT = 4

    /** 扫风方向：0 */
    const val WIND_DIR_MIN = 0

    /** 扫风方向：1 */
    const val WIND_DIR_MAX = 1

    /** 扫风方向数量（0/1 两态） */
    const val WIND_DIR_COUNT = 2

    /** 换风向标记：0 */
    const val CHANGE_WIND_DIR_MIN = 0

    /** 换风向标记：1 */
    const val CHANGE_WIND_DIR_MAX = 1

    // ---------- 校验（非法返回 false，不修改输入） ----------

    /**
     * 校验 6 个 AC 字段是否合法（应用层语义）。
     * 任一越界即返回 false；全部合法返回 true。
     */
    fun isValid(
        acPower: Int,
        acMode: Int,
        acTemp: Int,
        acWindSpeed: Int,
        acWindDir: Int,
        changeWindDir: Int,
    ): Boolean =
        (acPower == POWER_OFF || acPower == POWER_ON) &&
            acMode in MODE_COOL..MODE_DEHUMIDITY &&
            acTemp in TEMP_ABSOLUTE_MIN..TEMP_ABSOLUTE_MAX &&
            acWindSpeed in SPEED_AUTO..SPEED_HIGH &&
            acWindDir in WIND_DIR_MIN..WIND_DIR_MAX &&
            changeWindDir in CHANGE_WIND_DIR_MIN..CHANGE_WIND_DIR_MAX

    /**
     * 校验 ACStatus 是否合法（按应用层语义解释 bean 字段）。
     * null 视为不合法（调用方需用默认状态兜底，见 IrextDecoder.decode）。
     */
    fun isValid(ac: ACStatus?): Boolean {
        if (ac == null) return false
        return isValid(
            acPower = ac.acPower,
            acMode = ac.acMode,
            acTemp = ac.acTemp,
            acWindSpeed = ac.acWindSpeed,
            acWindDir = ac.acWindDir,
            changeWindDir = ac.changeWindDir,
        )
    }

    // ---------- 钳制（越界修正，返回新实例，不修改输入） ----------

    /**
     * 将温度钳制到合法区间。
     *
     * @param temp    待钳制温度（℃）
     * @param tempRange 模式温度范围（℃，应用层语义）；null 表示未知，
     *                  退化为绝对区间 16..30。该范围通常来自
     *                  IrextDecoder.getTemperatureRange + toAppTempRange。
     */
    fun clampTemp(temp: Int, tempRange: TemperatureRange?): Int {
        val range = toAppTempRange(tempRange)
        return temp.coerceIn(range.first, range.last)
    }

    /**
     * 应用层默认空调状态（新实例）。
     *
     * 与 data/model/ACStatusData 默认值对齐：0=关、0=制冷、26℃、0=自动、
     * windDir=0、changeWindDir=0（计划 §5.7 持久化格式一致）。
     *
     * 注意：不能直接使用 net.irext.decode.sdk.bean.ACStatus() 默认构造——那是
     * **原生层**默认（power=1=关、mode=2=自动、temp=8=24℃索引、speed=0、windDir=0），
     * 若按应用层语义解释（temp=8 视为 8℃）再钳制会得到 16℃，语义错乱。
     */
    fun defaultAppStatus(): ACStatus = ACStatus(
        POWER_OFF, MODE_COOL, 26, SPEED_AUTO, WIND_DIR_MIN,
        0, 0, 0, CHANGE_WIND_DIR_MIN,
    )

    /**
     * 钳制整个 ACStatus（应用层语义），返回修正后的**新实例**。
     *
     * @param ac        待钳制状态；null 时返回 [defaultAppStatus]
     * @param tempRange 当前模式的温度范围（℃）；null 退化为 16..30
     */
    fun clamp(ac: ACStatus?, tempRange: TemperatureRange?): ACStatus {
        val src = ac ?: defaultAppStatus()
        val range = toAppTempRange(tempRange)
        // ACStatus 为 Java 类，构造器为位置参数（acPower, acMode, acTemp, acWindSpeed,
        // acWindDir, acDisplay, acSleep, acTimer, changeWindDir）
        return ACStatus(
            src.acPower.coerceIn(POWER_OFF, POWER_ON),
            src.acMode.coerceIn(MODE_COOL, MODE_DEHUMIDITY),
            src.acTemp.coerceIn(range.first, range.last),
            src.acWindSpeed.coerceIn(SPEED_AUTO, SPEED_HIGH),
            src.acWindDir.coerceIn(WIND_DIR_MIN, WIND_DIR_MAX),
            src.acDisplay,
            src.acSleep,
            src.acTimer,
            src.changeWindDir.coerceIn(CHANGE_WIND_DIR_MIN, CHANGE_WIND_DIR_MAX),
        )
    }

    // ---------- 应用层语义 ↔ irext 原生层语义 转换 ----------

    /**
     * 应用层 ACStatus → irext 原生层 ACStatus（新实例，不修改入参）。
     *
     * 转换规则（见类注释的语义对照）：
     *   1. 先按应用层语义钳制（保证转换结果恒落在原生合法区间内）
     *   2. acPower 反转：应用 0关1开 → 原生 0开1关
     *   3. acTemp 转索引：16..30℃ → 0..14
     * 其余字段（mode/windSpeed/windDir/changeWindDir）两套语义一致，直接透传。
     *
     * 注意：decodeBinary（Java 包装层）与原生层都校验 acTemp ∈ [0,14]，
     * 不转换直接传入 26℃ 会被整体拒绝并返回空波形。
     */
    fun toNativeAcStatus(ac: ACStatus?): ACStatus {
        val clamped = clamp(ac, null)
        // ACStatus 为 Java 类，构造器为位置参数（acPower, acMode, acTemp, acWindSpeed,
        // acWindDir, acDisplay, acSleep, acTimer, changeWindDir）
        return ACStatus(
            1 - clamped.acPower,          // 应用 0关1开 → irext 0开1关
            clamped.acMode,
            tempToIndex(clamped.acTemp),  // 16..30℃ → 温度表索引 0..14
            clamped.acWindSpeed,
            clamped.acWindDir,
            clamped.acDisplay,
            clamped.acSleep,
            clamped.acTimer,
            clamped.changeWindDir,
        )
    }

    /**
     * irext 原生温度索引 → 应用层温度（℃）：0..14 → 16..30。
     */
    fun indexToTemp(index: Int): Int = index + TEMP_ABSOLUTE_MIN

    /**
     * 应用层温度（℃）→ irext 原生温度索引：16..30 → 0..14。
     */
    fun tempToIndex(temp: Int): Int = temp - TEMP_ABSOLUTE_MIN

    /**
     * irext 原生温度范围 → 应用层温度范围（℃）。
     *
     * 原生 getTemperatureRange 返回的是温度表索引区间（对照
     * irext/core decoder/src/ir_decode.c get_temperature_range：遍历
     * 0..AC_TEMP_MAX 找首尾可用索引）。以下特殊值按"无限制"处理：
     *   - 全部温度可用（all_temp=1）时原生返回 (-1,-1)
     *   - 模式非法时原生返回失败，JNI 层带出 (0,0)
     * 两者都退化为绝对区间 16..30。
     */
    fun toAppTempRange(range: TemperatureRange?): IntRange {
        val min = range?.tempMin ?: return TEMP_ABSOLUTE_MIN..TEMP_ABSOLUTE_MAX
        val max = range.tempMax
        if (min < 0 || max <= min) return TEMP_ABSOLUTE_MIN..TEMP_ABSOLUTE_MAX
        // 索引越界也退化为绝对区间（防御原生异常值）
        val lo = indexToTemp(min.coerceIn(0, 14))
        val hi = indexToTemp(max.coerceIn(0, 14))
        return lo..hi
    }

    /**
     * 由原生支持位掩码转布尔数组（对照 irext 原生 get_supported_*：
     * 位 i = 1 表示支持第 i 项）。
     *
     * 供 IrextDecoder 的 AC 支持查询使用；native 调用失败时位掩码为 0，
     * 得到全 false 数组（UI 据此隐藏全部不可用项，不会崩溃）。
     */
    fun maskToBooleans(mask: Int, count: Int): BooleanArray =
        BooleanArray(count) { i -> ((mask ushr i) and 1) == 1 }

    /** 引用 Constants 枚举，确保 ACStatusHelper 与 irext 常量表联动（编译期可查） */
    @Suppress("unused")
    private val constantCheck: Unit = run {
        require(Constants.ACMode.MODE_COOL.getValue() == MODE_COOL)
        require(Constants.ACMode.MODE_DEHUMIDITY.getValue() == MODE_DEHUMIDITY)
        require(Constants.ACWindSpeed.SPEED_AUTO.getValue() == SPEED_AUTO)
        require(Constants.ACWindSpeed.SPEED_HIGH.getValue() == SPEED_HIGH)
    }
}
