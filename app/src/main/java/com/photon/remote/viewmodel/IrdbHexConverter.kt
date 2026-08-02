package com.photon.remote.viewmodel

import com.photon.remote.codebase.IrdbCode
import com.photon.remote.ir.core.ProtocolType

/**
 * irdb CSV 码（device / subdevice / function，十进制）→ 协议编码器 hex 输入 转换器。
 *
 * irdb CSV 格式（assets/irdb/<品牌>/<类型>/<型号>.csv，保持 irdb 原样）：
 * `functionname,protocol,device,subdevice,function`，device/subdevice/function 均为十进制。
 * 各协议编码器的 hex 输入布局见 plan §3.2 表与 ir/protocol 目录下各编码器，此处按
 * irdb 社区约定（probonopd/irdb README 的 LIRC 映射规则）组装：
 *   - NEC 家族（NEC/NECx1/NECx2）：addr,~addr,cmd,~cmd → 8 hex（device→addr、function→cmd；
 *     NECx2 双帧第二帧按 irdb 惯例与 device 相同或取 subdevice，本转换以 device 为准，
 *     两者不同时第二帧为近似值，见 Necx2Encoder 注释）
 *   - SAMSUNG32：custom(device),cmd(function) → 4 hex
 *   - SONY12：cmd<<5 | device（7 位命令 + 5 位地址）→ 3 hex
 *   - SONY15：cmd<<8 | device（7 位命令 + 8 位地址）→ 4 hex
 *   - SONY20：cmd<<13 | device（7 位命令 + 13 位地址）→ 5 hex
 *   - RC5：device<<3（AA 字节高位地址域）、function<<2（CC 字节高位命令域）→ 4 hex
 *   - RC6：device<<8 | function → 4 hex
 *   - SHARP：device<<8 | function（5 位地址 + 8 位命令）→ 4 hex
 *   - JVC：device<<8 | function → 4 hex
 *   - KASEIKYO（松下）：厂商 0x4004 + parity（编码器自算）+ device + function → 12 hex
 *   - PIONEER：addr,~addr,cmd,~cmd → 8 hex
 *   - RAW：无转换（irdb 不产生 RAW）
 *
 * 说明：这是"尽力而为"的映射（无真机码库对照的协议按上述社区规则推导），
 * 个别品牌码组可能不匹配，测试页即为此设计的验证环节；映射不了（UNKNOWN 协议）
 * 返回 null，调用方跳过该按键。
 */
object IrdbHexConverter {

    /** 松下厂商码（KASEIKYO 帧首 16 位；irdb 不携带，按社区常见值 0x4004） */
    private const val PANASONIC_VENDOR = 0x4004

    /**
     * 由 irdb 码记录生成编码器 hex 输入；协议映射不了时返回 null。
     *
     * @param code irdb 码记录（mappedProtocol 已由 IrdbCsvParser 预解析）
     */
    fun toHex(code: IrdbCode): String? {
        val protocol = code.mappedProtocol ?: return null
        val device = code.device.toIntOrNull() ?: return null
        val subdevice = code.subdevice.toIntOrNull() ?: -1
        val function = code.function.toIntOrNull() ?: return null
        return when (protocol) {
            ProtocolType.NEC, ProtocolType.NECX1, ProtocolType.NECX2 -> {
                // addr,~addr,cmd,~cmd（NECx2 第二帧以 device 近似，见类注释）
                val d = device and 0xFF
                val c = function and 0xFF
                "%02X%02X%02X%02X".format(d, d.inv() and 0xFF, c, c.inv() and 0xFF)
            }
            ProtocolType.SAMSUNG32 -> "%02X%02X".format(device and 0xFF, function and 0xFF)
            ProtocolType.SONY12 -> "%03X".format(((function and 0x7F) shl 5) or (device and 0x1F))
            ProtocolType.SONY15 -> "%04X".format(((function and 0x7F) shl 8) or (device and 0xFF))
            ProtocolType.SONY20 -> "%05X".format(((function and 0x7F) shl 13) or (device and 0x1FFF))
            ProtocolType.RC5 -> {
                // AA=地址位域（bit7..3），CC=命令位域（bit7..2）
                "%02X%02X".format((device and 0x1F) shl 3, (function and 0x3F) shl 2)
            }
            ProtocolType.RC6 -> "%04X".format(((device and 0xFF) shl 8) or (function and 0xFF))
            ProtocolType.SHARP -> "%04X".format(((device and 0x1F) shl 8) or (function and 0xFF))
            ProtocolType.JVC -> "%04X".format(((device and 0xFF) shl 8) or (function and 0xFF))
            ProtocolType.KASEIKYO -> {
                // 厂商 16bit + parity 8bit（占位 00，编码器自算）+ 设备 8bit + 命令 16bit
                "%04X00%02X%04X".format(PANASONIC_VENDOR, device and 0xFF, function and 0xFFFF)
            }
            ProtocolType.PIONEER -> {
                val d = device and 0xFF
                val c = function and 0xFF
                "%02X%02X%02X%02X".format(d, d.inv() and 0xFF, c, c.inv() and 0xFF)
            }
            ProtocolType.RAW -> null   // irdb 不产生 RAW 码
        }
    }
}
