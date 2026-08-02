package com.photon.remote.ir.irext

import com.photon.remote.ir.core.IRPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.irext.decode.sdk.IRDecode
import net.irext.decode.sdk.bean.ACStatus
import net.irext.decode.sdk.bean.TemperatureRange
import net.irext.decode.sdk.utils.Constants

/**
 * IREXT JNI 解码桥（计划 §3.3 / Todo 14-15）。
 *
 * 包装官方 IRDecode（net.irext.decode.sdk，MIT），提供"绝不崩溃"的惰性加载与
 * 会话管理：
 *   - [isAvailable]：惰性 try/catch(Throwable) 加载 libirdecode.so。仅 arm 真机有 so；
 *     x86/无 so 环境 isAvailable=false，上层 UI 降级为协议编码器直发，绝不崩溃
 *     （Todo 14 Acceptance：x86 模拟器 App 正常启动）。
 *   - 所有 JNI 调用一律包 try/catch(Throwable) 返回 false/null/空数组，兑现"绝不崩溃"。
 *   - 会话规则：open（幂等守卫：已 open 先 close）→ decode → close；
 *     close() 必须清空 [currentOpenRef]/[isOpen]，防止连续 one-shot 泄漏原生会话
 *     （Todo 15 Acceptance：连续两次 one-shot 后无残留 open 会话）。
 *
 * 线程模型：open/decode/close 须经 IrDispatcher 串行调用（计划 §3.4，JNI 单例共享
 * 原生状态，并发解码会互相污染）；本对象不自行加锁。
 *
 * 注意（对照 irext/core 源码核实）：
 *   - decodeBinary 返回纯 mark/space 微秒序列（无频率字段；TV 路径 tv_binary_decode、
 *     AC 路径 create_ir_frame 均只产出时间序列，官方示例 transmitIr 硬编码 38000），
 *     故本桥固定用 38000Hz。
 *   - AC 的 keyCode 走官方 Java 层约定：Constants.ACFunction 1..7（官方 ControlHelper /
 *     compose 示例 RemoteCommandMapper 均如此）；C 层另有 0,1,2,3,9,10,11 变体，
 *     irext 两层自身不一致，以官方 SDK 常量表为准（TODO 真机验证：若电源键发出
 *     模式帧等错位，改为 C 层变体映射即可，映射表集中在 [translateKeyCode]）。
 *   - ACStatus 应用层语义与原生层不同，入 JNI 前必须经 ACStatusHelper.toNativeAcStatus
 *     转换（power 反转 + temp 转索引），详见 ACStatusHelper 类注释。
 */
object IrextDecoder {

    /** 默认载波频率（Hz）：IREXT 解码输出不含频率，官方示例一律用 38000 */
    const val DEFAULT_FREQUENCY = 38_000

    // ---------- 应用层语义按键常量（计划 §2.3，IrextDecoder 内维护 ↔ irext 官方键位映射） ----------

    /** 电源 */
    const val APP_KEY_POWER = 0

    /** 数字 0..8（1..9 对应数字 0..8，见计划 §2.3） */
    const val APP_KEY_NUM_0 = 1
    const val APP_KEY_NUM_8 = 9

    /** 频道 + / - */
    const val APP_KEY_CH_UP = 10
    const val APP_KEY_CH_DOWN = 11

    /** 音量 + / - */
    const val APP_KEY_VOL_UP = 12
    const val APP_KEY_VOL_DOWN = 13

    /** 静音 */
    const val APP_KEY_MUTE = 14

    /** 确认 */
    const val APP_KEY_OK = 15

    /** 方向键 */
    const val APP_KEY_UP = 16
    const val APP_KEY_DOWN = 17
    const val APP_KEY_LEFT = 18
    const val APP_KEY_RIGHT = 19

    /** 返回 */
    const val APP_KEY_BACK = 20

    /** 菜单 */
    const val APP_KEY_MENU = 21

    /** 输入源 */
    const val APP_KEY_INPUT = 22

    /** 数字 9（计划 §2.3 未编号，此处补充，对应 irext 通道槽位第 10 个） */
    const val APP_KEY_NUM_9 = 23

    // ---------- 加载与可用性 ----------

    /**
     * 惰性 + 防崩溃加载：仅 arm 真机有 libirdecode.so。
     *
     * catch 所有异常（UnsatisfiedLinkError / ExceptionInInitializerError /
     * SecurityException 等）保证"绝不崩溃"；首次访问触发 System.loadLibrary。
     */
    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("irdecode")
            true
        } catch (e: Throwable) {
            // 无 so（x86 模拟器等）：标记不可用，UI 降级，不抛异常
            false
        }
    }

    // ---------- 会话状态（open 守卫 / close 清空，计划 §3.3） ----------

    /** 当前是否持有已打开的 binary（跨线程读，volatile） */
    @Volatile
    private var _isOpen = false

    /** 当前是否持有已打开的 binary */
    val isOpen: Boolean get() = _isOpen

    /** 当前打开的 binary 名（规则 d 会话恢复用）；close 后必须为 null */
    @Volatile
    var currentOpenRef: String? = null
        private set

    /** 当前打开 binary 的 category（open 时记录，translateKeyCode 需要） */
    @Volatile
    private var currentCategory: Int = Constants.CategoryID.TV.getValue()

    // ---------- 会话 API ----------

    /**
     * 打开一个 IREXT 二进制码组。
     *
     * 幂等守卫：若已 open 先 close（防止重复 openBinary 泄漏原生状态，计划规则 c）。
     *
     * @param refName 二进制名（设备 codeRef，规则 d 恢复会话用）
     * @param category 设备大类（Constants.CategoryID，如 TV=2 / STB=3 / AC=1）
     * @param subCate  子类（irext 索引的 sub_cate；TV/STB 等命令类为 1 或 2，
     *                 AC 通常 0，以 IrextBinaryRef 为准）
     * @param bytes    binary 文件内容
     * @return 打开成功返回 true；失败（含任何异常）返回 false
     */
    suspend fun open(refName: String, category: Int, subCate: Int, bytes: ByteArray): Boolean {
        if (!isAvailable) return false
        // 幂等守卫：已 open 先 close（计划 §3.3 规则 c）
        if (_isOpen) close()
        return withContext(Dispatchers.IO) {
            try {
                val ret = IRDecode.getInstance().openBinary(category, subCate, bytes, bytes.size)
                if (ret >= 0) {
                    currentCategory = category
                    _isOpen = true
                    currentOpenRef = refName
                    true
                } else {
                    // 打开失败（码组损坏/类别非法等）：保持未打开状态
                    false
                }
            } catch (e: Throwable) {
                // JNI 或包装层异常：绝不崩溃，返回 false（调用方走降级路径）
                false
            }
        }
    }

    /**
     * 解码按键。
     *
     * @param keyCode  应用层语义按键（APP_KEY_* 常量，见 [translateKeyCode]）
     * @param acStatus 空调状态（应用层语义；非 AC 设备传默认 ACStatus() 即可，
     *                 irext decodeBinary 恒接收 ACStatus）
     * @return 波形（频率固定 38000，见类注释）；未打开/按键无效/任何异常返回 null
     */
    fun decode(keyCode: Int, acStatus: ACStatus): IRPattern? {
        if (!isAvailable || !_isOpen) return null
        return try {
            // 应用层语义 → irext 原生语义（power 反转 + temp 索引，见 ACStatusHelper）
            val nativeStatus = ACStatusHelper.toNativeAcStatus(acStatus)
            val irextKey = translateKeyCode(currentCategory, keyCode)
            if (irextKey < 0) return null   // 该设备类别不支持此按键
            val decoded = IRDecode.getInstance().decodeBinary(irextKey, nativeStatus) ?: return null
            if (decoded.isEmpty()) return null  // 解码失败（原生返回空数组）
            // IREXT 返回纯 mark/space 微秒序列（以 mark 开头），频率官方约定 38000
            IRPattern(DEFAULT_FREQUENCY, decoded)
        } catch (e: Throwable) {
            // JNI 异常（含 UnsatisfiedLinkError 等）：绝不崩溃，返回 null
            null
        }
    }

    /**
     * 关闭当前 binary 会话。
     *
     * 无论原生 close 是否成功，都必须清空 [currentOpenRef]/[isOpen]
     * （计划 §3.3：防止连续 one-shot 泄漏会话；Todo 15 Acceptance 要求）。
     */
    fun close() {
        if (!isAvailable) {
            // 无 so 环境：无需调用原生，仍要清空状态
            _isOpen = false
            currentOpenRef = null
            return
        }
        try {
            IRDecode.getInstance().closeBinary()
        } catch (e: Throwable) {
            // 关闭失败不致命：状态照常清空，避免上层认为会话仍存活
        }
        _isOpen = false
        currentOpenRef = null
    }

    // ---------- AC 支持查询（全部 try/catch 返回空值，绝不崩溃） ----------

    /**
     * 当前已打开空调码组的温度范围（原生 TemperatureRange，索引语义）。
     * 调用方用 ACStatusHelper.toAppTempRange 转应用层 ℃ 区间。
     * 失败返回 null（未打开 AC 码组 / JNI 异常）。
     */
    fun getTemperatureRange(acMode: Int): TemperatureRange? = try {
        IRDecode.getInstance().getTemperatureRange(acMode)
    } catch (e: Throwable) {
        // JNI 异常：返回 null，UI 退化为绝对区间 16..30
        null
    }

    /**
     * 当前空调码组支持的模式（下标 0..4 = 制冷/制热/自动/送风/除湿，true=支持）。
     * 失败返回全 false（UI 据此隐藏全部模式，不崩溃）。
     */
    fun getACSupportedMode(): BooleanArray = try {
        IRDecode.getInstance().getACSupportedMode().let { arr ->
            BooleanArray(5) { i -> arr.getOrElse(i) { 0 } == 1 }
        }
    } catch (e: Throwable) {
        // JNI 异常：全 false 降级
        BooleanArray(5) { false }
    }

    /**
     * 指定模式下支持的风速档位（下标 0..3 = 自动/低/中/高，true=支持）。
     * 失败返回全 false。
     */
    fun getACSupportedWindSpeed(acMode: Int): BooleanArray = try {
        IRDecode.getInstance().getACSupportedWindSpeed(acMode).let { arr ->
            BooleanArray(4) { i -> arr.getOrElse(i) { 0 } == 1 }
        }
    } catch (e: Throwable) {
        // JNI 异常：全 false 降级
        BooleanArray(4) { false }
    }

    /**
     * 指定模式下支持的扫风功能（下标 0=扫风开 1=扫风关，true=支持）。
     * 失败返回全 false。
     */
    fun getACSupportedSwing(acMode: Int): BooleanArray = try {
        IRDecode.getInstance().getACSupportedSwing(acMode).let { arr ->
            BooleanArray(2) { i -> arr.getOrElse(i) { 0 } == 1 }
        }
    } catch (e: Throwable) {
        // JNI 异常：全 false 降级
        BooleanArray(2) { false }
    }

    // ---------- 按键映射（应用层语义 → irext 官方键位） ----------

    /**
     * 应用层按键 → irext 官方 keyCode（计划 §2.3 语义，对照 irext/core ir_decode.h
     * 的 KEY_TV / KEY_STB 常量与官方示例 RemoteCommandMapper）。
     *
     * AC 类（category == AIR_CONDITIONER=1）：按官方 Java 层约定映射为
     * Constants.ACFunction 功能码 1..7（与官方 ControlHelper / compose 示例一致）；
     * 无对应功能的按键返回 -1（调用方放弃发送）。
     *
     * 非 AC 类（TV/STB/网络盒子/DVD…）：映射到 irext 标准键位（0..13 标准键 +
     * 14..23 通道槽位）。数字键 0..9 → 通道槽位 14..23（STB/TV 二进制通用布局，
     * 数字 0=槽 0…数字 9=槽 9；TODO 真机验证）。未知按键原样透传，交由原生
     * key 范围校验兜底（超范围返回空波形 → decode 返回 null）。
     *
     * TODO 真机验证：AC 键位存在 irext 自身两层约定不一致的历史问题
     * （Java 层 ACFunction 1..7 vs C 层 0,1,2,3,9,10,11），本桥以官方 SDK
     * 常量表（Java 层）为准；若实测功能错位，改走 C 层变体：
     * 0=电源 1=模式 2/7=温度+ 3/8=温度- 9=风速 10=扫风 11=风向固定。
     */
    fun translateKeyCode(category: Int, keyCode: Int): Int {
        if (category == Constants.CategoryID.AIR_CONDITIONER.getValue()) {
            // 空调：应用层按键 → ACFunction 功能码（官方 ControlHelper 同款映射）
            return when (keyCode) {
                APP_KEY_POWER -> Constants.ACFunction.FUNCTION_SWITCH_POWER.getValue()        // 1 电源
                APP_KEY_UP -> Constants.ACFunction.FUNCTION_SWITCH_WIND_SPEED.getValue()      // 5 风速
                APP_KEY_DOWN -> Constants.ACFunction.FUNCTION_SWITCH_WIND_DIR.getValue()      // 6 风向
                APP_KEY_RIGHT -> Constants.ACFunction.FUNCTION_CHANGE_MODE.getValue()         // 2 模式
                APP_KEY_OK -> Constants.ACFunction.FUNCTION_SWITCH_SWING.getValue()           // 7 扫风
                APP_KEY_VOL_UP -> Constants.ACFunction.FUNCTION_TEMPERATURE_UP.getValue()     // 3 温度+
                APP_KEY_VOL_DOWN -> Constants.ACFunction.FUNCTION_TEMPERATURE_DOWN.getValue() // 4 温度-
                else -> -1   // 空调无此按键（数字键/菜单等不适用）
            }
        }
        // 非 AC：应用层按键 → irext 标准键位
        return when (keyCode) {
            APP_KEY_POWER -> 0        // KEY_TV/STB_POWER
            APP_KEY_NUM_0 -> 14       // 通道槽位 0（数字 0）
            APP_KEY_NUM_0 + 1 -> 15   // 通道槽位 1（数字 1）
            APP_KEY_NUM_0 + 2 -> 16   // 通道槽位 2（数字 2）
            APP_KEY_NUM_0 + 3 -> 17   // 通道槽位 3（数字 3）
            APP_KEY_NUM_0 + 4 -> 18   // 通道槽位 4（数字 4）
            APP_KEY_NUM_0 + 5 -> 19   // 通道槽位 5（数字 5）
            APP_KEY_NUM_0 + 6 -> 20   // 通道槽位 6（数字 6）
            APP_KEY_NUM_0 + 7 -> 21   // 通道槽位 7（数字 7）
            APP_KEY_NUM_8 -> 22       // 通道槽位 8（数字 8）
            APP_KEY_NUM_9 -> 23       // 通道槽位 9（数字 9）
            APP_KEY_CH_UP -> 12       // STB PAGE_UP / TV 无专用 CH 键（TODO 真机验证）
            APP_KEY_CH_DOWN -> 13     // STB PAGE_DOWN（同上）
            APP_KEY_VOL_UP -> 7       // KEY_VOL_PLUS
            APP_KEY_VOL_DOWN -> 8     // KEY_VOL_MINUS
            APP_KEY_MUTE -> 1         // KEY_MUTE
            APP_KEY_OK -> 6           // KEY_OK
            APP_KEY_UP -> 2           // KEY_UP
            APP_KEY_DOWN -> 3         // KEY_DOWN
            APP_KEY_LEFT -> 4         // KEY_LEFT
            APP_KEY_RIGHT -> 5        // KEY_RIGHT
            APP_KEY_BACK -> 9         // KEY_BACK
            APP_KEY_MENU -> 11        // KEY_MENU
            APP_KEY_INPUT -> 10       // KEY_INPUT
            else -> keyCode           // 未知按键原样透传（原生范围校验兜底）
        }
    }
}
