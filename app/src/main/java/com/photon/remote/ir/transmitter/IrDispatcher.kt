package com.photon.remote.ir.transmitter

import com.photon.remote.ir.core.IRPattern
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch

/**
 * IR 发送调度器（计划 §3.4，新增核心组件）——所有发送必须经此队列串行执行。
 *
 * 单线程队列（Channel + 常驻消费协程）：每个提交的任务在队列工作协程中独占执行，
 * 期间不插入其他任务（原子性）。
 *
 * 用途：
 *  - [send]：发送 IRPattern（内部调用注入的 [transmitFn]，生产环境 = TransmitterManager::transmit）
 *  - [onQueue]：通用原子任务入口，供 CodeResolver 的 open→decode→close→restore 整段使用
 *    （防止页面按键在会话交换窗口解码错误 binary 或收到 null），
 *    也供 IrextDecoder 的 open/decode/close 串行化（JNI 单例共享原生状态，并发会互相污染）。
 *
 * 原因：ConsumerIrManager.transmit 会阻塞至整帧发完（NEC 补零后最长 108.8ms），
 * 绝不能在主线程调用；且宏 / 暴力找码 / 长按连发可能并发，必须串行化。
 */
class IrDispatcher(
    /** 实际发射函数（生产环境注入 TransmitterManager::transmit，由 AppContainer 组装） */
    private val transmitFn: suspend (IRPattern) -> Boolean,
    /** 队列工作协程作用域（默认后台线程池；测试可注入自定义 scope） */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        // 常驻消费协程：逐个执行队列任务，天然串行；任务异常已在 onQueue 内吞掉，不会杀死工作协程
        scope.launch {
            for (task in queue) task()
        }
    }

    /** 发送：作为单个原子队列任务，经 [transmitFn] 串行执行；返回发送结果供 UI 显示"已发送/失败" */
    suspend fun send(pattern: IRPattern): Boolean = onQueue { transmitFn(pattern) }

    /**
     * 通用原子队列入口：block 在队列工作协程中独占执行，期间不插入其他任务。
     *
     * block 抛出的异常会原样传播给调用方（via CompletableDeferred），
     * 且不影响队列后续任务（工作协程不会因此死亡）。
     */
    suspend fun <T> onQueue(block: suspend () -> T): T {
        val deferred = CompletableDeferred<T>()
        queue.send { deferred.completeWith(runCatching { block() }) }
        return deferred.await()
    }
}
