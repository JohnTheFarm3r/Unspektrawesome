package com.unspektrawesome.ui.camera

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Canvas as NativeCanvas
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.ColorMatrixColorFilter
import android.graphics.ComposeShader
import android.graphics.Picture
import android.graphics.RenderNode
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewTreeObserver
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unspektrawesome.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val AMBIENT_MAX_DIMENSION = 320
internal const val AMBIENT_FRAME_DELAY_MS = 67L
internal val LocalPageCardColor = staticCompositionLocalOf { CameraColors.MenuCard }
internal val LocalAmbientBackdrop = staticCompositionLocalOf<AmbientBackdrop?> { null }
private val LocalAmbientView = staticCompositionLocalOf<View?> { null }

internal class AmbientBackdrop {
    val frame = mutableStateOf<AmbientTextures?>(null)
    val textures = AmbientTextureCache()
    val seconds = mutableFloatStateOf(0f)
    var size by mutableStateOf(IntSize.Zero)
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
}

private val ambientDitherBitmap by lazy {
    Bitmap.createBitmap(IntArray(64 * 64) { index ->
        var value = index + 1
        value = (value xor (value ushr 16)) * 0x7feb352d
        value = (value xor (value ushr 15)) * 0x846ca68b.toInt()
        0xff000000.toInt() or ((value xor (value ushr 16)) and 255)
    }, 64, 64, Bitmap.Config.ARGB_8888)
}

@Composable
internal fun Modifier.pageCardBackground(shape: Shape = RectangleShape): Modifier {
    val ambientView = LocalAmbientView.current
    val sameWindow = ambientView == null || ambientView === LocalView.current
    val color = if (sameWindow) LocalPageCardColor.current else CameraColors.MenuCard
    val backdrop = if (sameWindow) LocalAmbientBackdrop.current else null
    if (backdrop == null || color.alpha <= 0f || color.alpha >= 1f) return background(color, shape)
    val painter = remember(color) { AmbientFramePainter(color) }
    val position = remember { mutableStateOf<Offset?>(null) }
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind {
            val frame = backdrop.frame.value
            val offset = position.value
            if (frame != null && offset != null) {
                drawIntoCanvas { it.drawOutline(outline, painter.prepare(frame, backdrop.size, offset, backdrop.seconds.floatValue)) }
            } else {
                drawOutline(outline, color)
            }
        }
    }.onGloballyPositioned { card ->
        position.value = backdrop.coordinates?.takeIf { it.isAttached }?.localPositionOf(card, Offset.Zero)
    }
}

private fun ambientAnimationsEnabled(context: Context) = ValueAnimator.areAnimatorsEnabled() &&
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

@Composable
internal fun AmbientScreen(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val nested = LocalAmbientView.current === view
    val inherited = LocalAmbientBackdrop.current
    val backdrop = if (nested) inherited else remember { AmbientBackdrop() }
    Box(modifier.then(if (nested) Modifier else Modifier.background(CameraColors.Background))) {
        CompositionLocalProvider(LocalAmbientView provides view, LocalPageCardColor provides CameraColors.AmbientCard,
            LocalAmbientBackdrop provides backdrop) {
            if (!nested) AmbientBackground(Modifier.matchParentSize(), active)
            content()
        }
    }
}

internal fun ambientRenderSize(width: Int, height: Int): IntSize {
    val scale = minOf(1f, AMBIENT_MAX_DIMENSION.toFloat() / maxOf(width, height).coerceAtLeast(1))
    return IntSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
}

internal fun ambientMotionAllowed(resumed: Boolean, focused: Boolean, powerSave: Boolean, thermal: Int, animations: Boolean) =
    resumed && focused && !powerSave && thermal < PowerManager.THERMAL_STATUS_MODERATE && animations

@Composable
private fun ambientMotionEnabled(): Boolean {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var focused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    var powerSave by remember(power) { mutableStateOf(power.isPowerSaveMode) }
    var thermal by remember(power) { mutableStateOf(power.currentThermalStatus) }
    var animations by remember { mutableStateOf(ambientAnimationsEnabled(context)) }
    DisposableEffect(context, lifecycle, view, power) {
        fun refresh() {
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            focused = view.hasWindowFocus()
            powerSave = power.isPowerSaveMode
            thermal = power.currentThermalStatus
            animations = ambientAnimationsEnabled(context)
        }
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        val thermalListener = PowerManager.OnThermalStatusChangedListener { thermal = it }
        val motionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        lifecycle.addObserver(observer)
        val viewTree = view.viewTreeObserver
        viewTree.addOnWindowFocusChangeListener(focus)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        power.addThermalStatusListener(context.mainExecutor, thermalListener)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver)
        refresh()
        onDispose {
            lifecycle.removeObserver(observer)
            if (viewTree.isAlive) viewTree.removeOnWindowFocusChangeListener(focus)
            val currentTree = view.viewTreeObserver
            if (currentTree !== viewTree && currentTree.isAlive) currentTree.removeOnWindowFocusChangeListener(focus)
            context.unregisterReceiver(receiver)
            power.removeThermalStatusListener(thermalListener)
            context.contentResolver.unregisterContentObserver(motionObserver)
        }
    }
    return ambientMotionAllowed(resumed, focused, powerSave, thermal, animations)
}

@Composable
internal fun AmbientBackground(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    renderer: AmbientWaveRenderer? = null,
) {
    val context = LocalContext.current
    val backdrop = LocalAmbientBackdrop.current
    val waves = renderer ?: remember(context) { createAmbientWaveRenderer(context) }
    val painter = remember { AmbientFramePainter() }
    val seconds = backdrop?.seconds ?: remember { mutableFloatStateOf(0f) }
    val frame = backdrop?.frame ?: remember { mutableStateOf<AmbientTextures?>(null) }
    val textures = backdrop?.textures ?: remember { AmbientTextureCache() }
    var renderSize by remember { mutableStateOf(IntSize.Zero) }
    val running = ambientMotionEnabled() && active
    LaunchedEffect(waves, renderSize) {
        if (renderSize != IntSize.Zero) {
            val needsFrame = frame.value?.size != renderSize || frame.value?.renderer !== waves
            if (needsFrame) frame.value = textures.get(waves, renderSize)
        }
    }
    val ready = frame.value?.let { it.size == renderSize && it.renderer === waves } == true
    LaunchedEffect(waves, renderSize, running, ready) {
        if (!running || !ready) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isActive) {
            delay(AMBIENT_FRAME_DELAY_MS)
            withFrameNanos { now ->
                seconds.floatValue += ((now - previous) / 1_000_000_000f).coerceAtMost(.25f)
                previous = now
            }
        }
    }
    Canvas(modifier.clearAndSetSemantics {}.onSizeChanged {
        backdrop?.size = it
        renderSize = if (it.width > 0 && it.height > 0) ambientRenderSize(it.width, it.height) else IntSize.Zero
    }.onGloballyPositioned {
        backdrop?.coordinates = it
    }) {
        frame.value?.let {
            drawIntoCanvas { canvas -> painter.draw(canvas.nativeCanvas, it, size.width, size.height, seconds.floatValue) }
        }
    }
}

internal class AmbientFramePainter(tint: Color = Color.Transparent) {
    private val noiseShader = BitmapShader(ambientDitherBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private var textures: AmbientTextures? = null
    private var layers = emptyList<AmbientTextureShader>()
    private val paint = androidx.compose.ui.graphics.Paint().apply {
        filterQuality = FilterQuality.Low
        asFrameworkPaint().isDither = false
        val neutral = CameraColors.Muted
        val accent = CameraColors.Accent
        val visible = 1f - tint.alpha
        // Combine the card tint before dithering, avoiding an intermediate dark gradient.
        asFrameworkPaint().colorFilter = ColorMatrixColorFilter(floatArrayOf(
            neutral.red * .265f * visible, accent.red * .018f * visible, 1f / 255f, 0f, (neutral.red * .012f * visible + tint.red * tint.alpha) * 255f - .5f,
            neutral.green * .265f * visible, accent.green * .018f * visible, 1f / 255f, 0f, (neutral.green * .012f * visible + tint.green * tint.alpha) * 255f - .5f,
            neutral.blue * .265f * visible, accent.blue * .018f * visible, 1f / 255f, 0f, (neutral.blue * .012f * visible + tint.blue * tint.alpha) * 255f - .5f,
            0f, 0f, 0f, 0f, 255f,
        ))
    }

    fun draw(canvas: NativeCanvas, frame: AmbientTextures, width: Float, height: Float, seconds: Float) {
        val paint = prepare(frame, IntSize(width.roundToInt(), height.roundToInt()), seconds = seconds)
        canvas.drawRect(0f, 0f, width, height, paint.asFrameworkPaint())
    }

    fun prepare(frame: AmbientTextures, viewport: IntSize, offset: Offset = Offset.Zero, seconds: Float = 0f): androidx.compose.ui.graphics.Paint {
        if (textures !== frame) {
            textures = frame
            layers = frame.fields.map { AmbientTextureShader(it) }
            val fields = layers.drop(1).fold(layers.first().shader as Shader) { combined, layer ->
                ComposeShader(combined, layer.shader, BlendMode.PLUS)
            }
            paint.asFrameworkPaint().shader = ComposeShader(fields, noiseShader, BlendMode.PLUS)
        }
        layers.forEach { it.update(viewport, offset, seconds) }
        return paint
    }
}

internal enum class AmbientField { STATIC, WAVE_A, WAVE_B, GLOW, GRADIENT_WAVE, GRADIENT_GLOW }

internal data class AmbientTexture(val field: AmbientField, val image: ImageBitmap)
internal class AmbientTextures(val renderer: AmbientWaveRenderer, val size: IntSize, val fields: List<AmbientTexture>)

internal class AmbientTextureCache {
    private val mutex = Mutex()
    private val entries = ArrayDeque<AmbientTextures>()

    suspend fun get(renderer: AmbientWaveRenderer, size: IntSize): AmbientTextures =
        withContext(NonCancellable + Dispatchers.Default) {
            mutex.withLock {
                entries.firstOrNull { it.renderer === renderer && it.size == size } ?: run {
                    val textures = AmbientFrameBuffer(renderer, size).use { it.render() }
                    entries.removeAll { it.renderer !== renderer }
                    if (entries.size == 2) entries.removeFirst()
                    entries.addLast(textures)
                    textures
                }
            }
        }
}

private class AmbientTextureShader(texture: AmbientTexture) {
    private val field = texture.field
    private val width = texture.image.width.toFloat()
    private val height = texture.image.height.toFloat()
    private val transform = Matrix()
    val shader = BitmapShader(texture.image.asAndroidBitmap(), Shader.TileMode.CLAMP,
        if (field == AmbientField.WAVE_A || field == AmbientField.WAVE_B) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP)

    fun update(viewport: IntSize, offset: Offset, seconds: Float) {
        val w = viewport.width.toFloat()
        val h = viewport.height.toFloat()
        when (field) {
            AmbientField.STATIC -> transform.setScale(w / width, h / height)
            AmbientField.WAVE_A, AmbientField.WAVE_B -> {
                val frequency = if (field == AmbientField.WAVE_A) 4.5f else 5.2f
                val speed = if (field == AmbientField.WAVE_A) .15f else -.115f
                val period = (2.0 * kotlin.math.PI).toFloat()
                transform.setScale(w / width, h * period / (frequency * height))
                transform.postTranslate(0f, -(seconds * speed % period) * h / frequency)
            }
            AmbientField.GLOW -> {
                transform.setTranslate(-width / 2f, -height / 2f)
                transform.postScale(w * 1.3f / width, h * (1.3f / 1.2f) / height)
                transform.postTranslate(w * (.78f + .10f * sin(seconds * .10f)), h * .25f)
            }
            AmbientField.GRADIENT_WAVE -> {
                transform.setTranslate(-width / 2f, -height / 2f)
                transform.postScale(w * .64f / width, h * 2.10f / height)
                transform.postRotate(-18f)
                transform.postTranslate(w * (.38f + .15f * sin(seconds * .15f)), h * .48f)
            }
            AmbientField.GRADIENT_GLOW -> {
                transform.setTranslate(-width / 2f, -height / 2f)
                transform.postScale(w * 1.4f / width, h * .9f / height)
                transform.postTranslate(w * (.75f + .10f * sin(seconds * .115f)), h * .28f)
            }
        }
        transform.postTranslate(-offset.x, -offset.y)
        shader.setLocalMatrix(transform)
    }
}

internal class AmbientFrameBuffer(private val renderer: AmbientWaveRenderer, private val size: IntSize) : AutoCloseable {
    private val node = RenderNode("ambient-waves").apply { setPosition(0, 0, size.width, size.height) }
    private val picture = object : Picture() {
        override fun getWidth() = size.width
        override fun getHeight() = size.height
        override fun requiresHardwareAcceleration() = true
        override fun endRecording() = Unit
        override fun draw(canvas: NativeCanvas) { canvas.drawRenderNode(node) }
    }
    private var textures: AmbientTextures? = null
    private var closed = false

    fun render(): AmbientTextures = synchronized(renderer) {
        check(!closed)
        textures ?: AmbientTextures(renderer, size, renderer.fields.map { field ->
            val canvas = node.beginRecording(size.width, size.height)
            try {
                renderer.draw(canvas, size.width, size.height, field)
            } finally {
                node.endRecording()
            }
            // Immutable fields are rendered once; animation only moves their GPU samplers.
            AmbientTexture(field, Bitmap.createBitmap(picture).asImageBitmap())
        }).also { textures = it }
    }

    override fun close() = synchronized(renderer) {
        if (!closed) {
            closed = true
            node.discardDisplayList()
        }
    }
}

internal interface AmbientWaveRenderer {
    val fields: List<AmbientField> get() = listOf(AmbientField.STATIC)
    fun draw(canvas: NativeCanvas, width: Int, height: Int, field: AmbientField)
}

internal fun createAmbientWaveRenderer(context: Context): AmbientWaveRenderer =
    if (Build.VERSION.SDK_INT >= 33) RuntimeAmbientWaves(context.resources.openRawResource(R.raw.ambient_waves)
        .bufferedReader().use { it.readText() }) else GradientAmbientWaves()

@RequiresApi(33)
private class RuntimeAmbientWaves(source: String) : AmbientWaveRenderer {
    override val fields = listOf(AmbientField.WAVE_A, AmbientField.WAVE_B, AmbientField.GLOW)
    private val shader = RuntimeShader(source)
    private val paint = Paint().apply { shader = this@RuntimeAmbientWaves.shader }

    override fun draw(canvas: NativeCanvas, width: Int, height: Int, field: AmbientField) {
        shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        shader.setIntUniform("field", fields.indexOf(field))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

internal class GradientAmbientWaves : AmbientWaveRenderer {
    override val fields = listOf(AmbientField.GRADIENT_WAVE, AmbientField.GRADIENT_GLOW)
    private val neutral = RadialGradient(0f, 0f, 1f,
        intArrayOf(0xffff0000.toInt(), 0xff600000.toInt(), 0xff000000.toInt()),
        floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
    private val accent = RadialGradient(0f, 0f, 1f, 0xff00ff00.toInt(), 0xff000000.toInt(), Shader.TileMode.CLAMP)
    private val transform = Matrix()
    private val paint = Paint()

    override fun draw(canvas: NativeCanvas, width: Int, height: Int, field: AmbientField) {
        val shader = if (field == AmbientField.GRADIENT_WAVE) neutral else accent
        transform.setScale(width * .5f, height * .5f)
        transform.postTranslate(width * .5f, height * .5f)
        shader.setLocalMatrix(transform)
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
