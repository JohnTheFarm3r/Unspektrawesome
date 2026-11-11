package com.unspektrawesome.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import com.unspektrawesome.panorama.GridCaptureState
import com.unspektrawesome.panorama.gridTarget
import com.unspektrawesome.panorama.gridTranspose
import com.unspektrawesome.panorama.gridVector
import com.unspektrawesome.settings.PreviewSlider

@Composable
internal fun CameraPreviewOverlays(
    viewport: CameraArea,
    freeHeight: Dp,
    rotation: Float,
    railHeight: Dp,
    lenses: @Composable () -> Unit,
    exposure: (@Composable () -> Unit)?,
    highlights: (@Composable () -> Unit)?,
    banner: CameraArea?,
    performance: (@Composable () -> Unit)?,
    longShutter: (@Composable (Boolean) -> Unit)?,
    histogram: (@Composable () -> Unit)?,
    aeSwitch: (@Composable () -> Unit)? = null,
    idleGrid: GridCaptureState? = null,
    aeBalance: (@Composable () -> Unit)? = null,
    shadows: (@Composable () -> Unit)? = null,
    controlsAlpha: Float = 1f,
    videoControls: Boolean = false,
    expandedSliders: Set<PreviewSlider> = emptySet(),
    loupe: Rect? = null,
    video: @Composable () -> Unit = {},
) {
    val landscape = abs(rotation) == 90f
    val shutterVisibility = remember { MutableTransitionState(false) }
    shutterVisibility.targetState = longShutter != null
    val shutterTransition = rememberTransition(shutterVisibility, label = "Long shutter visibility")
    val shutterSpacing by shutterTransition.animateFloat(
        transitionSpec = { tween(if (targetState) 220 else 180, easing = FastOutSlowInEasing) },
        label = "Long shutter spacing") { if (it) 1f else 0f }
    var lastShutter by remember { mutableStateOf(longShutter) }
    SideEffect {
        if (longShutter != null) lastShutter = longShutter
        else if (shutterVisibility.isIdle) lastShutter = null
    }
    val controlsModifier = Modifier.graphicsLayer { alpha = controlsAlpha }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Layout(modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }, content = {
        shutterTransition.AnimatedVisibility(visible = { it }, modifier = controlsModifier.layoutId("shutter"),
            enter = fadeIn(tween(220)) + if (landscape)
                expandHorizontally(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.End)
                else expandVertically(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(180)) + if (landscape)
                shrinkHorizontally(tween(180, easing = FastOutSlowInEasing), shrinkTowards = Alignment.End)
                else shrinkVertically(tween(180, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom)) {
            RotatingCameraPanel(rotation) { (longShutter ?: lastShutter)?.invoke(longShutter != null) }
        }
        Box(Modifier.layoutId("lenses").testTag("Lens selection controls")) { lenses() }
        for ((name, content) in listOf("exposure" to exposure, "aeBalance" to aeBalance,
            "highlights" to highlights, "shadows" to shadows)) content?.let {
            Box(controlsModifier.layoutId(name)) {
                if (videoControls) VideoAdjustmentRail(rotation, it) else it()
            }
        }
        aeSwitch?.let { RotatingCameraPanel(rotation, controlsModifier.layoutId("aeSwitch"), content = it) }
        performance?.let { Box(controlsModifier.layoutId("performance")) { it() } }
        histogram?.let { RotatingCameraPanel(rotation, controlsModifier.layoutId("histogram"), content = it) }
        RotatingCameraPanel(rotation, Modifier.layoutId("video").testTag("Video overlay position"), maxWidth = 640.dp) {
            Box { video() }
        }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val bottom = freeHeight.toPx()
        val gap = 8.dp.toPx()
        val obstacles = mutableListOf<CameraArea>()
        banner?.let { obstacles += CameraArea(it.left.dp.toPx(), it.top.dp.toPx(), it.width.dp.toPx(), it.height.dp.toPx()) }
        idleGrid?.takeIf { it.available }?.let { grid ->
            val inverse = gridTranspose(grid.rotation)
            val radians = Math.toRadians(rotation.toDouble())
            val cosine = cos(radians).toFloat()
            val sine = sin(radians).toFloat()
            val guideWidth = (if (landscape) viewport.height else viewport.width).dp.toPx()
            val guideHeight = (if (landscape) viewport.width else viewport.height).dp.toPx()
            val radius = 22.dp.toPx()
            for (index in 0..8) {
                val point = gridVector(inverse, gridTarget(index, grid.width, grid.height, grid.focal))
                if (point[2] <= .05) continue
                val x = (guideWidth * grid.focal / grid.width * point[0] / point[2]).toFloat()
                val y = (guideHeight * grid.focal / grid.height * point[1] / point[2]).toFloat()
                val left = (viewport.left + viewport.width / 2).dp.toPx() + x * cosine - y * sine
                val top = (viewport.top + viewport.height / 2).dp.toPx() + x * sine + y * cosine
                obstacles += CameraArea(left - radius, top - radius, radius * 2, radius * 2)
            }
        }
        val placements = mutableListOf<Pair<Placeable, CameraArea>>()
        fun placeControl(placeable: Placeable?, left: Float, bottom: Float) {
            placeable ?: return
            val area = CameraArea(left, bottom - placeable.height, placeable.width.toFloat(), placeable.height.toFloat())
            placements += placeable to area
            if (area.width > 0f && area.height > 0f) obstacles += area
        }
        val controls = measurables.associateBy { it.layoutId }
        val previewLeft = maxOf(0f, viewport.left.dp.toPx()) + 10.dp.toPx()
        val previewRight = minOf(width.toFloat(), (viewport.left + viewport.width).dp.toPx()) - 10.dp.toPx()
        val previewTop = maxOf(56.dp.toPx(), viewport.top.dp.toPx()) + gap
        val previewBottom = minOf(bottom, (viewport.top + viewport.height).dp.toPx()) - 10.dp.toPx()
        val previewBounds = CameraArea(previewLeft, previewTop, previewRight - previewLeft, previewBottom - previewTop)
        val landscapeSliders = listOf("aeBalance" to PreviewSlider.AE_BALANCE, "exposure" to PreviewSlider.EXPOSURE,
            "shadows" to PreviewSlider.SHADOWS, "highlights" to PreviewSlider.HIGHLIGHTS)
        val recordingRows = (if (landscape) {
            (if (rotation == -90f) landscapeSliders.asReversed() else landscapeSliders).map { listOf(it) }
        } else listOf(
            listOf("aeBalance" to PreviewSlider.AE_BALANCE, "shadows" to PreviewSlider.SHADOWS),
            listOf("exposure" to PreviewSlider.EXPOSURE, "highlights" to PreviewSlider.HIGHLIGHTS),
        )).filter { row -> row.any { controls.containsKey(it.first) } }
        val videoCenter = if (landscape && videoControls)
            (viewport.top + viewport.height / 2).dp.toPx()
            else (previewTop + previewBottom) / 2
        val videoBottom = if (landscape && videoControls)
            minOf(previewBottom, bottom - 12.dp.toPx())
            else previewBottom
        val videoHeight = if (landscape && videoControls)
            2 * minOf(videoCenter - previewTop, videoBottom - videoCenter)
            else videoBottom - previewTop - if (landscape) 96.dp.toPx() else 0f
        val videoStatus = controls.getValue("video").measure(Constraints(
            maxWidth = previewBounds.width.roundToInt().coerceAtLeast(0),
            maxHeight = videoHeight.roundToInt().coerceAtLeast(0)))
        val statusAboveControls = !videoControls && rotation != 0f
        val videoArea = if (videoStatus.width > 0 && videoStatus.height > 0) {
            val left = when (rotation) {
                -90f -> previewLeft
                90f -> previewRight - videoStatus.width
                else -> (previewLeft + previewRight - videoStatus.width) / 2
            }
            val top = when (rotation) {
                180f, -180f -> previewBottom - videoStatus.height
                -90f, 90f -> videoCenter - videoStatus.height / 2f
                else -> previewTop
            }
            CameraArea(left, top, videoStatus.width.toFloat(), videoStatus.height.toFloat()).also {
                if (!statusAboveControls) {
                    placements += videoStatus to it
                    obstacles += it
                }
            }
        } else null
        val controlBottom = if (videoControls && abs(rotation) == 180f && videoArea != null) minOf(bottom - 12.dp.toPx(), videoArea.top - gap)
            else bottom - 12.dp.toPx()
        val controlHeight = (controlBottom - 64.dp.toPx()).roundToInt().coerceAtLeast(0)
        val centerWidth = (width - 144.dp.roundToPx()).coerceAtLeast(48.dp.roundToPx())
        val shutter = controls["shutter"]?.measure(Constraints(
            maxWidth = (if (landscape) centerWidth - 48.dp.roundToPx() - gap.roundToInt()
                else width - 24.dp.roundToPx()).coerceAtLeast(0),
            maxHeight = (if (landscape) controlHeight else controlHeight - 48.dp.roundToPx() - gap.roundToInt()).coerceAtLeast(0)))
        val shutterGap = (gap * shutterSpacing).roundToInt()
        val belowLenses = if (!landscape && shutter != null) shutter.height + shutterGap else 0
        val besideLenses = if (landscape) shutter?.width ?: 0 else 0
        val railEdge = (12.dp + 42.dp).toPx()
        val betweenRails = width - railEdge * 2
        val lensWidth = if (landscape) minOf(centerWidth,
            (betweenRails - besideLenses - 6.dp.toPx() * shutterSpacing).roundToInt().coerceAtLeast(0)) else centerWidth
        val lensControls = controls.getValue("lenses").measure(Constraints(
            maxWidth = lensWidth,
            maxHeight = (controlHeight - belowLenses).coerceAtLeast(0)))
        val centeredLensLeft = (width - lensControls.width) / 2f
        val besideSpace = centeredLensLeft - railEdge
        val shortage = (besideLenses - besideSpace).coerceAtLeast(0f)
        val sideGap = if (shortage > 0f) minOf(gap * shutterSpacing, shortage / 2,
            ((betweenRails - besideLenses - lensControls.width) / 3).coerceAtLeast(0f))
            else minOf(gap * shutterSpacing, (besideSpace - besideLenses).coerceAtLeast(0f) / 2)
        val lensLeft = if (landscape && shutter != null) maxOf(centeredLensLeft, railEdge + besideLenses + sideGap * 2)
            else centeredLensLeft
        placeControl(lensControls, lensLeft, controlBottom - belowLenses)
        // In landscape the panel sits beside the lens buttons. While they are hidden, it moves next to the
        // side rails at the screen edge that is the bottom of the turned phone.
        placeControl(shutter, when {
            landscape && lensControls.width > 0 -> lensLeft - besideLenses - sideGap
            landscape && rotation == 90f -> railEdge + gap
            landscape -> width - railEdge - gap - (shutter?.width ?: 0)
            else -> (width - (shutter?.width ?: 0)) / 2f
        }, controlBottom)
        fun railConstraints(topOccupied: Boolean, paired: Boolean): Constraints {
            val available = if (topOccupied && videoArea != null && !statusAboveControls)
                minOf(controlHeight.toFloat(), controlBottom - videoArea.top - videoArea.height - gap)
                else controlHeight.toFloat()
            val height = if (paired) (available - belowLenses - gap) / 2 else available - belowLenses
            return Constraints(maxWidth = width / 2, maxHeight = minOf(railHeight.roundToPx(), height.roundToInt().coerceAtLeast(0)))
        }
        if (videoControls) {
            var railBottom = controlBottom - belowLenses
            if (lensControls.height > 0) railBottom -= lensControls.height + gap
            val availableWidth = (width - 24.dp.roundToPx()).coerceAtLeast(0)
            for (row in recordingRows.asReversed()) {
                val bothExpanded = row.size == 2 && row.all { controls.containsKey(it.first) && it.second in expandedSliders }
                for ((index, entry) in row.withIndex()) {
                    val measurable = controls[entry.first] ?: continue
                    val opposite = row.getOrNull(1 - index)?.first
                    val availableLength = when {
                        landscape && videoArea != null -> (width - 24.dp.toPx() - videoArea.width - gap).roundToInt()
                        bothExpanded -> (availableWidth - gap.roundToInt()) / 2
                        controls.containsKey(opposite) -> availableWidth - 42.dp.roundToPx() - gap.roundToInt()
                        else -> availableWidth
                    }.coerceAtLeast(0)
                    val rail = measurable.measure(Constraints(
                        maxWidth = minOf(railHeight.roundToPx(), availableLength), maxHeight = 42.dp.roundToPx()))
                    val atLeft = if (landscape) rotation == 90f else index == 0
                    placeControl(rail, if (atLeft) 12.dp.toPx() else width - 12.dp.toPx() - rail.width, railBottom)
                }
                railBottom -= 42.dp.toPx() + gap
            }
        } else {
            val exposureConstraints = railConstraints(rotation == 0f || rotation == -90f, controls.containsKey("aeBalance"))
            val exposureRail = controls["exposure"]?.measure(exposureConstraints)
            val balanceRail = controls["aeBalance"]?.measure(exposureConstraints)
            val toneConstraints = railConstraints(rotation == 0f || rotation == 90f, controls.containsKey("shadows"))
            val highlightRail = controls["highlights"]?.measure(toneConstraints)
            val shadowRail = controls["shadows"]?.measure(toneConstraints)
            placeControl(exposureRail, 12.dp.toPx(), controlBottom - belowLenses)
            placeControl(balanceRail, 12.dp.toPx(), controlBottom - belowLenses - (exposureRail?.height ?: 0) - gap)
            placeControl(highlightRail, width - 12.dp.toPx() - (highlightRail?.width ?: 0), controlBottom - belowLenses)
            placeControl(shadowRail, width - 12.dp.toPx() - (shadowRail?.width ?: 0),
                controlBottom - belowLenses - (highlightRail?.height ?: 0) - gap)
        }
        if (statusAboveControls && videoArea != null) {
            val controlsTop = placements.filter { it.second.width > 0 && it.second.height > 0 }.minOfOrNull { it.second.top } ?: controlBottom
            val statusBottom = minOf(previewBottom, controlsTop - gap)
            val scale = minOf(1f, (statusBottom - previewTop).coerceAtLeast(0f) / videoStatus.height)
            val area = CameraArea((previewLeft + previewRight - videoStatus.width * scale) / 2,
                statusBottom - videoStatus.height * scale, videoStatus.width * scale, videoStatus.height * scale)
            placements += videoStatus to area
            obstacles += area
        }
        // The AE switch and performance readout move out of the focus loupe's way. The histogram does
        // not, because the loupe already avoids it.
        val loupeArea = loupe?.let { CameraArea(it.left - origin.x, it.top - origin.y, it.width, it.height) }
        for (measurable in measurables.filter { it.layoutId in setOf("aeSwitch", "performance", "histogram") }) {
            val bounds = previewBounds
            val placeable = measurable.measure(Constraints(maxWidth = bounds.width.roundToInt().coerceAtLeast(0),
                maxHeight = bounds.height.roundToInt().coerceAtLeast(0)))
            val avoid = if (loupeArea != null && measurable.layoutId != "histogram") obstacles + loupeArea else obstacles
            val area = CameraLayout.overlayArea(bounds, placeable.width.toFloat(), placeable.height.toFloat(),
                avoid, gap, alignEnd = measurable.layoutId == "histogram")
            if (area != null) {
                placements += placeable to area
                obstacles += area
            }
        }
        layout(width, constraints.maxHeight) {
            placements.forEach { (placeable, area) ->
                // Empty controls need an invertible layer when they return.
                val horizontalScale = if (placeable.width > 0 && area.width > 0f) area.width / placeable.width else 1f
                val verticalScale = if (placeable.height > 0 && area.height > 0f) area.height / placeable.height else 1f
                placeable.placeWithLayer(area.left.roundToInt(), area.top.roundToInt()) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = horizontalScale
                    scaleY = verticalScale
                }
            }
        }
    }
}

@Composable
private fun VideoAdjustmentRail(rotation: Float, content: @Composable () -> Unit) {
    val trackRotation = if (rotation == -90f || abs(rotation) == 180f) -90f else 90f
    Layout(content = {
        CompositionLocalProvider(LocalCameraRotation provides (rotation - trackRotation + 540f) % 360f - 180f, content = content)
    }) { measurables, constraints ->
        val rail = measurables.single().measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
        layout(rail.height, rail.width) {
            rail.placeWithLayer((rail.height - rail.width) / 2, (rail.width - rail.height) / 2) {
                rotationZ = trackRotation
            }
        }
    }
}
