package com.college.core.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.college.core.model.ProfilePalette
import com.college.core.ui.theme.AppTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val BLOB_PIXELS = 384
private const val NOISE_PIXELS = 128
private const val SCRIM_PIXELS = 256
// Strong low-pass: ~3 s to settle. Slow enough that spinning the phone does
// not make the nodes juggle.
private const val TILT_SMOOTHING = 0.02f

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
    hsv[1] = (hsv[1] * 1.30f).coerceAtMost(1f)
    hsv[2] = (hsv[2] * 1.06f).coerceAtMost(1f)
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

// Vertical field tint: the "water" the blobs float in. Mixed from the same
// palette as the nodes, pre-rendered once and stretched at draw time.
private fun buildBase(palette: List<Color>, background: Color, isDark: Boolean): ImageBitmap {
    val width = 8
    val bitmap = Bitmap.createBitmap(width, SCRIM_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint()
    val bg = background.toArgb()
    val top = mixArgb(bg, palette[0].toArgb(), if (isDark) 0.17f else 0.10f)
    val middle = mixArgb(bg, palette[2].toArgb(), if (isDark) 0.12f else 0.07f)
    val bottom = mixArgb(bg, palette[1].toArgb(), if (isDark) 0.15f else 0.09f)
    for (y in 0 until SCRIM_PIXELS) {
        val t = y / (SCRIM_PIXELS - 1).toFloat()
        paint.color = if (t < 0.5f) {
            mixArgb(top, middle, t * 2f)
        } else {
            mixArgb(middle, bottom, (t - 0.5f) * 2f)
        }
        canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 1).toFloat(), paint)
    }
    return bitmap.asImageBitmap()
}

// 4x256 stretched scrim keeps text/search legible over the brightest pools.
private fun buildScrim(background: Color): ImageBitmap {
    val bitmap = Bitmap.createBitmap(4, SCRIM_PIXELS, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint()
    val top = background.copy(alpha = 0.88f).toArgb()
    val clear = background.copy(alpha = 0f).toArgb()
    val bottom = background.copy(alpha = 0.72f).toArgb()
    for (y in 0 until SCRIM_PIXELS) {
        val t = y / (SCRIM_PIXELS - 1).toFloat()
        paint.color = if (t < 0.35f) {
            mixArgb(top, clear, t / 0.35f)
        } else {
            mixArgb(clear, bottom, (t - 0.35f) / 0.65f)
        }
        canvas.drawRect(0f, y.toFloat(), 4f, (y + 1).toFloat(), paint)
    }
    return bitmap.asImageBitmap()
}

// Gravity direction in screen space, sampled at UI rate. TYPE_GRAVITY is a
// fused, already-filtered sensor when present; otherwise a cheap low-pass on
// the accelerometer. No gyroscope integration needed for a tilt direction.
private class TiltTracker(context: Context) {
    private val ctx = context.applicationContext
    private val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val vector = FloatArray(3)
    private var ready = false
    var onTilt: (Float, Float) -> Unit = { _, _ -> }

    @Suppress("DEPRECATION")
    private val rotation: Int
        get() = (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Same gentle low-pass for both sensor types. TYPE_GRAVITY is
            // already fused, but without extra smoothing a quick spin yanks
            // the whole field around and looks like juggling.
            vector[0] = TILT_SMOOTHING * event.values[0] + (1f - TILT_SMOOTHING) * vector[0]
            vector[1] = TILT_SMOOTHING * event.values[1] + (1f - TILT_SMOOTHING) * vector[1]
            ready = true
            publish()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun publish() {
        if (!ready) return
        val gx = vector[0]
        val gy = vector[1]
        // Accelerometer reads proper acceleration (-gravity). Flip it to get
        // the downhill direction, then rotate device axes into screen axes
        // (screen y grows downward, device y points to the top of the phone).
        val (sx, sy) = when (rotation) {
            Surface.ROTATION_90 -> gy to gx
            Surface.ROTATION_180 -> gx to gy
            Surface.ROTATION_270 -> -gy to -gx
            else -> -gx to gy
        }
        val length = sqrt(sx * sx + sy * sy)
        if (length > 0.5f) onTilt(sx / length, sy / length)
    }

    fun start() {
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        manager?.unregisterListener(listener)
    }
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
        listOf(
            first, second, third,
            Color(mixArgb(first.toArgb(), second.toArgb(), 0.5f)),
            Color(mixArgb(second.toArgb(), third.toArgb(), 0.5f))
        )
    }
    val blobs = remember(nodeColors) { nodeColors.map(::buildBlob) }
    val base = remember(nodeColors, colors) {
        buildBase(nodeColors, colors.background, colors.isDark)
    }
    val noise = remember { buildNoise() }
    val noiseBrush = remember(noise) {
        ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated))
    }
    val scrimBase = Color(
        mixArgb(
            colors.background.toArgb(),
            nodeColors[1].toArgb(),
            if (colors.isDark) 0.10f else 0.06f
        )
    )
    val scrim = remember(scrimBase) { buildScrim(scrimBase) }

    // Sensor writes ~20 Hz into state. Only the draw lambda reads these, so
    // content never recomposes on tilt.
    var gravityX by remember { mutableFloatStateOf(0f) }
    var gravityY by remember { mutableFloatStateOf(1f) }
    DisposableEffect(context) {
        val tracker = TiltTracker(context)
        tracker.onTilt = { x, y ->
            gravityX = x
            gravityY = y
        }
        tracker.start()
        onDispose { tracker.stop() }
    }

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
    // Restart (not Reverse): a node travels fully across the screen, exits,
    // then re-enters from the opposite edge. Staggered offsets hide the wrap.
    val fallPhase by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Restart),
        label = "fallPhase"
    )
    val swayPhase by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(22_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "swayPhase"
    )
    val breathPhase by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(21_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathPhase"
    )

    val fall = if (reduceMotion) 0.35f else fallPhase
    val sway = if (reduceMotion) 0.25f else swayPhase
    val breath = if (reduceMotion) 0.10f else breathPhase
    val ts = 2f * PI.toFloat() * sway
    val tb = 2f * PI.toFloat() * breath
    val baseAlpha = if (colors.isDark) 0.42f else 0.23f
    val nodeBlend = if (colors.isDark) BlendMode.Screen else BlendMode.SrcOver

    Box(
        modifier = modifier.fillMaxSize().drawBehind {
            val w = size.width
            val h = size.height
            drawImage(
                image = base,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(w.toInt(), h.toInt())
            )

            val span = max(w, h)
            // Gravity from the tilt sensor is "screen down". Perpendicular
            // component makes nodes sway sideways like falling leaves.
            val gx = gravityX
            val gy = gravityY
            val px = -gy
            val py = gx
            val breathing = 1f + 0.18f * sin(tb)

            for (index in blobs.indices) {
                // Staggered progress: each node starts 1/5th of the journey
                // behind the previous one, so the screen is never empty.
                val raw = fall + index / blobs.size.toFloat()
                val progress = raw - floor(raw)
                val travel = (progress * 2.2f - 1.1f) * span
                val sideways = 0.10f * span * sin(ts + index * 1.7f)
                val cx = w * 0.5f + gx * travel + px * sideways
                val cy = h * 0.5f + gy * travel + py * sideways

                val stretchX = 1f + 0.14f * sin(ts * 0.61f + index)
                val stretchY = 1f + 0.12f * cos(ts * 0.73f + index)
                val nodeW = span * 0.95f * breathing * stretchX
                val nodeH = span * 0.95f * breathing * stretchY
                drawImage(
                    image = blobs[index],
                    dstOffset = IntOffset((cx - nodeW / 2f).toInt(), (cy - nodeH / 2f).toInt()),
                    dstSize = IntSize(nodeW.toInt(), nodeH.toInt()),
                    alpha = baseAlpha * (0.80f + 0.20f * sin(ts + tb + index)),
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
