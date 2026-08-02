package com.photon.remote.di

import android.content.Context
import android.os.Vibrator
import com.photon.remote.codebase.CodeResolver
import com.photon.remote.codebase.IrdbCsvParser
import com.photon.remote.codebase.IrextBinaryStore
import com.photon.remote.codebase.IrextIndexLoader
import com.photon.remote.data.local.AppDatabase
import com.photon.remote.data.local.SettingsStore
import com.photon.remote.data.local.settingsStore
import com.photon.remote.data.model.ACStatusData
import com.photon.remote.data.repository.DeviceRepository
import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.ProtocolType
import com.photon.remote.ir.irext.IrextDecoder
import com.photon.remote.ir.protocol.ProtocolEncoders
import com.photon.remote.ir.transmitter.AudioIrTransmitter
import com.photon.remote.ir.transmitter.ConsumerIrTransmitter
import com.photon.remote.ir.transmitter.IrDispatcher
import com.photon.remote.ir.transmitter.TransmitterManager
import com.photon.remote.ir.transmitter.UsbIrTransmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import net.irext.decode.sdk.bean.ACStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * 手动 DI 容器（计划 §1 / D5：单模块 + 手动 DI，无 Hilt）。
 *
 * 组装码库层全部依赖：索引加载器 → 二进制码库 → CSV 解析器 → CodeResolver，
 * 以及应用级 AC 状态缓存（[ACStatusCache]，供 CodeResolver.currentAcStatus 回调）。
 *
 * UI 阶段（Todo 26-31）追加：数据层（Room 数据库 + 设备仓储）、红外发射层
 * （内置/USB/音频发射器 + 路径路由 + 串行调度器）、协议编码器表（长按重复间隔
 * 查询用）。页面 ViewModel 通过 PhotonApplication.container 访问本容器。
 */
class AppContainer(context: Context) {

    /** 应用级协程作用域（AC 状态回写等后台任务用，App 生命周期跟随） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 应用 Context（单例，避免 Activity 泄漏） */
    private val appContext = context.applicationContext

    /** DataStore 设置存储（AC 状态持久化落点） */
    val settingsStore: SettingsStore = SettingsStore(context.settingsStore)

    /** IREXT 索引（assets JSON → 内存缓存 + 五级查询） */
    val indexLoader: IrextIndexLoader = IrextIndexLoader(context)

    /** IREXT 二进制码库（zip 按需解压 + LRU 缓存 + 元数据包装） */
    val binaryStore: IrextBinaryStore = IrextBinaryStore(context, indexLoader)

    /** irdb CSV 码库解析器 */
    val irdbParser: IrdbCsvParser = IrdbCsvParser(context)

    /** 应用级 AC 状态内存缓存（启动自 SettingsStore 水合、变更回写） */
    val acStatusCache: ACStatusCache = ACStatusCache(settingsStore, scope)

    /**
     * 码解析统一入口。
     *
     * currentAcStatus 回调：ACStatusCache（应用层 ACStatusData）→ irext ACStatus
     * bean（应用层语义，IrextDecoder.decode 内部再转原生语义）；无记录返回 null
     * （CodeResolver 用默认状态兜底）。
     */
    val codeResolver: CodeResolver = CodeResolver(
        irextStore = binaryStore,
        irextDecoder = IrextDecoder,
        irdbParser = irdbParser,
        encoders = ProtocolEncoders.all,
        currentAcStatus = { deviceId -> acStatusCache.get(deviceId)?.toIrextBean() },
    )

    // ---------- 数据层（Todo 26-31 UI 阶段追加） ----------

    /** Room 数据库（devices / remote_buttons / macros 三表） */
    val database: AppDatabase = AppDatabase.getInstance(appContext)

    /** 设备仓储（设备 + 按键 + 宏 统一入口） */
    val repository: DeviceRepository = DeviceRepository(
        database.deviceDao(), database.buttonDao(), database.macroDao(),
    )

    // ---------- 红外发射层（Todo 26-31 UI 阶段追加） ----------

    /** 系统震动服务（内置红外发送反馈用） */
    val vibrator: Vibrator =
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    /** 内置红外发射器（ConsumerIrManager） */
    val consumerIr: ConsumerIrTransmitter = ConsumerIrTransmitter(appContext, vibrator)

    /** USB 红外外设发射器 */
    val usbIr: UsbIrTransmitter = UsbIrTransmitter(appContext)

    /** 音频转红外发射器（无红外手机降级路径） */
    val audioIr: AudioIrTransmitter = AudioIrTransmitter()

    /** 发射路径路由（auto：USB→内置→音频，可手动指定） */
    val transmitterManager: TransmitterManager =
        TransmitterManager(consumerIr, usbIr, audioIr, settingsStore)

    /** IR 发送调度器（单线程串行队列，所有发送/解码必须经它） */
    val irDispatcher: IrDispatcher = IrDispatcher(transmitterManager::transmit)

    /** 协议编码器表（RemoteKey 长按连发间隔查询：encoder.repeatIntervalMs ?: 250） */
    val encoders: Map<ProtocolType, IrProtocolEncoder> = ProtocolEncoders.all
}

/**
 * 应用级 AC 状态内存缓存（计划 §1 di/AppContainer 内含，Todo 18-21 所需）。
 *
 *  - 读：[get] 非 suspend（CodeResolver.currentAcStatus 回调为同步函数）；
 *  - 写：[set] 同步写内存 + 协程回写 SettingsStore（DataStore 为 suspend API）；
 *  - 水合：[hydrate] 启动/进入空调面板时按 deviceId 从 SettingsStore 拉取历史状态
 *    填入缓存（惰性按设备水合，避免启动时枚举全部设备）。
 */
class ACStatusCache(
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    /** deviceId → ACStatusData（应用层语义，6 个 Int 原语） */
    private val cache = ConcurrentHashMap<Long, ACStatusData>()

    /** 非 suspend 读取（回调路径用）；无缓存记录返回 null */
    fun get(deviceId: Long): ACStatusData? = cache[deviceId]

    /** 同步写内存 + 异步回写 SettingsStore（覆盖式） */
    fun set(deviceId: Long, status: ACStatusData) {
        cache[deviceId] = status
        scope.launch { settingsStore.setAcStatus(deviceId, status) }
    }

    /** 从 SettingsStore 水合指定设备的历史状态（无历史则跳过，不写入） */
    suspend fun hydrate(deviceId: Long) {
        if (cache.containsKey(deviceId)) return
        settingsStore.acStatus(deviceId).firstOrNull()?.let { cache[deviceId] = it }
    }
}

/**
 * 应用层 ACStatusData → irext ACStatus bean（应用层语义，字段一一对应；
 * 原生语义转换由 IrextDecoder.decode 内部的 ACStatusHelper.toNativeAcStatus 完成）。
 */
private fun ACStatusData.toIrextBean(): ACStatus = ACStatus(
    acPower, acMode, acTemp, acWindSpeed, acWindDir,
    0, 0, 0, changeWindDir,
)
