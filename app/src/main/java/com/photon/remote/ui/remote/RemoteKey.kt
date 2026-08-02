package com.photon.remote.ui.remote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.photon.remote.data.model.ButtonShape
import com.photon.remote.ir.core.PressKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 遥控器按键组件（计划 §5.5 / Todo 29）。
 *
 * 交互：按压立即发码（NEW_PRESS）+ 轻震动 + 缩放 0.92 动画 + 阴影收缩；
 * 按住超过 [LONG_PRESS_THRESHOLD_MS] 进入长按连发，按 [repeatIntervalMs] 节奏
 * 发送 REPEAT（NEC 家族 / JVC 由协议覆盖为 110ms，其余 250ms，见 §3.2 表；
 * 调用方经 encoder.repeatIntervalMs 传入）。
 *
 * 发送经 rememberCoroutineScope().launch 触发（Compose onClick 非 suspend），
 * 实际发射由 ViewModel → IrDispatcher 后台队列串行执行。
 *
 * @param onSend 单击/连发回调（参数为按压语义：NEW_PRESS 首次、REPEAT 长按重复）
 */
@Composable
fun RemoteKey(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    width: Dp = Dp.Unspecified,    // 自定义布局：键宽（与 size 二选一，指定后按此渲染）
    height: Dp = Dp.Unspecified,   // 自定义布局：键高（与 size 二选一，指定后按此渲染）
    shape: ButtonShape = ButtonShape.ROUNDED,
    icon: ImageVector? = null,
    label: String? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
    sendFailed: Boolean = false,
    enabled: Boolean = true,
    repeatIntervalMs: Int = DEFAULT_REPEAT_MS,
    onSend: (PressKind) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        label = "keyScale",
    )
    val haptic = LocalHapticFeedback.current
    // 连发循环作用域（AwaitPointerEventScope 非 CoroutineScope，需外部 scope）
    val scope = rememberCoroutineScope()

    // 背景色：失败红色提示（errorContainer）/ 自定义 / 默认抬升面
    val bg = when {
        sendFailed -> MaterialTheme.colorScheme.errorContainer
        containerColor != null -> containerColor
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        sendFailed -> MaterialTheme.colorScheme.onErrorContainer
        contentColor != null -> contentColor
        else -> MaterialTheme.colorScheme.onSurface
    }

    // 按压手势：按下即发码，长按进入连发循环，抬起/取消停止
    val gestureModifier = Modifier.pointerInput(enabled, repeatIntervalMs) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled) return@awaitEachGesture
            pressed = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onSend(PressKind.NEW_PRESS)
            // 长按连发：超过阈值后按 repeatIntervalMs 节奏发送 REPEAT
            val repeatJob = scope.launch {
                delay(LONG_PRESS_THRESHOLD_MS)
                while (true) {
                    delay(repeatIntervalMs.toLong())
                    onSend(PressKind.REPEAT)
                }
            }
            try {
                waitForUpOrCancellation()
            } finally {
                repeatJob.cancel()
                pressed = false
            }
        }
    }

    Surface(
        modifier = modifier
            .semantics { contentDescription = label ?: "按键" }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (pressed) 0f else 4f
            }
            .size(
                width = if (width == Dp.Unspecified) size else width,
                height = if (height == Dp.Unspecified) size else height,
            )
            .then(gestureModifier),
        shape = if (shape == ButtonShape.CIRCLE) CircleShape else RoundedCornerShape(KEY_CORNER_RADIUS),
        color = bg,
        tonalElevation = if (pressed) 0.dp else 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = fg,
                    modifier = Modifier.size(size * 0.5f),
                )
            } else if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = fg,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 长按判定阈值（毫秒） */
const val LONG_PRESS_THRESHOLD_MS = 400L

/** 默认长按连发间隔（毫秒，计划 §5.5：全帧重发协议默认 250ms） */
const val DEFAULT_REPEAT_MS = 250

/** 圆角矩形按键圆角 */
val KEY_CORNER_RADIUS = 20.dp
