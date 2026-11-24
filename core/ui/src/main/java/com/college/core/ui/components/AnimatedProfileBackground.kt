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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.college.core.model.ProfilePalette
import com.college.core.ui.theme.AppTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

private const val BLOB_PIXELS = 384
private const val NOISE_PIXELS = 128
private const val SCRIM_PIXELS = 256

private fun parseHex(value: String?, fallback: Color): Color = try {
    if (value.isNullOrBlank()) fallback else Color(android.graphics.Color.parseColor(value))
} catch (_: Exception) {
    fallback
}

// Push extracted colors slightly more vivid: artwork palettes are often muted
// once reduced to 3 average colors, but the glow wants saturated light.
private fun vivid(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.18f).coerceAtMost(1f)
    hsv[2] = (hsv[2] * 1.04f).coerceAtMost(1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// Oversized, Gaussian-like diffused node. 48 stacked discs approximate the
// falloff of a heavy blur; this runs ONCE per palette, not per frame.
private fun buildBlob(color: Color): ImageBitmap {
    val bitmap = Bitmap.createBitmap(BLOB_PIXELS, BLOB_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = BLOB_PIXELS / 2f
    for (step in 48 downTo 1) {
        val t = step / 48f
        paint.color = color.copy(alpha = 0.55f * exp(-4.5f * t * t)).toArgb()
        canvas.drawCircle(center, center, center * t, paint)
    }
    return bitmap.asImageBitmap()
}

// Static anti-banding film grain, tiled across the whole screen at draw time.
private fun buildNoise(): ImageBitmap {
    val pixels = IntArray(NOISE_PIXELS * NOISE_PIXELS)
    val random = Random(1337)
    for (i in pixels.indices) {
        val value = random.nextInt(256)
        val alpha = random.nextInt(18)
        pixels[i] = (alpha shl 24) or (value shl 16) or (value shl 8) or value
    }
    val bitmap = Bitmap.createBitmap(NOISE_PIXELS, NOISE_PIXELS, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, NOISE_PIXELS, 0, 0, NOISE_PIXELS, NOISE_PIXELS)
    return bitmap.asImageBitmap()
}

// 4x256 stretched scrim keeps text/search legible over the brightest pools.
private fun buildScrim(background: Color): ImageBitmap {
    val bitmap = Bitmap.createBitmap(4, SCRIM_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint()
    val top = background.copy(alpha = 0.88f)
    val clear = background.copy(alpha = 0f)
    val bottom = background.copy(alpha = 0.72f)
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

// Liquid mesh background: oversized diffused nodes drift, stretch and blend
// over a grained canvas. Everything expensive is pre-rendered once; the frame
// loop is a handful of draw calls plus a few sin/cos reads.
@Composable
fun AnimatedProfileBackground(
    palette: ProfilePalette?,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val context = LocalContext.current

    val nodeColors = remember(palette, colors) {
        val first = vivid(parseHex(palette?.primary, colors.primaryPurple))
        val second = vivid(parseHex(palette?.secondary, colors.accentOrange))
        val third = vivid(parseHex(palette?.tertiary, colors.infoBlue))
        listOf(first, second, third, lerp(first, third, 0.5f))
    }
    val blobs = remember(nodeColors) { nodeColors.map(::buildBlob) }
    val noise = remember { buildNoise() }
    val noiseBrush = remember(noise) {
        ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated))
    }
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
        0f, 1f,
        infiniteRepeatable(tween(13_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "phaseA"
    )
    val phaseB by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(17_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "phaseB"
    )
    val phaseC by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(23_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "phaseC"
    )

    val a = if (reduceMotion) 0.25f else phaseA
    val b = if (reduceMotion) 0.60f else phaseB
    val c = if (reduceMotion) 0.10f else phaseC
    val ta = 2f * PI.toFloat() * a
    val tb = 2f * PI.toFloat() * b
    val tc = 2f * PI.toFloat() * c
    val baseAlpha = if (colors.isDark) 0.30f else 0.17f
    val nodeBlend = if (colors.isDark) BlendMode.Screen else BlendMode.SrcOver

    Box(
        modifier = modifier.fillMaxSize().drawBehind {
            val w = size.width
            val h = size.height
            drawRect(colors.background)

            val span = max(w, h)
            // Two superimposed frequencies per node = quasi-periodic, organic
            // drift instead of a mechanical circle. Size breathes with phase C.
            val breath = 1f + 0.16f * sin(tc)
            for (index in blobs.indices) {
                val phase = when (index) {
                    0 -> ta
                    1 -> tb
                    2 -> tc
                    else -> ta * 0.5f + tb * 0.5f
                }
                val cx = w * (0.5f + 0.34f * sin(phase + index * 1.7f))
                val cy = h * (0.5f + 0.32f * cos(phase * 0.83f + index * 2.1f))
                val stretchX = 1f + 0.22f * sin(phase * 0.61f + index)
                val stretchY = 1f + 0.20f * cos(phase * 0.73f + index)
                val nodeW = span * 1.65f * breath * stretchX
                val nodeH = span * 1.65f * breath * stretchY
                drawImage(
                    image = blobs[index],
                    dstOffset = IntOffset((cx - nodeW / 2f).toInt(), (cy - nodeH / 2f).toInt()),
                    dstSize = IntSize(nodeW.toInt(), nodeH.toInt()),
                    alpha = baseAlpha * (0.85f + 0.15f * sin(phase + tc)),
                    blendMode = nodeBlend
                )
            }

            drawImage(
                image = scrim,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w.toInt(), h.toInt())
            )

            drawRect(
                brush = noiseBrush,
                alpha = 0.55f,
                blendMode = BlendMode.Overlay
            )
        }
    )
}
