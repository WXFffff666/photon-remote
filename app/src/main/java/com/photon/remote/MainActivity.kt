// com.photon.remote.MainActivity.kt —— 第一步：检测红外 + 发送测试 NEC 码（计划 §6.6）
package com.photon.remote

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photon.remote.ir.protocol.NecEncoder
import com.photon.remote.ui.navigation.PhotonNavHost
import com.photon.remote.ui.theme.PhotonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotonTheme {
                // 自适应导航骨架（NavigationSuiteScaffold + NavHost 空路由，Home 当前为第一步测试屏）
                PhotonNavHost()
            }
        }
    }
}

/**
 * 第一步测试屏（Home 路由的占位内容，计划 §6.6）：
 * 1) 检测内置红外发射器（ConsumerIrManager，null = 无红外）
 * 2) 发送 NEC 测试码 0x00FF12ED（接口版编码器，返回 IRPattern）
 * Todo 26 实现设备列表页后，本屏内容由 ui/home/HomeScreen 替换。
 */
@Composable
fun IrTestScreen() {
    val context = LocalContext.current

    // 1) 检测红外发射器
    val manager = remember {
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    }
    val hasIr = manager?.hasIrEmitter() == true

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (hasIr) stringResource(R.string.ir_has_emitter)
                else stringResource(R.string.ir_no_emitter),
                style = MaterialTheme.typography.headlineSmall
            )
            // SDK 36 起 ConsumerIrManager 移除 getCarrierFrequencyRange()（int[]），
            // 改为 getCarrierFrequencies() 返回 CarrierFrequencyRange[]（含 minFrequency/maxFrequency）。
            // 载波范围仅为展示信息：异常（极旧系统缺方法）时跳过该行，不影响发送链路。
            manager?.let { m ->
                rememberCarrierRange(m)?.let { (minF, maxF) ->
                    Text(
                        stringResource(R.string.ir_carrier_range, minF, maxF),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 2) 测试 NEC 码：0x00FF12ED（常用测试码，部分电视/机顶盒响应）
            Button(
                enabled = hasIr,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                onClick = {
                    // 接口版编码器：encode 返回 IRPattern（载波频率 + mark/space 间隔序列）
                    val pattern = NecEncoder.encode("00FF12ED")
                    // ConsumerIrManager.transmit 返回 void：用 try/catch 判定发送结果（与 §3.1 同款写法）
                    val ok = try {
                        manager!!.transmit(pattern.frequency, pattern.intervals)
                        true
                    } catch (e: Exception) { false }
                    if (ok) vibrate(context)
                    Toast.makeText(
                        context,
                        if (ok) context.getString(R.string.send_ok) else context.getString(R.string.send_fail),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            ) { Text(stringResource(R.string.send_nec_test)) }

            Text(
                stringResource(R.string.send_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun vibrate(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= 26) {
        vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(30)
    }
}

/**
 * 读取红外发射器的第一个载波频率范围（展示用，异常安全）。
 *
 * SDK 36 起 getCarrierFrequencyRange()（int[]）被 getCarrierFrequencies()（CarrierFrequencyRange[]）取代；
 * 此处用新 API，任何异常（极旧系统缺少该方法）返回 null，仅不展示频率行，不影响发送链路。
 */
@Composable
private fun rememberCarrierRange(manager: ConsumerIrManager): Pair<Int, Int>? =
    remember(manager) {
        try {
            manager.carrierFrequencies.firstOrNull()?.let { it.minFrequency to it.maxFrequency }
        } catch (_: Throwable) {
            null
        }
    }
