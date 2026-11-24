package com.college.core.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.college.core.model.ProfilePalette
import com.college.core.ui.theme.AppTheme
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.PI

private const val BLOB_PIXELS = 256
private const val SCRIM_PIXELS = 256

private fun parseHex(value: String?, fallback: Color): Color = try {
    if (value.isNullOrBlank()) fallback else Color(android.graphics.Color.parseColor(value))
} catch (_: Exception) {
    fallback
}

// Soft radial blob pre-rendered once per palette. The expensive gradient work
// happens here, not in the draw loop.
private fun buildBlob(color: Color): ImageBitmap {
    val bitmap = Bitmap.createBitmap(BLOB_PIXELS, BLOB_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = BLOB_PIXELS / 2f
    // 24 concentric discs approximate a soft radial gradient. This runs once
    // per palette, so the per-frame cost stays at a single drawImage.
    for (step in 24 downTo 1) {
        val t = step / 24f
        paint.color = color.copy(alpha = 0.75f * (1f - t) * (1f - t)).toArgb()
        canvas.drawCircle(center, center, center * t, paint)
    }
    return bitmap.asImageBitmap()
}

// 4x256 vertical scrim: stretched across the screen at draw time. One tiny
// bitmap, no per-frame gradient allocation.
private fun buildScrim(background: Color): ImageBitmap {
    val bitmap = Bitmap.createBitmap(4, SCRIM_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint()
    val top = background.copy(alpha = 0.92f)
    val clear = background.copy(alpha = 0f)
    val bottom = background.copy(alpha = 0.78f)
    for (y in 0 until SCRIM_PIXELS) {
        val t = y / (SCRIM_PIXELS - 1).toFloat()
        paint.color = if (t < 0.35f) {
            lerp(top, clear, t / 0.35f).toArgb()
        } else {
            lerp(clear, bottom, (t - 0.35f) / 0.65f).toArgb()
        }
        canvas.drawRect(0f, y.toFloat(), 4f, (y + 1).toFloat(), paint)
    }
    return bitmap.asImageBitmap()
}

// Apple-Music-lyrics-style drifting color field derived from the owner's
// profile palette. Cost: 3 tiny bitmaps + 2 animated floats + 5 draw calls.
@Composable
fun AnimatedProfileBackground(
    palette: ProfilePalette?,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val context = LocalContext.current

    val blobColors = remember(palette, colors) {
        listOf(
            parseHex(palette?.primary, colors.primaryPurple),
            parseHex(palette?.secondary, colors.accentOrange),
            parseHex(palette?.tertiary, colors.infoBlue)
        )
    }
    val blobs = remember(blobColors) { blobColors.map(::buildBlob) }
    val scrim = remember(colors) { buildScrim(colors.background) }

    val reduceMotion = remember {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }

    val transition = rememberInfiniteTransition(label = "profileBackground")
    val phaseA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phaseA"
    )
    val phaseB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(19_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phaseB"
    )

    val a = if (reduceMotion) 0.25f else phaseA
    val b = if (reduceMotion) 0.60f else phaseB
    val blobAlpha = if (colors.isDark) 0.22f else 0.13f

    Box(
        modifier = modifier.drawBehind {
            val w = size.width
            val h = size.height
            drawRect(colors.background)

            val blobSize = max(w, h) * 1.15f
            val half = blobSize / 2f
            val ta = 2f * PI.toFloat() * a
            val tb = 2f * PI.toFloat() * b
            val tc = (ta + tb) / 2f

            val centers = arrayOf(
                Offset(w * (0.25f + 0.18f * sin(ta)), h * (0.28f + 0.16f * cos(ta))),
                Offset(w * (0.72f + 0.16f * cos(tb)), h * (0.68f + 0.18f * sin(tb))),
                Offset(w * (0.50f + 0.20f * sin(tc)), h * (0.52f + 0.18f * cos(tc)))
            )
            val blobIntSize = IntSize(blobSize.toInt(), blobSize.toInt())
            for (index in blobs.indices) {
                val c = centers[index]
                drawImage(
                    image = blobs[index],
                    dstOffset = IntOffset((c.x - half).toInt(), (c.y - half).toInt()),
                    dstSize = blobIntSize,
                    alpha = blobAlpha
                )
            }

            drawImage(
                image = scrim,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w.toInt(), h.toInt())
            )
        }
    )
}
