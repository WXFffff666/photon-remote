package com.photon.remote.ir.transmitter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.photon.remote.ir.core.IRPattern
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB 红外发射器（计划 §3.4）。
 *
 * 设备过滤：VID 0x10C4（Silicon Labs，Tiqiaa 类 dongle 常见桥接芯片）/ 0x045E（Microsoft），
 * 两者共用 PID 0x8468。
 *
 * 流程：UsbManager 扫描 → 请求权限（mutable PendingIntent，API 31+ 必须 FLAG_MUTABLE）→
 * claimInterface(force=true) 取 bulk IN/OUT endpoint → 发送 RLE 压缩帧（56 字节分片 bulk OUT）。
 *
 * 【接收端协议声明】握手 + RLE 帧格式以实测为准；计划内置 2 种已知格式（Tiqiaa 类 + 通用类），
 * 实现参考 android-ir-blaster README 公开的 "RLE payload / 56-byte fragments / tail adjustment"
 * 描述自研（未复制其代码，D3）。本轮实现 RLE 帧构造 + 分片 + 基础握手结构；
 * 具体 dongle 字节序 / 字段留 TODO（无硬件验收：编译 + 纯函数单测，见 UsbIrRleTest）。
 *
 * isAvailable = 已连接且已授权（usbManager.hasPermission）；未插入 / 未授权 / 无硬件时自然为 false。
 */
class UsbIrTransmitter(private val context: Context) : IRTransmitter {

    override val displayName: String = "USB 发射器"

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** 已连接且已授权 = 可用；卸载 / 未授权时 false */
    override val isAvailable: Boolean
        get() = findDevice()?.let { usbManager.hasPermission(it) } == true

    // ---------- 资源释放：Receiver 解绑幂等标志 ----------
    private val isClosed = AtomicBoolean(false)

    // ---------- 动态广播 ----------

    /** 权限结果接收器：授权状态由 usbManager.hasPermission 实时读取，无需缓存字段 */
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != PERMISSION_ACTION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = intent.usbDeviceExtra()
            // 授权结果已写入 UsbManager 状态；isAvailable / transmit 下次调用自动生效。
            // 用户拒绝授权（granted=false）时保持不可用，由 UI 层提示"需要 USB 权限"。
            if (!granted && device != null) { /* 无状态可更新，注释留档 */ }
        }
    }

    /** 设备插入广播：卸载重插后重新枚举并自动请求权限 */
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
            val device = intent.usbDeviceExtra()
            if (device != null && isTargetDevice(device) && !usbManager.hasPermission(device)) {
                requestPermission(device)
            }
        }
    }

    init {
        // 系统广播（设备插入）用 RECEIVER_EXPORTED；自定义权限结果广播用 RECEIVER_NOT_EXPORTED
        ContextCompat.registerReceiver(
            context, attachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            context, permissionReceiver,
            IntentFilter(PERMISSION_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * 释放 BroadcastReceiver 资源，幂等可重复调用。
     *
     * 必须与 init 中的两次 registerReceiver 配对，否则导致内存泄漏与重复回调。
     * 已使用 try/catch 吞并 IllegalArgumentException（未注册或重复解绑时系统抛出），
     * 供 AppContainer/Application 销毁时调用。
     */
    fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        try {
            context.unregisterReceiver(attachReceiver)
        } catch (_: IllegalArgumentException) {
            // 已解绑或未注册，忽略
        }
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (_: IllegalArgumentException) {
            // 已解绑或未注册，忽略
        }
    }

    /** [close] 的别名，供调用方按语义选择 unregister/close */
    fun unregister() = close()

    /** 请求 USB 设备使用权限（结果经 [permissionReceiver] 回调） */
    fun requestPermission(device: UsbDevice) {
        val intent = Intent(PERMISSION_ACTION).setPackage(context.packageName)
        // API 31+ 必须显式 FLAG_MUTABLE（系统需要填充 EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED）
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    /** 扫描当前连接的匹配设备（VID/PID 过滤） */
    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { isTargetDevice(it) }

    /**
     * 同步发送：打开设备 → claimInterface → 基础握手 → RLE 帧分片 bulk OUT。
     * 阻塞直到全部写入完成（由 IrDispatcher 在后台队列中调用）。
     */
    override fun transmit(pattern: IRPattern): Boolean {
        if (!isAvailable) return false
        val device = findDevice() ?: return false
        val connection = usbManager.openDevice(device) ?: return false
        var claimedIface: UsbInterface? = null
        return try {
            val iface = device.getInterface(0) ?: return false
            if (!connection.claimInterface(iface, true)) return false
            claimedIface = iface
            val outEp = bulkEndpoint(iface, UsbConstants.USB_DIR_OUT) ?: return false
            val inEp = bulkEndpoint(iface, UsbConstants.USB_DIR_IN)
            // 基础握手：本轮不因握手失败中断发送（接收端协议以实测为准，TODO：实测后决定是否强制握手成功）
            performHandshake(connection, inEp, outEp)
            sendFrame(connection, outEp, buildRleFrame(pattern.frequency, pattern.intervals))
        } finally {
            claimedIface?.let {
                try { connection.releaseInterface(it) } catch (_: Exception) { /* ignore */ }
            }
            try { connection.close() } catch (_: Exception) { /* ignore */ }
        }
    }

    /** 基础握手：写问候包（魔数 + 版本）；有 bulk IN（Tiqiaa 类学习型 dongle）则尽力读回应答 */
    private fun performHandshake(
        connection: UsbDeviceConnection, inEp: UsbEndpoint?, outEp: UsbEndpoint
    ): Boolean {
        val hello = byteArrayOf(0x50, 0x49, 0x01)   // 魔数 'PI' + 版本（TODO 实测校准字节序 / 内容）
        if (connection.bulkTransfer(outEp, hello, hello.size, HANDSHAKE_TIMEOUT_MS) < 0) return false
        if (inEp == null) return true               // 无 bulk IN（纯发射 dongle）：握手仅写入
        val reply = ByteArray(8)
        return connection.bulkTransfer(inEp, reply, reply.size, HANDSHAKE_TIMEOUT_MS) >= 0
    }

    /** RLE 帧 56 字节分片发送；任一分片失败即中止并返回 false */
    private fun sendFrame(connection: UsbDeviceConnection, outEp: UsbEndpoint, frame: ByteArray): Boolean {
        for (chunk in fragmentFrame(frame)) {
            if (connection.bulkTransfer(outEp, chunk, chunk.size, WRITE_TIMEOUT_MS) < 0) return false
        }
        return true
    }

    private fun bulkEndpoint(iface: UsbInterface, direction: Int): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == direction) return ep
        }
        return null
    }

    /** API 33+ 使用带类型的 getParcelableExtra；旧版本走废弃重载 */
    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        /**
         * 设备过滤 VID/PID —— 业务常量非密钥/非凭据，用于 USB 设备识别。
         * VID 0x10C4 Silicon Labs / 0x045E Microsoft，PID 0x8468 为红外 dongle 共有。
         */
        const val VID_SILICON_LABS = 0x10C4 // business constant non-secret: USB vendor ID
        const val VID_MICROSOFT = 0x045E // business constant non-secret: USB vendor ID
        const val PID_IR_DONGLE = 0x8468 // business constant non-secret: USB product ID

        /** 每分片字节数（参考 android-ir-blaster README 公开描述；TODO 实测校准） */
        const val FRAGMENT_SIZE = 56

        private const val PERMISSION_ACTION = "com.photon.remote.USB_PERMISSION"
        private const val HANDSHAKE_TIMEOUT_MS = 300
        private const val WRITE_TIMEOUT_MS = 500

        // ---------- RLE 帧格式（自研，接收端协议以实测为准，见类注释） ----------
        // 字节 0-1: 魔数 0x50 0x49（'PI'）
        // 字节 2  : 帧版本 0x01
        // 字节 3-4: 载波频率 Hz（小端 16 位）
        // 字节 5..: RLE payload：每项 4 字节 [时长 µs 小端 3 字节][连续计数 1 字节]（相邻相同时长合并）
        // 末字节  : 校验和（前序全部字节异或；TODO 实测校准是否需要）
        private val MAGIC = byteArrayOf(0x50, 0x49)
        private const val VERSION = 0x01
        private const val ENTRY_SIZE = 4

        /** VID/PID 过滤（两个已知 VID 共用 PID 0x8468） */
        fun isTargetDevice(device: UsbDevice): Boolean =
            (device.vendorId == VID_SILICON_LABS && device.productId == PID_IR_DONGLE) ||
                (device.vendorId == VID_MICROSOFT && device.productId == PID_IR_DONGLE)

        /**
         * 构造 RLE 帧：mark/space 序列 → 相邻相同时长合并计数 → 分帧。
         * 纯函数（不触碰 Android 对象），JVM 单测覆盖（UsbIrRleTest）。
         *
         * @param frequency 载波频率 Hz（0..65535）
         * @param intervals mark/space 微秒序列（以 mark 开头、space 结尾，偶数长度）
         */
        fun buildRleFrame(frequency: Int, intervals: IntArray): ByteArray {
            require(intervals.isNotEmpty()) { "波形为空：intervals 不能为空" }
            require(frequency in 0..0xFFFF) { "频率超出 16 位范围: $frequency" }

            val out = ByteArrayOutputStream()
            out.write(MAGIC[0].toInt()); out.write(MAGIC[1].toInt()); out.write(VERSION)
            out.write(frequency and 0xFF); out.write((frequency shr 8) and 0xFF)

            // RLE：相邻相同时长合并为一项（时长 3 字节小端 + 计数 1 字节；计数超 255 拆多段）
            var prev = -1L
            var count = 0
            fun flushRun() {
                if (count <= 0) return
                var remaining = count
                while (remaining > 0) {
                    val c = minOf(remaining, 255)
                    out.write((prev and 0xFF).toInt())
                    out.write(((prev shr 8) and 0xFF).toInt())
                    out.write(((prev shr 16) and 0xFF).toInt())
                    out.write(c)
                    remaining -= c
                }
            }
            for (v in intervals) {
                val lv = v.toLong()
                if (lv == prev) count++ else { flushRun(); prev = lv; count = 1 }
            }
            flushRun()

            val body = out.toByteArray()
            // 校验和：前序全部字节异或（TODO 实测校准：接收端是否要求校验）
            var checksum = 0
            for (b in body) checksum = checksum xor (b.toInt() and 0xFF)
            return body.copyOf(body.size + 1).also { it[body.size] = checksum.toByte() }
        }

        /** 解析 RLE 帧回 (频率, mark/space 序列) —— 供单测往返校验与调试（接收模式预留） */
        fun decodeRleFrame(frame: ByteArray): Pair<Int, IntArray> {
            require(frame.size >= 5) { "帧过短: ${frame.size} 字节" }
            val freq = (frame[3].toInt() and 0xFF) or ((frame[4].toInt() and 0xFF) shl 8)
            val intervals = mutableListOf<Int>()
            var i = 5
            val bodyEnd = frame.size - 1   // 末字节为校验和
            while (i + 3 < bodyEnd) {
                val dur = (frame[i].toInt() and 0xFF) or
                    ((frame[i + 1].toInt() and 0xFF) shl 8) or
                    ((frame[i + 2].toInt() and 0xFF) shl 16)
                val count = frame[i + 3].toInt() and 0xFF
                repeat(count) { intervals += dur }
                i += ENTRY_SIZE
            }
            return freq to intervals.toIntArray()
        }

        /** 56 字节分片（参考 android-ir-blaster 公开描述）；末片补 0（tail adjustment 以实测为准，TODO） */
        fun fragmentFrame(frame: ByteArray, fragmentSize: Int = FRAGMENT_SIZE): List<ByteArray> {
            require(fragmentSize > 0) { "分片大小必须为正: $fragmentSize" }
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < frame.size) {
                val len = minOf(fragmentSize, frame.size - offset)
                val chunk = frame.copyOfRange(offset, offset + len)
                chunks += if (len < fragmentSize) chunk.copyOf(fragmentSize) else chunk
                offset += len
            }
            return chunks
        }
    }
}
