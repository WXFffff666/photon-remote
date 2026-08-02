package com.photon.remote.viewmodel

import com.photon.remote.codebase.IrdbCode
import com.photon.remote.ir.core.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IrdbHexConverter 单元测试（Todo 41 补全）。
 *
 * 覆盖 irdb CSV 十进制码（device/subdevice/function）→ 协议编码器 hex 输入
 * 的全部协议转换路径（计划 §3.2 表 + IrdbHexConverter 类注释）：
 * NEC 家族 8 hex、SAMSUNG 4 hex、SONY 3/4/5 hex、RC5/RC6、SHARP/JVC、
 * KASEIKYO 12 hex、PIONEER 8 hex，以及 UNKNOWN/非法输入 → null。
 */
class IrdbHexConverterTest {

    /** 构造 irdb 码记录（mappedProtocol 预映射，subdevice 缺省 -1） */
    private fun code(
        protocol: String,
        mapped: ProtocolType?,
        device: String,
        function: String,
        subdevice: String = "-1",
    ) = IrdbCode(protocol, protocol, device, subdevice, function, mapped)

    @Test
    fun nec家族_地址反码命令反码_8hex() {
        // 实测 irdb 行：device=7, function=153 → addr=07,~addr=F8,cmd=99,~cmd=66
        val hex = IrdbHexConverter.toHex(code("NECx2", ProtocolType.NECX2, "7", "153"))
        assertEquals("07F89966", hex)
    }

    @Test
    fun samsung32_自定义码加命令_4hex() {
        assertEquals("0799", IrdbHexConverter.toHex(code("Samsung36", ProtocolType.SAMSUNG32, "7", "153")))
    }

    @Test
    fun sony_命令与地址拼接_3_4_5hex() {
        // SONY12：cmd<<5 | device → 153&0x7F=25 <<5 = 0x320 | 0x07 = 0x327
        assertEquals("327", IrdbHexConverter.toHex(code("SONY", ProtocolType.SONY12, "7", "153")))
        // SONY15：cmd<<8 | device → 25<<8 = 0x1900 | 7 = 0x1907
        assertEquals("1907", IrdbHexConverter.toHex(code("Sony15", ProtocolType.SONY15, "7", "153")))
        // SONY20：cmd<<13 | device → 25<<13 = 0x32000 | 7 = 0x32007
        assertEquals("32007", IrdbHexConverter.toHex(code("Sony20", ProtocolType.SONY20, "7", "153")))
    }

    @Test
    fun rc5_地址命令按位域左移_4hex() {
        // AA = device<<3 → 7<<3 = 0x38；CC = function<<2 → 2<<2 = 0x08
        assertEquals("3808", IrdbHexConverter.toHex(code("RC5", ProtocolType.RC5, "7", "2")))
    }

    @Test
    fun rc6_sharp_jvc_地址高位命令低位_4hex() {
        assertEquals("0702", IrdbHexConverter.toHex(code("RC6", ProtocolType.RC6, "7", "2")))
        assertEquals("0702", IrdbHexConverter.toHex(code("Sharp{1}", ProtocolType.SHARP, "7", "2")))
        assertEquals("0702", IrdbHexConverter.toHex(code("JVC", ProtocolType.JVC, "7", "2")))
    }

    @Test
    fun kaseikyo_厂商加设备命令_12hex() {
        // 厂商 4004 + parity 占位 00 + device 07 + function 0002
        assertEquals(
            "400400070002",
            IrdbHexConverter.toHex(code("Panasonic", ProtocolType.KASEIKYO, "7", "2")),
        )
    }

    @Test
    fun pioneer_地址反码命令反码_8hex() {
        assertEquals("07F802FD", IrdbHexConverter.toHex(code("Pioneer", ProtocolType.PIONEER, "7", "2")))
    }

    @Test
    fun 未知协议_返回null() {
        assertNull(IrdbHexConverter.toHex(code("Zenith", null, "7", "2")))
    }

    @Test
    fun 非法数字_返回null() {
        assertNull(IrdbHexConverter.toHex(code("NECx2", ProtocolType.NECX2, "abc", "153")))
        assertNull(IrdbHexConverter.toHex(code("NECx2", ProtocolType.NECX2, "7", "xyz")))
    }

    @Test
    fun raw协议_irdb不产生_返回null() {
        assertNull(IrdbHexConverter.toHex(code("RAW", ProtocolType.RAW, "7", "2")))
    }
}
