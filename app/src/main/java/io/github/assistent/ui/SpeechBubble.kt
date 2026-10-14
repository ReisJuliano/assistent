package io.github.assistent.ui

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** VT323 — retro terminal font (SIL Open Font License, see docs/VT323-OFL.txt). */
val RetroFont = FontFamily(androidx.compose.ui.text.font.Font(io.github.assistent.R.font.vt323))

/**
 * Speech bubble drawn like the rest of the face: a thin neon outline with glow (no filled box), retro
 * monospace text typed out with a blinking block cursor, and a tail pointing back at the mouth.
 */
@Composable
fun SpeechBubble(text: String?, color: Color) {
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(text) { if (text != null) shown = text }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        var bubbleHeightPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val measuredHeight = with(density) { bubbleHeightPx.toDp() }
        // Sits to the right of the mouth; the tail on its left edge points at the mouth.
        val dx = if (landscape) maxWidth * 0.62f else maxWidth * 0.2f
        // Long replies need room for several wrapped lines. Move them above
        // the face before they reach the bottom edge of the phone.
        val length = text?.length ?: 0
        val preferredY = if (landscape) {
            when {
                length > 180 -> maxHeight * 0.12f
                length > 100 -> maxHeight * 0.38f
                else -> maxHeight * 0.66f
            }
        } else {
            when {
                length > 180 -> maxHeight * 0.12f
                length > 100 -> maxHeight * 0.34f
                else -> maxHeight * 0.7f
            }
        }
        // Use the measured height, not a character-count estimate. Wrapped
        // lines can be much taller than the same number of characters in a
        // different width/orientation.
        val bottomSafeY = (maxHeight - measuredHeight - 24.dp).coerceAtLeast(12.dp)
        val dy = minOf(preferredY, bottomSafeY)
        val maxW = if (landscape) maxWidth * 0.3f else maxWidth * 0.62f
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            AnimatedVisibility(
                visible = text != null,
                enter = fadeIn() + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow), transformOrigin = TransformOrigin(0f, 0.5f)),
                exit = fadeOut() + scaleOut(transformOrigin = TransformOrigin(0f, 0.5f)),
                modifier = Modifier.offset(x = dx, y = dy),
            ) {
                var typed by remember(shown) { mutableIntStateOf(0) }
                var cursor by remember { mutableStateOf(true) }
                LaunchedEffect(shown) {
                    typed = 0
                    while (typed < shown.length) {
                        delay(32)
                        typed++
                    }
                }
                LaunchedEffect(Unit) {
                    while (true) { delay(450); cursor = !cursor }
                }
                val glowPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
                Box(
                    Modifier
                        .widthIn(max = maxW)
                        .onGloballyPositioned { bubbleHeightPx = it.size.height }
                        .drawBehind {
                            val stroke = 2.dp.toPx()
                            val r = 18.dp.toPx()
                            val tailW = 14.dp.toPx()
                            val midY = size.height / 2f
                            // Rounded outline with a small pointed tail on the left, as one continuous neon line.
                            val path = Path().apply {
                                moveTo(r, 0f)
                                lineTo(size.width - r, 0f)
                                quadraticTo(size.width, 0f, size.width, r)
                                lineTo(size.width, size.height - r)
                                quadraticTo(size.width, size.height, size.width - r, size.height)
                                lineTo(r, size.height)
                                quadraticTo(0f, size.height, 0f, size.height - r)
                                lineTo(0f, midY + tailW * 0.45f)
                                lineTo(-tailW * 1.4f, midY + tailW * 0.2f)
                                lineTo(0f, midY - tailW * 0.45f)
                                lineTo(0f, r)
                                quadraticTo(0f, 0f, r, 0f)
                                close()
                            }
                            drawPath(path, Color.Black)
                            drawIntoCanvas {
                                glowPaint.style = android.graphics.Paint.Style.STROKE
                                glowPaint.strokeWidth = stroke * 2.5f
                                glowPaint.maskFilter = BlurMaskFilter(8.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                                glowPaint.color = color.copy(alpha = 0.55f).toArgb()
                                it.nativeCanvas.drawPath(path.asAndroidPath(), glowPaint)
                            }
                            drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    val done = typed >= shown.length
                    Text(
                        shown.take(typed) + if (!done || cursor) "▌" else " ",
                        style = TextStyle(
                            color = color,
                            fontFamily = RetroFont,
                            fontSize = 28.sp,
                            lineHeight = 30.sp,
                            letterSpacing = 0.5.sp,
                            shadow = Shadow(color.copy(alpha = 0.8f), blurRadius = 14f),
                        ),
                    )
                }
            }
        }
    }
}
