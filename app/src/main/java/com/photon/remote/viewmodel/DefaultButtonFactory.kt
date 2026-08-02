package com.photon.remote.viewmodel

import com.photon.remote.codebase.IrdbCode
import com.photon.remote.codebase.IrdbCsvParser
import com.photon.remote.data.local.entity.Device
import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.data.model.ButtonAction
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.data.model.CodeSource
import com.photon.remote.data.model.DeviceType
import com.photon.remote.data.model.toJson
import com.photon.remote.ir.irext.IrextDecoder

/**
 * 默认按键集生成器（计划 §5.5 模板）。
 *
 * 供「恢复默认布局」（LayoutEditor onReset，Todo 32 装配）使用：
 * 与 AddDeviceViewModel 保存设备时的默认按键生成逻辑保持一致
 * （IREXT → IrextKey；IRDB → SendProtocol；AC 设备仅电源/静音）。
 */
object DefaultButtonFactory {

    /** 生成设备默认按键集 */
    fun buttonsFor(device: Device, irdbParser: IrdbCsvParser): List<RemoteButton> =
        if (device.codeSource == CodeSource.IREXT) {
            if (device.type == DeviceType.AC) {
                listOf(
                    irextButton(device.id, "POWER", "电源", 0, IrextDecoder.APP_KEY_POWER, device.codeRef),
                    irextButton(device.id, "MUTE", "静音", 1, IrextDecoder.APP_KEY_MUTE, device.codeRef),
                )
            } else {
                COMMON_KEY_ORDER.mapIndexed { index, (keyId, label, keyCode) ->
                    irextButton(device.id, keyId, label, index, keyCode, device.codeRef)
                }
            }
        } else {
            irdbButtons(device, irdbParser)
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
    private fun irdbButtons(device: Device, irdbParser: IrdbCsvParser): List<RemoteButton> {
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
                deviceId = device.id,
                keyId = keyId,
                label = if (keyId == "POWER") "电源" else code.functionName.trim(),
                actionJson = ButtonAction.SendProtocol(code.mappedProtocol!!, hex).toJson(),
                order = sequence.indexOf(keyId),
                shape = if (keyId == "POWER") ButtonShape.CIRCLE else ButtonShape.ROUNDED,
            )
        }
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
}

/** 标准键序：keyId / 显示名 / IREXT 应用层键码（与 AddDeviceViewModel 一致） */
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
