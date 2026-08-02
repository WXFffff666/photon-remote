package com.photon.remote.codebase.finder

import com.photon.remote.ir.core.IRPattern
import com.photon.remote.ir.core.ProtocolType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IrBruteForcer 单元测试（Todo 22）：
 *  - parsePrefix 规范化/非法输入；
 *  - candidateCount 组合数计算；
 *  - run 命中返回正确 hex + onTested 回调次数；
 *  - run 协程取消返回 null 且不抛异常。
 */
class IrBruteForcerTest {

    // ---------- parsePrefix ----------

    @Test
    fun `parsePrefix 普通大写hex 原样保留`() {
        assertEquals("AA", IrBruteForcer.parsePrefix("AA"))
    }

    @Test
    fun `parsePrefix 小写hex 转大写`() {
        assertEquals("AABB", IrBruteForcer.parsePrefix("aabb"))
    }

    @Test
    fun `parsePrefix 去掉 0x 前缀`() {
        assertEquals("AABB", IrBruteForcer.parsePrefix("0xAABB"))
        assertEquals("AABB", IrBruteForcer.parsePrefix("0Xaabb"))
    }

    @Test
    fun `parsePrefix 冒号与空格分隔符`() {
        assertEquals("AABB", IrBruteForcer.parsePrefix("AA:BB"))
        assertEquals("AABB", IrBruteForcer.parsePrefix("AA BB"))
    }

    @Test
    fun `parsePrefix 空串返回空前缀`() {
        assertEquals("", IrBruteForcer.parsePrefix(""))
        assertEquals("", IrBruteForcer.parsePrefix("  "))
    }

    @Test
    fun `parsePrefix 非法字符抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            IrBruteForcer.parsePrefix("XYZ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IrBruteForcer.parsePrefix("0xGG")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IrBruteForcer.parsePrefix("AA:ZZ")
        }
    }

    // ---------- candidateCount ----------

    @Test
    fun `candidateCount 无前缀 32位 NEC 全量 2^32`() {
        val config = BruteForceConfig(ProtocolType.NEC, prefixHex = "", bitWidth = 32)
        assertEquals(1L shl 32, IrBruteForcer.candidateCount(config))
    }

    @Test
    fun `candidateCount 8位前缀 32位协议 剩 24 位`() {
        val config = BruteForceConfig(ProtocolType.NEC, prefixHex = "AA", bitWidth = 32)
        assertEquals(1L shl 24, IrBruteForcer.candidateCount(config))
    }

    @Test
    fun `candidateCount SONY12 4位前缀 剩 8 位`() {
        val config = BruteForceConfig(ProtocolType.SONY12, prefixHex = "0xA", bitWidth = 12)
        assertEquals(1L shl 8, IrBruteForcer.candidateCount(config))
    }

    @Test
    fun `candidateCount 前缀位宽超协议位宽抛异常`() {
        val config = BruteForceConfig(ProtocolType.SONY12, prefixHex = "FFFF", bitWidth = 12)
        assertThrows(IllegalArgumentException::class.java) {
            IrBruteForcer.candidateCount(config)
        }
    }

    // ---------- run：命中 ----------

    @Test
    fun `run 第 4 个候选命中 返回正确 hex 且回调 4 次`() = runBlocking {
        // 前缀 AABBCC 占 24 位，剩余 8 位 → 候选值 = 0xAABBCC00 + i
        val config = BruteForceConfig(ProtocolType.NEC, prefixHex = "AABBCC", bitWidth = 32)
        var sent = 0
        var testedCount = 0L
        var lastTestedHex = ""

        val result = IrBruteForcer.run(
            config = config,
            transmit = { _: IRPattern ->
                sent++
                sent == 4   // 第 4 个候选命中
            },
            onTested = { hex, tested, _ ->
                testedCount = tested
                lastTestedHex = hex
            },
        )

        assertEquals("AABBCC03", result)   // 0xAABBCC00 + 3
        assertEquals(4, sent)
        assertEquals(4L, testedCount)
        assertEquals("AABBCC03", lastTestedHex)
    }

    @Test
    fun `run 无前缀 首个候选为全 0 hex 且立即命中`() = runBlocking {
        val config = BruteForceConfig(ProtocolType.NEC, prefixHex = "", bitWidth = 32)
        val tested = mutableListOf<String>()

        val result = IrBruteForcer.run(
            config = config,
            transmit = { _: IRPattern -> true },
            onTested = { hex, _, _ -> tested.add(hex) },
        )

        assertEquals("00000000", result)
        assertEquals(listOf("00000000"), tested)
    }

    @Test
    fun `run 从未命中 自然跑完返回 null 且回调总数正确`() = runBlocking {
        // 前缀 FFFFFF 占 24 位，剩余 8 位 → 256 个候选：0xFFFFFF00..0xFFFFFFFF
        val config = BruteForceConfig(ProtocolType.NEC, prefixHex = "FFFFFF", bitWidth = 32, intervalMs = 0L)
        var testedCount = 0L
        var lastHex = ""

        val result = IrBruteForcer.run(
            config = config,
            transmit = { _: IRPattern -> false },
            onTested = { hex, tested, total ->
                testedCount = tested
                lastHex = hex
                assertEquals(256L, total)   // 剩余 8 位 → 2^8 个候选
            },
        )

        assertNull(result)
        assertEquals(256L, testedCount)
        assertEquals("FFFFFFFF", lastHex)
    }

    // ---------- run：取消 ----------

    @Test
    fun `run 协程取消返回 null 且不抛异常`() = runBlocking {
        val config = BruteForceConfig(ProtocolType.NEC, prefixHex = "", bitWidth = 32, intervalMs = 1L)
        var result: String? = "未执行"
        var thrown: Throwable? = null

        val job = launch {
            try {
                result = IrBruteForcer.run(
                    config = config,
                    transmit = { _: IRPattern -> false },   // 永不命中 → 一直迭代
                    onTested = { _, _, _ -> },
                )
            } catch (t: Throwable) {
                thrown = t
            }
        }

        delay(50)
        job.cancel()
        job.join()

        assertNull(thrown)     // 不抛异常
        assertNull(result)     // 取消返回 null
        assertTrue(job.isCompleted)
    }
}
