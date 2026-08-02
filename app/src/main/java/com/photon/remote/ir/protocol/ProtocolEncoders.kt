package com.photon.remote.ir.protocol

import com.photon.remote.ir.core.IrProtocolEncoder
import com.photon.remote.ir.core.ProtocolType

/**
 * 协议编码器注册表（计划 §3.2 / Todo 9）。
 *
 * 全部 14 种协议的编码器统一注册，供 CodeResolver（Todo 21）按 [ProtocolType] 查找。
 */
object ProtocolEncoders {

    /** 协议类型 → 编码器映射（14 种，含 NEC） */
    val all: Map<ProtocolType, IrProtocolEncoder> = mapOf(
        ProtocolType.NEC to NecEncoder,
        ProtocolType.NECX1 to Necx1Encoder,
        ProtocolType.NECX2 to Necx2Encoder,
        ProtocolType.RC5 to Rc5Encoder,
        ProtocolType.RC6 to Rc6Encoder,
        ProtocolType.SONY12 to Sony12Encoder,
        ProtocolType.SONY15 to Sony15Encoder,
        ProtocolType.SONY20 to Sony20Encoder,
        ProtocolType.SAMSUNG32 to Samsung32Encoder,
        ProtocolType.SHARP to SharpEncoder,
        ProtocolType.JVC to JvcEncoder,
        ProtocolType.KASEIKYO to KaseikyoEncoder,
        ProtocolType.PIONEER to PioneerEncoder,
        ProtocolType.RAW to RawEncoder,
    )
}
