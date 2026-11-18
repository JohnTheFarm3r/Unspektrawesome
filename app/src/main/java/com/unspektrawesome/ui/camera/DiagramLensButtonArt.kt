package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.unspektrawesome.camera.CameraDescriptor
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.settings.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** The lens buttons as the camera screen shows them in a profile's capture mode, on the side its lens faces. */
internal data class LensRailPreview(val buttons: List<LensRailButton>, val railWidth: Dp)

/** Lens buttons with [profile] in use, each other lens showing its startup profile or its first one. */
internal fun lensRailPreview(preferences: CameraPreferences, profile: LensProfile, cameras: List<CameraDescriptor>,
    railWidth: Dp, text: (String) -> String): LensRailPreview {
    val facing = cameras.firstOrNull { it.route.routeId == profile.routeId }?.facing
    val side = cameras.filter { it.facing == facing }.distinctBy { it.route.routeId }
    val byRoute = side.associateBy { it.route.routeId }
    val shown = preferences.copy(lensProfileMode = profile.mode, activeLensProfileId = profile.id)
    val startup = preferences.defaultLensProfileId(profile.mode)
    val buttons = LensRail.slots(LensProfiles.visible(shown, side), side).map { slot ->
        val choices = slot.choices
        val chosen = slot.pinned ?: choices.firstOrNull { it.id == profile.id } ?: choices.firstOrNull { it.id == startup } ?: choices.first()
        val camera = byRoute.getValue(chosen.routeId)
        val focal = chosen.focalLength.label(camera)
        LensRailButton(slot.key, camera, chosen.button.customLabel ?: text(focal), focal, chosen.button,
            slot.contains(profile.id), chosen, slot.group)
    }
    return LensRailPreview(buttons, railWidth)
}

/** Width the camera screen of a phone held upright gives the lens buttons. */
@Composable
internal fun phoneLensRailWidth(): Dp {
    val configuration = LocalConfiguration.current
    return (minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp - 144.dp).coerceAtLeast(48.dp)
}

private fun LensButtonMetrics.scaled(scale: Float) = LensButtonMetrics(width * scale, height * scale, fontSize * scale)

private val RAIL_ROOM = 296.dp
private val FACE_MARGIN = 10.dp
private const val VALUE_ROW = 22

/**
 * The lens buttons of the camera screen with the edited profile's button in lime, and that button enlarged with
 * its label, text size, size, shape and spacing marked. The numbers match the rows below. When [focus] names one
 * of the marks 4 to 8, the others are greyed so the part being changed stands out.
 */
@Composable
internal fun LensButtonStage(profile: LensProfile, camera: CameraDescriptor, rail: LensRailPreview, focus: Int? = null) {
    fun lit(number: Int) = focus == null || number !in 4..8 || number == focus
    fun ink(number: Int) = if (lit(number)) CameraColors.Accent else Diagram.Line
    val renderer = LocalAppLanguage.current.renderer
    val pageDensity = LocalDensity.current
    val style = profile.button
    val label = style.customLabel ?: renderer.text(profile.focalLength.label(camera))
    val buttons = rail.buttons
    // Measured with the page's text size, like the camera screen, so long labels shrink the same way.
    val measured = rememberLensButtonMetrics(buttons.map { it.label to it.style } + (label to style), sideways = false)
    val own = measured.last()
    val railMetrics = measured.dropLast(1)
    val layout = lensRailLayout(railMetrics.map { it.width }, buttons.map { it.style.spacing }, rail.railWidth)
    val scale = if (buttons.isEmpty()) 1f else minOf(1f, RAIL_ROOM / maxOf(rail.railWidth, layout.width))
    val railHeight = (railMetrics.maxOfOrNull { it.height } ?: 0.dp) * scale
    val edited = buttons.indexOfFirst { it.profile?.id == profile.id }
    // The rail band keeps room for the largest button, so the drawing keeps its height while a size changes and
    // the controls below it stay under the finger.
    val band = LensButtonStyle.MAX_SIZE.dp * ACTIVE_LENS_SCALE
    val railCenter = 55.dp + band / 2
    val railTop = railCenter - railHeight / 2
    val railBottom = railTop + railHeight
    val bandBottom = railCenter + band / 2
    val detailTop = bandBottom + 56.dp
    val height = detailTop + (VALUE_ROW * 8 + 12).dp
    val railLeft = (Diagram.ArtboardWidth - layout.width * scale) / 2
    val lefts = buildList {
        var x = 0.dp
        railMetrics.forEachIndexed { index, metrics ->
            add(railLeft + x * scale)
            x += metrics.width + (layout.gaps.getOrNull(index) ?: 0.dp)
        }
    }
    // The enlarged button sits in the left half, beside the values.
    val zoom = minOf(1.6f, 104.dp / own.width, 104.dp / own.height)
    val face = own.scaled(zoom)
    val cx = 92.dp
    val cy = detailTop + 86.dp
    val textHeight = (face.fontSize.value * pageDensity.fontScale).dp
    val corner = face.height * when (style.shape) {
        LensButtonShape.CIRCLE, LensButtonShape.PILL -> .5f
        LensButtonShape.ROUNDED -> .28f
        LensButtonShape.SQUARE -> .1f
    } * (1f - .7071f)
    val faceLeft = cx - face.width / 2
    val faceTop = cy - face.height / 2
    val textSizeX = faceLeft - 12.dp
    val sizeY = cy + face.height / 2 + 14.dp
    SensorArtStage(height, "Lens buttons on the camera screen, with the edited button enlarged") {
        DiagramDrawing {
            box(10.dp, (-24).dp, 316.dp, bandBottom + 50.dp, radius = 22.dp, color = Diagram.Line)
            // 8: the gap to the next button, or to the previous one for the last button.
            if (edited >= 0 && buttons.size > 1) {
                val next = if (edited < buttons.lastIndex) edited + 1 else edited - 1
                val first = minOf(edited, next)
                val a = lefts[first] + railMetrics[first].width * scale
                val b = lefts[first + 1]
                val y = railBottom + 10.dp
                val left = minOf(a, b)
                val right = maxOf(a, b, left + 2.dp)
                line(left, y, right, y, ink(8), 1.dp)
                line(left, y - 4.dp, left, y + 4.dp, ink(8), 1.dp)
                line(right, y - 4.dp, right, y + 4.dp, ink(8), 1.dp)
            }
            line(16.dp, detailTop - 12.dp, 320.dp, detailTop - 12.dp, Diagram.Border, 1.dp, dashed = true)
            // 5: text size beside the label, 6: button width below it.
            line(textSizeX, cy - textHeight / 2, textSizeX, cy + textHeight / 2, ink(5), 1.dp)
            line(textSizeX - 3.dp, cy - textHeight / 2, textSizeX + 3.dp, cy - textHeight / 2, ink(5), 1.dp)
            line(textSizeX - 3.dp, cy + textHeight / 2, textSizeX + 3.dp, cy + textHeight / 2, ink(5), 1.dp)
            line(faceLeft, sizeY, faceLeft + face.width, sizeY, ink(6), 1.dp)
            line(faceLeft, sizeY - 4.dp, faceLeft, sizeY + 4.dp, ink(6), 1.dp)
            line(faceLeft + face.width, sizeY - 4.dp, faceLeft + face.width, sizeY + 4.dp, ink(6), 1.dp)
        }
        Text("Camera screen · {mode}", Modifier.at(22.dp, 10.dp),
            arguments = mapOf("mode" to renderer.text(if (profile.mode == LensProfileMode.VIDEO) "Video" else "Photo")),
            color = Diagram.Hint, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, pageDensity.fontScale)) {
            if (buttons.isNotEmpty()) {
                val active = buttons.indexOfFirst { it.active }
                Box(Modifier.at(railLeft - FACE_MARGIN, railTop - FACE_MARGIN)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.padding(FACE_MARGIN)) {
                    LensRailRow(LensRailLayout(layout.gaps.map { it * scale }, layout.width * scale, true), Modifier.height(railHeight)) {
                        buttons.forEachIndexed { index, button ->
                            val stack = if (index == active) buttons.size + 1f
                                else buttons.size - abs(index - active.coerceAtLeast(0)).toFloat()
                            LensButtonFace(button.label, railMetrics[index].scaled(scale), button.style.shape, button.active,
                                Modifier.zIndex(stack).graphicsLayer {
                                    if (button.active) { scaleX = ACTIVE_LENS_SCALE; scaleY = ACTIVE_LENS_SCALE }
                                })
                        }
                    }
                }
            }
            Box(Modifier.at(faceLeft - FACE_MARGIN, faceTop - FACE_MARGIN)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.padding(FACE_MARGIN)) {
                LensButtonFace(label, face, style.shape, active = true)
            }
        }
        DiagramDrawing {
            // 4: the label, 7: the outline.
            callout(cx, cy - textHeight / 2 - 2.dp, faceLeft - 4.dp, faceTop - 10.dp, active = lit(4))
            callout(faceLeft + face.width - corner, faceTop + corner, faceLeft + face.width + 8.dp, faceTop - 10.dp, active = lit(7))
        }
        if (edited >= 0 && style.ownButton) {
            StageNumber(3, Modifier.at(lefts[edited] + railMetrics[edited].width * scale / 2 - 7.5.dp,
                railTop - railHeight * (ACTIVE_LENS_SCALE - 1f) / 2 - 19.dp))
        }
        if (edited >= 0 && buttons.size > 1) {
            val next = if (edited < buttons.lastIndex) edited + 1 else edited - 1
            val first = minOf(edited, next)
            val middle = (lefts[first] + railMetrics[first].width * scale + lefts[first + 1]) / 2
            StageNumber(8, Modifier.at(middle - 7.5.dp, railBottom + 17.dp), lit(8))
        }
        when {
            edited < 0 -> DiagramText("Hidden from the lens buttons. Tick the profile in the list to show it.",
                Modifier.at(22.dp, railBottom + 14.dp), size = 9f, maxWidth = 292.dp)
            !layout.fits -> DiagramText("The buttons scroll sideways on the camera screen.",
                Modifier.at(22.dp, bandBottom + 30.dp), size = 8.5f, maxWidth = 292.dp, maxLines = 1)
        }
        StageNumber(4, Modifier.at(faceLeft - 4.dp - 15.dp, faceTop - 10.dp - 7.5.dp), lit(4))
        StageNumber(5, Modifier.at(textSizeX - 7.5.dp, cy + textHeight / 2 + 5.dp), lit(5))
        StageNumber(6, Modifier.at(faceLeft + face.width + 5.dp, sizeY - 7.5.dp), lit(6))
        StageNumber(7, Modifier.at(faceLeft + face.width + 8.dp, faceTop - 10.dp - 7.5.dp), lit(7))
        @Composable
        fun value(number: Int, caption: String, value: String, translate: Boolean = false, active: Boolean = true) {
            Row(Modifier.at(178.dp, detailTop + ((number - 1) * VALUE_ROW).dp).width(150.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StageNumber(number, Modifier.padding(top = 2.dp), active && lit(number))
                Column(Modifier.weight(1f)) {
                    Text(caption, color = if (focus == number) CameraColors.Text else Diagram.Hint, fontSize = 8.5.sp, lineHeight = 10.5.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(value, translate = translate, color = if (active) CameraColors.Accent else SensorArt.Detail,
                        fontSize = 9.5.sp, lineHeight = 11.5.sp, fontFamily = Diagram.Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        value(1, "Focal length factor", "${profile.focalLength.factor}×")
        value(2, "Lens button label", profile.focalLength.label.label, translate = true, active = style.customLabel == null)
        value(3, "Separate lens button", if (style.ownButton) "On" else "Off", translate = true, active = style.ownButton)
        value(4, "Button label", style.customLabel ?: "Automatic", translate = style.customLabel == null)
        value(5, "Text size", "${style.textSize.roundToInt()}")
        value(6, "Button size", "${style.size.roundToInt()}")
        value(7, "Shape", style.shape.label, translate = true)
        value(8, "Spacing", style.spacing?.let(::spacingText) ?: "Automatic", translate = style.spacing == null)
    }
}

/** Spacing as shown to people: positive values with a plus sign, overlaps as negative values. */
internal fun spacingText(value: Float): String {
    val rounded = value.roundToInt()
    return if (rounded > 0) "+$rounded" else "$rounded"
}
