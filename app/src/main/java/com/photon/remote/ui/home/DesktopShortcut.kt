package com.photon.remote.ui.home

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.photon.remote.MainActivity
import com.photon.remote.R
import com.photon.remote.data.local.entity.Device

/**
 * 桌面快捷方式（计划 §5.3 / Todo 39）：ShortcutManagerCompat，兼容 minSdk 24。
 *
 * 长按设备卡片「添加到桌面」→ 桌面出现直达该设备遥控器/空调页的图标；
 * 快捷方式 intent = MainActivity + extra deviceId（FLAG_ACTIVITY_NEW_TASK|CLEAR_TOP），
 * MainActivity 据此直接导航到对应页面。
 */
fun requestPinDeviceShortcut(context: Context, device: Device) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, "当前桌面不支持添加快捷方式", Toast.LENGTH_SHORT).show()
        return
    }
    val shortcutIntent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .putExtra(MainActivity.EXTRA_DEVICE_ID, device.id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    // 快捷方式 id：device_<id>（同一设备重复添加会覆盖原快捷方式）
    val shortcut = ShortcutInfoCompat.Builder(context, "device_${device.id}")
        .setShortLabel(device.name)
        .setLongLabel(device.name)
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(shortcutIntent)
        .build()

    if (ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) {
        Toast.makeText(context, "已添加「${device.name}」到桌面", Toast.LENGTH_SHORT).show()
    }
}
