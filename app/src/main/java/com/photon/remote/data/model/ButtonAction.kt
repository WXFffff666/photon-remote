package com.photon.remote.data.model

import com.photon.remote.data.local.entity.RemoteButton
import com.photon.remote.ir.core.ProtocolType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 按钮动作模型（计划 §2.3）。
 *
 * 以 sealed interface + kotlinx.serialization 序列化为 JSON 字符串，
 * 存于 RemoteButton.actionJson 列。
 */
@Serializable
sealed interface ButtonAction {

    /** 原始波形直接发送：frequency 载波频率（Hz），intervals 交替 mark/space 微秒列表 */
    @Serializable
    @SerialName("SendRaw")
    data class SendRaw(val frequency: Int, val intervals: List<Int>) : ButtonAction

    /** 协议编码发送：protocol 协议类型（按枚举名序列化），hex 十六进制码串 */
    @Serializable
    @SerialName("SendProtocol")
    data class SendProtocol(
        @Serializable(with = ProtocolTypeSerializer::class)
        val protocol: ProtocolType,
        val hex: String,
    ) : ButtonAction

    /**
     * IREXT 码库按键：keyCode 语义键（0=电源 1..9=数字0..9 10=CH+ 11=CH- 12=VOL+ 13=VOL- 14=MUTE
     * 15=OK 16=UP 17=DOWN 18=LEFT 19=RIGHT 20=BACK 21=MENU 22=INPUT，以 irext 官方常量表为准）。
     *
     * 注意：IREXT 按钮的二进制引用以 Device.codeRef 为准；binaryRef 保留用于兼容导入数据
     * （Flipper/LIRC 转 IREXT 键），CodeResolver 一律读 device.codeRef。
     */
    @Serializable
    @SerialName("IrextKey")
    data class IrextKey(val keyCode: Int, val binaryRef: String) : ButtonAction
}

/**
 * ProtocolType 序列化器：按枚举名序列化。
 *
 * ir/core 中的 ProtocolType 是骨架既有枚举（不可改动），故在此以自定义序列化器完成
 * JSON 与枚举名的互转；遇到未知名称抛中文异常。
 */
object ProtocolTypeSerializer : KSerializer<ProtocolType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.photon.remote.ir.core.ProtocolType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ProtocolType) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ProtocolType {
        val name = decoder.decodeString()
        return try {
            ProtocolType.valueOf(name)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("未知协议类型：$name", e)
        }
    }
}

/**
 * ButtonAction 专用 JSON 实例：忽略未知字段、编码默认值，保证新旧版本数据兼容。
 *
 * 显式注册三个多态子类（sealed interface 的隐式子类注册在跨编译单元场景下不可靠，
 * 显式 SerializersModule 保证任何调用方反序列化均能解析子类）。
 */
internal val ButtonActionJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    serializersModule = SerializersModule {
        polymorphic(ButtonAction::class) {
            subclass(ButtonAction.SendRaw::class)
            subclass(ButtonAction.SendProtocol::class)
            subclass(ButtonAction.IrextKey::class)
        }
    }
}

/**
 * 将按钮动作序列化为 JSON 字符串（供 RemoteButton.actionJson 持久化）。
 */
fun ButtonAction.toJson(): String = ButtonActionJson.encodeToString(serializer(), this)

/**
 * 反序列化按钮动作（解析 RemoteButton.actionJson）。
 *
 * 非法 JSON 抛出带中文信息的 IllegalArgumentException（由调用方决定兜底策略，
 * 如 UI 层提示"该按键数据损坏"）。
 */
fun RemoteButton.action(): ButtonAction = try {
    // 显式传入序列化器（与 toJson 对称）：sealed interface 的 reified 解析在主模块内联展开
    // 不可靠（子类注册缺失），显式调用保证多态子类始终可解析
    ButtonActionJson.decodeFromString(ButtonAction.serializer(), actionJson)
} catch (e: Exception) {
    throw IllegalArgumentException("按钮动作 JSON 解析失败（deviceId=$deviceId, keyId=$keyId）：${e.message}", e)
}
