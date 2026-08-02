package com.photon.remote.ir.transmitter

import com.photon.remote.ir.core.IRPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IR 发送调度器单元测试（计划 §3.4 / Todo 13 验收：串行顺序与原子性）。
 *
 * 注入自建 scope + 记录事件序列的 transmitFn，验证：
 *  1) 并发提交的多个 send 严格串行（任务不交错）
 *  2) onQueue 任务抛异常向上传播且不杀死队列（后续任务正常）
 *  3) onQueue 返回任务结果
 */
class IrDispatcherTest {

    private val pattern = IRPattern(38_000, intArrayOf(562, 562))

    private fun newDispatcher(events: MutableList<String>): Pair<IrDispatcher, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = IrDispatcher(
            transmitFn = { p ->
                events += "start"
                delay(30)   // 人为拉长任务，制造交错窗口
                events += "end"
                true
            },
            scope = scope
        )
        return dispatcher to scope
    }

    @Test
    fun `并发发送严格串行不交错`() = runBlocking {
        val events = mutableListOf<String>()
        val (dispatcher, scope) = newDispatcher(events)
        try {
            val r1 = async { dispatcher.send(pattern) }
            val r2 = async { dispatcher.send(pattern) }
            assertEquals(true, r1.await())
            assertEquals(true, r2.await())
            // 若串行被破坏，会出现 start,start,end,end 之类的交错序列
            assertEquals(listOf("start", "end", "start", "end"), events)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `onQueue 返回结果且异常向上传播后队列仍可用`() = runBlocking {
        val events = mutableListOf<String>()
        val (dispatcher, scope) = newDispatcher(events)
        try {
            // 正常任务：返回结果
            assertEquals("ok", dispatcher.onQueue { "ok" })

            // 抛异常的任务：异常传播给调用方
            val ex = try {
                dispatcher.onQueue<Unit> { throw IllegalStateException("boom") }
                null
            } catch (e: IllegalStateException) {
                e
            }
            assertTrue("异常应向上传播", ex != null)

            // 异常之后队列仍然可用（工作协程未死）
            assertEquals("still-alive", dispatcher.onQueue { "still-alive" })
            assertEquals(true, dispatcher.send(pattern))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `onQueue 任务之间不交错`() = runBlocking {
        val events = mutableListOf<String>()
        val (dispatcher, scope) = newDispatcher(events)
        try {
            // 第一个任务阻塞 30ms，期间提交的第二个任务必须等待
            val a = async { dispatcher.onQueue { events += "task-a"; delay(50); 1 } }
            val b = async { dispatcher.onQueue { events += "task-b"; 2 } }
            assertEquals(1, a.await())
            assertEquals(2, b.await())
            assertEquals(listOf("task-a", "task-b"), events)   // 原子性：b 绝不插入 a 中间
        } finally {
            scope.cancel()
        }
    }
}
