package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.preview.FocusLoupeRequest
import com.unspektrawesome.preview.FocusLoupeStatus
import com.unspektrawesome.preview.FocusMeterState
import com.unspektrawesome.preview.TapMeteringPhase
import com.unspektrawesome.preview.TapMeteringStatus
import com.unspektrawesome.settings.LoupePlacement
import com.unspektrawesome.settings.LoupeZoom
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.unspektrawesome.language.LanguageText as Text

private val LoupeWidth = 224.dp
private val LoupeHeight = 160.dp
private val LoupeCorner = 14.dp
private val LoupeMargin = 12.dp
private const val LOUPE_HIDE_MS = 2_000
private const val LOUPE_FOCUS_TIMEOUT_MS = 4_000L

/** Unclipped bounds in root pixels, including scaling by parent layers. */
internal fun LayoutCoordinates.visualBoundsInRoot(): Rect {
    val start = localToRoot(Offset.Zero)
    val end = localToRoot(Offset(size.width.toFloat(), size.height.toFloat()))
    return Rect(minOf(start.x, end.x), minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y))
}

/** A focus point chosen outside the loupe, normalized to the viewfinder surface. */
internal data class LoupeTargetRequest(val generation: Long, val x: Float, val y: Float)

/**
 * Magnified RAW view for focusing. Opens during manual focus and, optionally, after tap to focus,
 * where it hides two seconds after focusing unless held to pin. The image is drawn natively from the
 * full-resolution RAW frame inside the rectangle this layer reports through [onRequest].
 *
 * The magnified point moves by dragging the image or the marked area, or through [targetRequest].
 * The window moves with its grip and stays at [placement]; without one it avoids the focus point.
 *
 * Target positions are normalized to the whole viewfinder surface; [horizontal] and [vertical] are
 * the fractions of the surface visible in this viewport.
 */
@Composable
internal fun FocusLoupeLayer(
    manualActive: Boolean,
    tapEnabled: Boolean,
    tapStatus: TapMeteringStatus,
    /** True while a camera button is half pressed; the tap loupe then stays open until it is released. */
    held: Boolean = false,
    status: FocusLoupeStatus,
    zoom: LoupeZoom,
    peaking: Float,
    horizontal: Float,
    vertical: Float,
    surfaceBounds: Rect?,
    obstacles: List<Rect>,
    topInset: Dp,
    rotation: Float,
    placement: LoupePlacement?,
    targetRequest: LoupeTargetRequest?,
    onPlacementChange: (LoupePlacement?) -> Unit,
    onZoomChange: (LoupeZoom) -> Unit,
    onRequest: (FocusLoupeRequest?) -> Unit,
    onBoundsChange: (Rect?) -> Unit = {},
) {
    var target by remember { mutableStateOf(Offset(.5f, .5f)) }
    var seenTapGeneration by remember { mutableLongStateOf(tapStatus.generation) }
    var tapVisible by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    var manualDismissed by remember { mutableStateOf(false) }
    val hideProgress = remember { Animatable(1f) }
    var hiding by remember { mutableStateOf(false) }

    LaunchedEffect(manualActive) {
        if (manualActive) {
            manualDismissed = false
            tapVisible = false
            pinned = false
        }
    }
    LaunchedEffect(tapEnabled) { if (!tapEnabled) { tapVisible = false; pinned = false } }
    var wasHeld by remember { mutableStateOf(false) }
    LaunchedEffect(held) {
        // Releasing the half press, or pressing fully to take the photo, closes the loupe it opened.
        if (!held && wasHeld && !pinned) tapVisible = false
        wasHeld = held
    }
    // A point chosen on the viewfinder while focusing manually; it also reopens a closed loupe.
    LaunchedEffect(targetRequest?.generation) {
        val request = targetRequest ?: return@LaunchedEffect
        if (!manualActive) return@LaunchedEffect
        target = Offset(request.x.coerceIn(0f, 1f), request.y.coerceIn(0f, 1f))
        manualDismissed = false
    }
    LaunchedEffect(tapStatus.generation, tapStatus.phase, tapEnabled) {
        if (!tapEnabled || manualActive) return@LaunchedEffect
        if (tapStatus.generation != seenTapGeneration && tapStatus.phase == TapMeteringPhase.METERING) {
            seenTapGeneration = tapStatus.generation
            target = Offset(tapStatus.normalizedX, tapStatus.normalizedY)
            tapVisible = true
            pinned = false
        }
    }
    // Hide two seconds after focusing finishes, or after a focus attempt that never reports a result.
    val focusFinished = tapStatus.phase != TapMeteringPhase.METERING && tapStatus.phase != TapMeteringPhase.IDLE
    LaunchedEffect(tapVisible, pinned, focusFinished, tapStatus.generation, held) {
        hiding = false
        hideProgress.snapTo(1f)
        if (!tapVisible || pinned || held) return@LaunchedEffect
        if (!focusFinished) kotlinx.coroutines.delay(LOUPE_FOCUS_TIMEOUT_MS)
        hiding = true
        hideProgress.animateTo(0f, tween(LOUPE_HIDE_MS, easing = LinearEasing))
        tapVisible = false
        hiding = false
    }

    val visible = (manualActive && !manualDismissed) || tapVisible
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 160 else 220), label = "Loupe opacity")
    val currentRequest by rememberUpdatedState(onRequest)
    val currentBounds by rememberUpdatedState(onBoundsChange)
    if (alpha <= .001f) {
        LaunchedEffect(Unit) { currentRequest(null); currentBounds(null) }
        return
    }

    var unavailable by remember { mutableStateOf(false) }
    LaunchedEffect(status.requested, status.available) {
        unavailable = false
        if (status.requested && !status.available) {
            kotlinx.coroutines.delay(600)
            unavailable = true
        }
    }

    val density = LocalDensity.current
    val sideways = abs(rotation) == 90f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxWidth = maxWidth
        val boxHeight = maxHeight
        val userWidth = minOf(LoupeWidth, (if (sideways) boxHeight else boxWidth) * .6f)
        val userHeight = userWidth * (LoupeHeight / LoupeWidth)
        val loupeWidth = if (sideways) userHeight else userWidth
        val loupeHeight = if (sideways) userWidth else userHeight
        val targetView = Offset(.5f + (target.x - .5f) / horizontal, .5f + (target.y - .5f) / vertical)
        val shownCenter = if (status.available) Offset(.5f + (status.centerX - .5f) / horizontal,
            .5f + (status.centerY - .5f) / vertical) else targetView

        var autoSpot by remember { mutableStateOf<Offset?>(null) }
        var dragSpot by remember { mutableStateOf<Offset?>(null) }
        var bounds by remember { mutableStateOf<Rect?>(null) }
        var origin by remember { mutableStateOf(Offset.Zero) }
        val totalWidthPx = with(density) { boxWidth.toPx() }
        val totalHeightPx = with(density) { boxHeight.toPx() }
        val loupeWidthPx = with(density) { loupeWidth.toPx() }
        val loupeHeightPx = with(density) { loupeHeight.toPx() }
        fun clampSpot(spot: Offset) = Offset(spot.x.coerceIn(0f, maxOf(totalWidthPx - loupeWidthPx, 0f)),
            spot.y.coerceIn(0f, maxOf(totalHeightPx - loupeHeightPx, 0f)))
        val placed = placement?.takeIf { it.valid }?.let {
            clampSpot(Offset(it.x * totalWidthPx - loupeWidthPx / 2, it.y * totalHeightPx - loupeHeightPx / 2))
        }
        val automatic = with(density) {
            val width = loupeWidthPx
            val height = loupeHeightPx
            val totalWidth = totalWidthPx
            val totalHeight = totalHeightPx
            val margin = LoupeMargin.toPx()
            val top = (topInset + LoupeMargin).toPx()
            val middle = minOf(top + height + margin, totalHeight * .62f - height)
            val point = Offset(targetView.x * totalWidth, targetView.y * totalHeight)
            val keepOut = 24.dp.toPx()
            val local = obstacles.map { it.translate(-origin.x, -origin.y) }
            fun fits(position: Offset): Boolean {
                val rect = Rect(position, Size(width, height))
                if (rect.left < 0f || rect.top < 0f || rect.right > totalWidth || rect.bottom > totalHeight) return false
                if (rect.inflate(keepOut).contains(point)) return false
                return local.none { it.overlaps(rect) }
            }
            val left = margin
            val right = totalWidth - width - margin
            val opposite = if (targetView.x < .5f) right else left
            val same = if (targetView.x < .5f) left else right
            val candidates = listOf(Offset(opposite, top), Offset(same, top), Offset(opposite, middle), Offset(same, middle))
            autoSpot?.takeIf { fits(it) } ?: candidates.firstOrNull { fits(it) } ?: candidates.first()
        }
        if (placed == null && automatic != autoSpot) autoSpot = automatic
        val chosen = dragSpot ?: placed ?: automatic
        val currentPlacementChange by rememberUpdatedState(onPlacementChange)
        val moveBy: (Offset) -> Unit = { amount -> dragSpot = clampSpot((dragSpot ?: chosen) + amount) }
        val finishMove: () -> Unit = {
            dragSpot?.let { spot ->
                currentPlacementChange(LoupePlacement((spot.x + loupeWidthPx / 2) / totalWidthPx,
                    (spot.y + loupeHeightPx / 2) / totalHeightPx))
            }
            dragSpot = null
        }
        // Moves the magnified point by a distance on screen.
        val moveTarget: (Offset) -> Unit = { amount ->
            val minX = .5f - .5f * horizontal
            val minY = .5f - .5f * vertical
            target = Offset((target.x + amount.x / totalWidthPx * horizontal).coerceIn(minX, 1f - minX),
                (target.y + amount.y / totalHeightPx * vertical).coerceIn(minY, 1f - minY))
        }

        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.visualBoundsInRoot().topLeft })
        // The dashed box marks the magnified area in the viewfinder.
        Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            val surfaceWidthPx = size.width / horizontal
            val scale = if (status.surfaceRawWidth > 0f) surfaceWidthPx / status.surfaceRawWidth else 0f
            val center = Offset(shownCenter.x * size.width, shownCenter.y * size.height)
            val sourceSize = Size(maxOf(status.sourceWidth * scale, 8.dp.toPx()), maxOf(status.sourceHeight * scale, 6.dp.toPx()))
            val source = Rect(center - Offset(sourceSize.width / 2, sourceSize.height / 2), sourceSize)
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
            drawRect(Color.White, source.topLeft, source.size, style = Stroke(1.2.dp.toPx(), pathEffect = dash))
            val loupeRect = Rect(chosen, Size(loupeWidth.toPx(), loupeHeight.toPx()))
            val anchor = Offset(loupeRect.center.x.coerceIn(source.left, source.right),
                loupeRect.center.y.coerceIn(source.top, source.bottom))
            val corner = Offset(if (abs(loupeRect.left - anchor.x) < abs(loupeRect.right - anchor.x)) loupeRect.left else loupeRect.right,
                if (abs(loupeRect.top - anchor.y) < abs(loupeRect.bottom - anchor.y)) loupeRect.top else loupeRect.bottom)
            drawLine(Color.White.copy(alpha = .55f), anchor, corner, 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        }
        // While focusing manually, dragging the marked area moves the magnified point across the viewfinder.
        // After tap to focus, the focus target itself is dragged instead.
        if (manualActive) {
            val handleSize = 56.dp
            val handlePx = with(density) { handleSize.toPx() }
            val handleCenter = Offset(shownCenter.x * totalWidthPx, shownCenter.y * totalHeightPx)
            Box(Modifier.offset(with(density) { (handleCenter.x - handlePx / 2).toDp() },
                    with(density) { (handleCenter.y - handlePx / 2).toDp() })
                .size(handleSize)
                .languageSemantics { language -> contentDescription = language.text("Focus point") }
                .pointerInput(horizontal, vertical, totalWidthPx, totalHeightPx) {
                    detectTapGestures { moveTarget(it - Offset(handlePx / 2, handlePx / 2)) }
                }
                .pointerInput(horizontal, vertical, totalWidthPx, totalHeightPx) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        moveTarget(drag)
                    }
                })
        }

        val shape = RoundedCornerShape(LoupeCorner)
        val zoomPx = zoom.screenPixelsPerRawPixel
        Box(Modifier.offset(with(density) { chosen.x.toDp() }, with(density) { chosen.y.toDp() })
            .size(loupeWidth, loupeHeight)
            .graphicsLayer { this.alpha = alpha }
            .onGloballyPositioned { bounds = it.visualBoundsInRoot() }
            .drawBehind {
                // A thin dark ring keeps the white border visible over bright scenes.
                drawRoundRect(Color.Black.copy(alpha = .45f), Offset(-1.5.dp.toPx(), -1.5.dp.toPx()),
                    Size(size.width + 3.dp.toPx(), size.height + 3.dp.toPx()),
                    CornerRadius((LoupeCorner + 1.5.dp).toPx()), style = Stroke(1.5.dp.toPx()))
            }
            .border(1.5.dp, Color.White.copy(alpha = .92f), shape)
            .clip(shape)
            .languageSemantics { language ->
                contentDescription = language.text("Focus loupe")
                stateDescription = zoom.label
                role = Role.Image
                onClick(language.text("Loupe zoom")) { onZoomChange(zoom.next()); true }
            }
            .pointerInput(zoom, tapVisible) {
                detectTapGestures(onDoubleTap = { onZoomChange(zoom.next()) },
                    onLongPress = { if (tapVisible) pinned = !pinned })
            }
            .pointerInput(zoomPx, status.surfaceRawWidth, status.surfaceRawHeight, horizontal, vertical) {
                detectDragGestures(onDragStart = { if (tapVisible) pinned = true }) { change, drag ->
                    change.consume()
                    if (status.surfaceRawWidth <= 0f || status.surfaceRawHeight <= 0f) return@detectDragGestures
                    // Dragging moves the magnified image under the finger.
                    val dx = drag.x / zoomPx / status.surfaceRawWidth
                    val dy = drag.y / zoomPx / status.surfaceRawHeight
                    val minX = .5f - .5f * horizontal
                    val minY = .5f - .5f * vertical
                    target = Offset((target.x - dx).coerceIn(minX, 1f - minX), (target.y - dy).coerceIn(minY, 1f - minY))
                }
            }) {
            Box(Modifier.align(Alignment.Center).requiredSize(userWidth, userHeight).graphicsLayer { rotationZ = rotation }) {
                LoupeChrome(
                    magnification = if (status.surfaceRawWidth > 0f && surfaceBounds != null && surfaceBounds.width > 0f)
                        zoomPx * status.surfaceRawWidth / surfaceBounds.width else 0f,
                    tapMode = tapVisible && !manualActive, pinned = pinned, phase = tapStatus.phase,
                    meter = status.meter,
                    hideProgress = if (hiding) hideProgress.value else null,
                    unavailable = unavailable,
                    viewAspect = if (sideways) boxHeight / boxWidth else boxWidth / boxHeight,
                    mapPoint = if (sideways) rotatedPoint(shownCenter, rotation) else shownCenter,
                    onZoom = { onZoomChange(zoom.next()) },
                    onClose = { if (tapVisible) { tapVisible = false; pinned = false } else manualDismissed = true },
                    onMove = { local ->
                        // The chrome is rotated with the device; turn the drag back into screen directions.
                        val angle = Math.toRadians(rotation.toDouble())
                        val screen = Offset((local.x * cos(angle) - local.y * sin(angle)).toFloat(),
                            (local.x * sin(angle) + local.y * cos(angle)).toFloat())
                        if (tapVisible) pinned = true
                        moveBy(screen)
                    },
                    onMoveEnd = finishMove,
                    onResetPlacement = { dragSpot = null; currentPlacementChange(null) },
                )
            }
        }

        // Lets other viewfinder overlays move out of the loupe's way.
        LaunchedEffect(bounds) { currentBounds(bounds) }
        val radiusFraction = with(density) { LoupeCorner.toPx() } / maxOf(with(density) { loupeWidth.toPx() }, 1f)
        LaunchedEffect(bounds, surfaceBounds, target, zoom, peaking, alpha) {
            val loupe = bounds
            val surface = surfaceBounds
            if (loupe == null || surface == null || surface.width <= 0f || surface.height <= 0f) {
                currentRequest(null)
                return@LaunchedEffect
            }
            currentRequest(FocusLoupeRequest(
                targetX = target.x, targetY = target.y,
                rectLeft = (loupe.left - surface.left) / surface.width, rectTop = (loupe.top - surface.top) / surface.height,
                rectWidth = loupe.width / surface.width, rectHeight = loupe.height / surface.height,
                cornerRadius = radiusFraction, widthPx = loupe.width, heightPx = loupe.height,
                zoom = zoomPx, peaking = peaking, opacity = alpha,
            ))
        }
    }
}

// Mini-map coordinates in the rotated chrome for a viewport point.
private fun rotatedPoint(point: Offset, rotation: Float): Offset =
    if (rotation > 0f) Offset(point.y, 1f - point.x) else Offset(1f - point.y, point.x)

@Composable
private fun LoupeChrome(
    magnification: Float,
    tapMode: Boolean,
    pinned: Boolean,
    phase: TapMeteringPhase,
    meter: FocusMeterState,
    hideProgress: Float?,
    unavailable: Boolean,
    viewAspect: Float,
    mapPoint: Offset,
    onZoom: () -> Unit,
    onClose: () -> Unit,
    onMove: (Offset) -> Unit,
    onMoveEnd: () -> Unit,
    onResetPlacement: () -> Unit,
) {
    val level = meter.level
    val move by rememberUpdatedState(onMove)
    val moveEnd by rememberUpdatedState(onMoveEnd)
    val resetPlacement by rememberUpdatedState(onResetPlacement)
    Box(Modifier.fillMaxSize()) {
        // Crosshair at the focus point.
        Canvas(Modifier.align(Alignment.Center).size(14.dp)) {
            val color = Color.White.copy(alpha = .75f)
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
        }
        if (unavailable) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)), contentAlignment = Alignment.Center) {
                Text("Loupe needs the RAW viewfinder", color = CameraColors.Text, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Row(Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = .62f))
            .pointerInput(Unit) { detectTapGestures { onZoom() } }
            .padding(horizontal = 7.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (magnification > 0f) Text(String.format(Locale.ROOT, "%.1f×", magnification), translate = false,
                color = CameraColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp)
            if (tapMode) {
                when (phase) {
                    TapMeteringPhase.SUCCESS -> {
                        CameraIcon(com.unspektrawesome.R.drawable.ic_sleek_check, Modifier.size(10.dp), CameraColors.Accent)
                        Text("Focus confirmed", color = CameraColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TapMeteringPhase.FAILED -> Text("Focus failed", color = CameraColors.FocusFailed, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TapMeteringPhase.TIMED_OUT, TapMeteringPhase.UNSUPPORTED -> Text("Focus not confirmed in time",
                        color = CameraColors.Text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else -> Text("Focusing", color = CameraColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Grip for moving the loupe window; a double tap returns it to automatic placement.
        Box(Modifier.align(Alignment.TopCenter).size(56.dp, 26.dp)
            .languageSemantics { language ->
                contentDescription = language.text("Move loupe")
                role = Role.Button
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { resetPlacement() }) }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { moveEnd() }, onDragCancel = { moveEnd() }) { change, drag ->
                    change.consume()
                    move(drag)
                }
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(28.dp, 4.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f))
                .border(.5.dp, Color.White.copy(alpha = .35f), CircleShape))
            Box(Modifier.size(24.dp, 2.dp).clip(CircleShape).background(Color.White.copy(alpha = .85f)))
        }
        Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top) {
            if (pinned || !tapMode) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f))
                    .languageSemantics { language ->
                        contentDescription = language.text("Close")
                        role = Role.Button
                    }
                    .pointerInput(Unit) { detectTapGestures { onClose() } }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(9.dp)) {
                        drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), 1.6.dp.toPx(), StrokeCap.Round)
                        drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), 1.6.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            // Where the loupe is looking within the frame.
            val mapHeight = 26.dp
            val mapWidth = (mapHeight * viewAspect.coerceIn(.4f, 2.5f))
            Canvas(Modifier.size(mapWidth, mapHeight)) {
                drawRoundRect(Color.Black.copy(alpha = .45f), cornerRadius = CornerRadius(3.dp.toPx()))
                drawRoundRect(Color.White.copy(alpha = .7f), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
                val dot = Size(4.dp.toPx(), 3.dp.toPx())
                val center = Offset(mapPoint.x.coerceIn(0f, 1f) * size.width, mapPoint.y.coerceIn(0f, 1f) * size.height)
                drawRect(CameraColors.Accent, center - Offset(dot.width / 2, dot.height / 2), dot)
            }
        }
        // Manual focus shows the focus meter; tap to focus shows how to keep the loupe open.
        val meterState = meter
        val meter = !tapMode
        val hint = tapMode && !pinned
        if (meter || hint) {
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(30.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f))))
                .padding(start = 8.dp, end = 8.dp, bottom = 7.dp),
                verticalAlignment = Alignment.Bottom) {
                if (meter) Row {
                    // The bars stand on the text baseline at capital height, level with FOCUS.
                    Text("FOCUS", color = if (meterState.sharpest) CameraColors.Accent else CameraColors.Text.copy(alpha = .85f),
                        fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .7.sp, lineHeight = 11.sp,
                        modifier = Modifier.alignByBaseline())
                    Spacer(Modifier.width(6.dp))
                    if (meterState.lowDetail) Text("Low detail", color = CameraColors.Muted, fontSize = 9.sp,
                        lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline())
                    else FocusMeter(level, meterState.sharpest, Modifier.height(with(LocalDensity.current) { (9.sp * .72f).toDp() })
                        .alignBy { it.measuredHeight })
                }
                else Text("Hold to pin", color = CameraColors.Text.copy(alpha = .85f), fontSize = 9.sp,
                    lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        hideProgress?.let { progress ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp)
                .background(CameraColors.Accent))
        }
    }
}

/**
 * Ten bars from the softest to the sharpest view seen while focusing this subject. All bars turn lime
 * at the sharpest point; the bars stay dark until focus has moved enough to compare.
 */
@Composable
private fun FocusMeter(level: Float?, sharpest: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.width(58.dp)) {
        val bars = 10
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * (bars - 1)) / bars
        val value = level?.coerceIn(0f, 1f)
        for (index in 0 until bars) {
            val lit = value != null && value >= (index + .5f) / bars
            val color = when {
                !lit -> Color.White.copy(alpha = .25f)
                sharpest -> CameraColors.Accent
                else -> Color.White
            }
            drawRoundRect(color, Offset(index * (barWidth + gap), 0f), Size(barWidth, size.height), CornerRadius(1.dp.toPx()))
        }
    }
}
