package com.college.core.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.college.core.model.ProfilePalette
import com.college.core.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

// Strong low-pass: ~3 s to settle. Slow enough that spinning the phone does
// not make the nodes juggle.
private const val TILT_SMOOTHING = 0.02f
private const val BG_FRAME_INTERVAL_MS = 30L // ~33 fps background mesh cadence

// Gravity direction in screen space, sampled at UI rate. TYPE_GRAVITY is a
// fused, already-filtered sensor when present; otherwise a cheap low-pass on
// the accelerometer. No gyroscope integration needed for a tilt direction.
private class TiltTracker(context: Context) {
    private val ctx = context.applicationContext
    private val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val vector = FloatArray(3)
    private var ready = false
    private var registered = false
    var onTilt: (Float, Float, Float) -> Unit = { _, _, _ -> }

    @Suppress("DEPRECATION")
    private val rotation: Int
        get() = (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
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
        val (sx, sy) = when (rotation) {
            Surface.ROTATION_90 -> gy to gx
            Surface.ROTATION_180 -> gx to gy
            Surface.ROTATION_270 -> -gy to -gx
            else -> -gx to gy
        }
        val length = sqrt(sx * sx + sy * sy)
        if (length > 0.3f) {
            val strength = (length / 9.8f).coerceIn(0f, 1f)
            onTilt(sx / length, sy / length, strength)
        }
    }

    fun start() {
        if (registered) return
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return
        registered = manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) == true
    }

    fun stop() {
        if (!registered) return
        manager?.unregisterListener(listener)
        registered = false
    }
}

private class TiltState(
    var gx: Float = 0f,
    var gy: Float = 1f,
    var strength: Float = 0f
)

// Shared phase/tilt state. The root background drives it; glass islands read
// it inside their draw lambdas so they stay in sync without child animators.
class BackgroundEngine {
    var fall by mutableFloatStateOf(0.35f)
    var sway by mutableFloatStateOf(0.25f)
    var breath by mutableFloatStateOf(0.10f)
    var gx by mutableFloatStateOf(0f)
    var gy by mutableFloatStateOf(1f)
    // Accumulated progress: advances faster as the phone tilts more.
    var flow by mutableFloatStateOf(0.35f)
    var tiltStrength by mutableFloatStateOf(0f)
    // Root viewport size, shared so glass islands draw the same mesh at the
    // same global coordinates instead of inventing blobs local to the island.
    var screenWidth by mutableFloatStateOf(0f)
    var screenHeight by mutableFloatStateOf(0f)
}

val LocalBackgroundEngine = staticCompositionLocalOf<BackgroundEngine?> { null }

// Liquid mesh background: lifecycle-gated ~33fps coroutine ticker updates BackgroundEngine
// directly so drawBehind repaints smoothly without recomposing the composable tree.
@Composable
fun AnimatedProfileBackground(
    palette: ProfilePalette?,
    modifier: Modifier = Modifier,
    engine: BackgroundEngine? = null,
    drive: Boolean = false,
    fill: Boolean = true
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val assets = remember(palette, colors) { GlassRenderCache.getOrBuild(palette, colors) }
    val noiseBrush = GlassStaticAssets.bgNoiseBrush
    val activeEngine = engine ?: remember { BackgroundEngine() }
    val shouldDrive = drive || engine == null
    val tiltState = remember { TiltState() }

    if (shouldDrive) {
        DisposableEffect(context, lifecycleOwner) {
            val tracker = TiltTracker(context)
            tracker.onTilt = { x, y, strength ->
                tiltState.gx = x
                tiltState.gy = y
                tiltState.strength = strength
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> tracker.start()
                    Lifecycle.Event.ON_PAUSE -> tracker.stop()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                tracker.start()
            }
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                tracker.stop()
            }
        }
    }

    val reduceMotion = remember(context) {
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

    if (shouldDrive) {
        LaunchedEffect(activeEngine, lifecycleOwner, reduceMotion) {
            if (reduceMotion) {
                activeEngine.fall = 0.35f
                activeEngine.sway = 0.25f
                activeEngine.breath = 0.10f
                activeEngine.flow = 0.35f
                activeEngine.gx = 0f
                activeEngine.gy = 1f
                activeEngine.tiltStrength = 0f
                return@LaunchedEffect
            }

            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastMs = SystemClock.uptimeMillis()
                var elapsedMs = 0L
                while (isActive) {
                    val nowMs = SystemClock.uptimeMillis()
                    val dtMs = (nowMs - lastMs).coerceIn(0L, 100L)
                    lastMs = nowMs
                    elapsedMs += dtMs

                    val fallDelta = dtMs / 30_000f
                    activeEngine.fall = (activeEngine.fall + fallDelta) % 1f
                    activeEngine.flow += fallDelta * (0.30f + 2.70f * tiltState.strength)

                    val sCycle = (elapsedMs % 44_000L) / 22_000f
                    activeEngine.sway = if (sCycle <= 1f) sCycle else 2f - sCycle

                    val bCycle = (elapsedMs % 42_000L) / 21_000f
                    activeEngine.breath = if (bCycle <= 1f) bCycle else 2f - bCycle

                    activeEngine.gx = tiltState.gx
                    activeEngine.gy = tiltState.gy
                    activeEngine.tiltStrength = tiltState.strength

                    delay(BG_FRAME_INTERVAL_MS)
                }
            }
        }
    }

    val blobBaseAlpha = if (colors.isDark) 0.42f else 0.23f
    val nodeBlend = if (colors.isDark) BlendMode.Screen else BlendMode.SrcOver

    val sizeRecorder = if (shouldDrive) {
        Modifier.onGloballyPositioned { coords ->
            val w = coords.size.width.toFloat()
            val h = coords.size.height.toFloat()
            if (w != activeEngine.screenWidth) activeEngine.screenWidth = w
            if (h != activeEngine.screenHeight) activeEngine.screenHeight = h
        }
    } else Modifier

    Box(
        modifier = (if (fill) modifier.fillMaxSize() else modifier)
            .then(sizeRecorder)
            .drawBehind {
                drawMeshLayer(
                    base = assets.rootBase,
                    blobs = assets.rootBlobs,
                    baseAlpha = 1f,
                    blobBaseAlpha = blobBaseAlpha,
                    nodeBlend = nodeBlend,
                    flow = activeEngine.flow,
                    sway = activeEngine.sway,
                    breath = activeEngine.breath,
                    gx = activeEngine.gx,
                    gy = activeEngine.gy,
                    viewportWidth = size.width,
                    viewportHeight = size.height,
                    offsetX = 0f,
                    offsetY = 0f
                )

                drawImage(
                    image = assets.scrim,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                )

                drawRect(
                    brush = noiseBrush,
                    alpha = 0.55f,
                    blendMode = BlendMode.Overlay
                )
            }
    )
}
