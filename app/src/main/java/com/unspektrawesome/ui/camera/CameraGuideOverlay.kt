package com.unspektrawesome.ui.camera

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal enum class CameraGuideStep {
    OPEN_CONTROLS, CHOOSE_LOOK, REORDER_CARDS, CLOSE_CONTROLS, PREVIEW, EXPOSURE,
    AUTO_EXPOSURE, AE_BALANCE, SHADOWS, HIGHLIGHTS, MANUAL_CONTROLS, FOCUS_METERING,
    TOP_TOOLS, CREATE_PROFILE, SAVE_PROFILE, SETTINGS,
}

internal val CAMERA_GUIDE_STEPS = CameraGuideStep.entries.size
internal const val CAMERA_GUIDE_REVISION = 61

private data class CameraGuidePage(val title: String, val description: String)

@Composable
internal fun CameraGuideOverlay(
    step: CameraGuideStep,
    dockHeight: Dp,
    captureLift: Dp,
    actionsHeight: Dp,
    targetBounds: Rect? = null,
    menuOpen: Boolean = false,
    originInRoot: Offset = Offset.Zero,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    val pages = mapOf(
        CameraGuideStep.OPEN_CONTROLS to CameraGuidePage("Swipe up for spektrafilm",
            "Swipe up on the viewfinder to reveal the film controls below. The preview stays undimmed while you adjust your look. You can also tap Show controls in this guide." + "\n\n" + CAPTURE_MODE_GESTURE_GUIDE),
        CameraGuideStep.CHOOSE_LOOK to CameraGuidePage("Choose a look",
            "Swipe cards sideways and tap to choose a look. On FORMAT and EFFECTS cards, swipe up or down for strength; double-tap to reset. FORMAT controls grain and EFFECTS controls diffusion.\n\nPROCESSING contains spektrafilm, Start brightness, Chroma Denoise and Hot Pixel Removal for photos. Tap to toggle, swipe adjustable cards for strength and double-tap to reset. Photo Hot Pixel Removal starts on to remove isolated bright sensor pixels from exports.\n\nEXPOSURE adjusts digital brightness in your look. Unspektra AE adjusts sensor exposure and suspends those digital controls. Video has separate processing settings and its own guide."),
        CameraGuideStep.REORDER_CARDS to CameraGuidePage("Arrange tabs and cards",
            "Hold a tab title for 0.5 seconds or a card for ${LOOK_CARD_HOLD_MILLIS / 1_000f} seconds. When the arrows appear, drag left or right. Cross halfway over a neighbor to swap, or hold near an edge to keep scrolling. Release to save.\n\nTabs can move to any position. Each category also remembers its card order; Disabled stays first. Your selected tab, look and strength stay unchanged. Moving before the hold finishes stays normal browsing.\nTry a tab or card below, or tap Next."),
        CameraGuideStep.CLOSE_CONTROLS to CameraGuidePage("Swipe down to close",
            "Swipe down on the viewfinder to hide the cards and return to the camera controls. A small spinner on the thumbnail means a photo is saving; a brief lime checkmark confirms it is saved.\n\nWith Process now, tap the thumbnail to open your last photo. With Later in Gallery, tapping opens Gallery. Hold the thumbnail for 0.5 seconds to open spektrafilm Gallery in either mode. Errors and full queues show a message with an action."),
        CameraGuideStep.PREVIEW to CameraGuidePage("Preview with VF-S",
            "Tap VF-S to turn spektrafilm off or on in the live preview. Your saved photo settings stay the same.\n\nZebra and the histogram follow the preview: before spektrafilm with VF-S off and after your look with VF-S on. Focus peaking temporarily replaces your look with a black-and-white preview. Close manual FOCUS or switch autofocus peaking off to restore your look."),
        CameraGuideStep.EXPOSURE to CameraGuidePage("Sensor exposure with EV",
            "Tap EV on the left to open its slider, then slide to adjust camera exposure. Positive values brighten; negative values darken. Double-tap a value in the open slider to reset to zero. EV works with automatic exposure, including Unspektra AE priority modes, on supported lenses. In full manual exposure it shows M and is unavailable.\n\nEV, AE BAL, SH and HC start as circular buttons. Hold still anywhere on an open slider for ${RAIL_COLLAPSE_HOLD_MILLIS / 1_000f} seconds to close it without changing its value. Each slider remembers whether it is open across lens changes, restarts and updates."),
        CameraGuideStep.AUTO_EXPOSURE to CameraGuidePage("Choose your auto exposure",
            "Unspektra AE is the default exposure engine. It uses the film engine's metering to adjust shutter speed and ISO. Existing saved engine choices are kept. Open the gear, then General > Auto exposure to choose an engine and Center weighted or Median metering. Android uses the camera's exposure engine and remains the fallback for cameras without manual sensor control.\n\nUnspektra AE settles gradually as the scene changes. EV adjustments and metering taps respond quickly. Tapping favors your subject while keeping the whole scene in the reading. Automatic digital exposure in the look is suspended, including for saved photos.\n\nEnable AE switch overlay in the same settings card to compare the two engines with one tap in the viewfinder. The guide keeps your chosen engine."),
        CameraGuideStep.AE_BALANCE to CameraGuidePage("Balance ISO and shutter",
            "With Unspektra AE active, tap AE BAL above EV. Negative values favor lower ISO; positive values favor faster shutter. Double-tap a value in the open slider to reset to Balanced. Each lens profile remembers its balance. Adjustment is available only when both ISO and shutter are automatic.\n\nOpen Settings > Lens Profiles, edit a profile, and set UNSPEKTRA AE LIMITS to choose the ISO range and fastest and slowest shutter. Changes save automatically. These limits also apply in priority modes; full manual exposure uses the camera's full range.\n\nThe default slowest shutter is about 1/30 second within the lens's supported range. Choose a slower limit when needed, allowing more time between preview frames. Existing custom limits are kept."),
        CameraGuideStep.SHADOWS to CameraGuidePage("Lift shadows with SH",
            "Tap SH above HC on the right, then raise its value to lift dark areas while preserving edges. It adjusts your look before spektrafilm and is saved with custom profiles.\n\nThe result appears in the preview and saved photos. Enable VF-S to see it with your film look. Double-tap a value in the open slider to reset to zero. Hold still on the slider for ${RAIL_COLLAPSE_HOLD_MILLIS / 1_000f} seconds to collapse it."),
        CameraGuideStep.HIGHLIGHTS to CameraGuidePage("Highlight compression",
            "Tap HC on the right. Negative values compress bright tones; positive values brighten them.\n\nSwipe up, open PROCESSING and swipe the Start brightness card to adjust it. Lower values include more tones; HC leaves tones below the threshold unchanged. Both settings are saved with your look and apply to preview and photos.\n\nUnspektra AE meters independently. Disable spektrafilm automatic digital exposure to keep it from compensating for HC. Compression cannot restore fully clipped RAW detail.\n\nDouble-tap a slider value to reset HC to zero. Hold still for ${RAIL_COLLAPSE_HOLD_MILLIS / 1_000f} seconds to collapse it."),
        CameraGuideStep.MANUAL_CONTROLS to CameraGuidePage("Manual and priority controls",
            "Tap ISO for sensitivity, SHUTTER for exposure time, WB for color temperature, or FOCUS for focus distance. Drag a slider to adjust it.\n\nWith Unspektra AE, hold ISO or SHUTTER to lock its current value while the other stays automatic. Hold it again to release that lock. Lock both for manual exposure. Tap a locked control to adjust its value. Adjusting an unlocked exposure control selects full manual exposure. The A button in either exposure slider returns both ISO and shutter to Auto. With Android AE, adjusting either exposure slider selects manual exposure.\n\nA in WB or FOCUS restores that control's automatic mode. In full manual exposure, selecting more than 1 second waits for Apply. RAW preview follows the shutter time, so long exposures update slowly. Cancel on the long-shutter notice interrupts a long exposure. Availability depends on the lens.\n\nA Priority label marks a locked ISO or shutter. The shutter label turns red for exposures slower than 1/30 s in photo mode. Briefly hold FOCUS with its slider closed to return to Auto.\n\nAfter a manual adjustment, hold ISO or SHUTTER to keep that value fixed and return the other to Auto. Manual WB shows the selected temperature color. Briefly hold WB with its slider closed to restore Auto."),
        CameraGuideStep.FOCUS_METERING to CameraGuidePage("Focus and tap metering",
            "While FOCUS is open and manual, peaking highlights fine detail in green on a black-and-white preview. It skips spektrafilm and chroma denoise to reduce power use. Choose Low, Medium or High in the Peaking overlay. Low is stricter; High includes fainter detail with brighter, wider outlines. Close FOCUS to restore your look while keeping manual focus. Settings > Viewfinder can turn manual peaking off. The top peaking button enables it during autofocus, with its own saved strength. Saved photos keep their look and denoise settings.\n\nTap the viewfinder to place AF and AE together. Drag the square for focus or the outer circle for exposure to separate them. Another tap brings them together; holding places only AE. During manual focus, taps move only AE. Metering cannot change exposure when both ISO and shutter are manual.\n\nAF turns green with a check when focus is confirmed, or red with a cross when it fails. A question mark means focus is unconfirmed or unavailable. Targets become translucent after five seconds and hide after a large scene change, keeping their points set. Tap again or move a faded target to restore it. Double-tap AF for continuous focus or AE for automatic scene metering."),
        CameraGuideStep.TOP_TOOLS to CameraGuidePage(
            "Top camera tools",
            when {
                menuOpen -> "Tap JPEG or JPEG+DNG to choose the file format. JPEG+DNG keeps the original RAW alongside the photo. The selected label turns lime. Tap Grid to cycle composition guides and use the timer for delayed photos. Reset look restores defaults after confirmation."
                else -> "Crop: tap a ratio card to apply it. Scroll to Custom to save your own proportions; hold a saved card to edit or delete it. Resolution shows the saved photo size. Crops apply before spektrafilm. Settings > Viewfinder > Framing & controls > Preview behind controls chooses whether large frames extend behind the controls or fit above the dock.\n\nZebra marks deep shadows in blue/black and highlights near clipping in red/white. Histogram toggles the RGB graph; tap the graph to change its size or double-tap to hide it. Both follow VF-S.\n\nThe peaking button between the arrow and Flash enables focus peaking during autofocus. Flash cycles Off, Auto in low light, Always on, and On capture on supported lenses. Sun enables maximum screen brightness in the viewfinder and Gallery, including photo previews. Tap again to follow device brightness in both.\n\nTap the arrow or swipe down in portrait to open the centered quick menu. In landscape, swipe away from the toolbar side toward the viewfinder. If the film panel is open, the first swipe closes it; swipe again to open the quick menu. Swipe back or tap outside to close.\n\nSwipe crop cards sideways and tap a ratio to apply it. Tap Done to close the panel."
            }),
        CameraGuideStep.CREATE_PROFILE to CameraGuidePage("Create a custom profile",
            "In CUSTOM, tap Create profile or New to build a reusable look from your current spektrafilm settings. Select a saved card, then Edit to change it, or Rename or Delete to manage it.\n\nHold a saved card for ${LOOK_CARD_HOLD_MILLIS / 1_000f} seconds, then move it left or right to arrange your profiles. Export lets you select multiple cards and saves their settings and order together. Import accepts backups or multiple older profile files, and lets you choose which cards to add. Matching names become copies."),
        CameraGuideStep.SAVE_PROFILE to CameraGuidePage("Save your look",
            "Tap Save profile, enter a name and confirm Save to keep your look under CUSTOM, including SH and HC. When editing an existing profile, Save updates it; choose Save as a new profile to keep a separate copy.\n\nChroma Denoise reduces colored speckles in saved photos. Open its category, set Enabled, then adjust Strength before saving. Each profile remembers both. Judge denoise in Gallery; it affects saved photos.\n\nApply now closes the editor and keeps your current look without saving a profile."),
        CameraGuideStep.SETTINGS to CameraGuidePage("Camera settings",
            "Tap the gear for Camera Setup. In Lens Profiles, hold a profile for ${LENS_PROFILE_HOLD_MILLIS / 1_000f} seconds, then drag up or down to reorder, including the first and last positions. The viewfinder picker shows profile names.\n\nExport backs up selected lens profiles with their settings, RAW levels, AE limits, startup selection and imported DCP files. Import lets you choose which to restore; matching names become copies. Edit a profile to change its RAW stream, AE limits or calibration. Valid changes save automatically, except RAW layout which uses Apply.\n\nRAW layout override lets advanced users enter the actual width, height and row stride in bytes for a sensor opmode. Blank fields use reported values. Enable it, enter the layout, then tap Apply RAW layout. The requested stream stays HAL-advertised; these controls change how received bytes are unpacked for preview and saved photos. They do not select a sensor mode or enable HFR by themselves. Disable and Apply to return to the reported layout. If a camera mod is disabled, an available RAW stream is used temporarily; the saved format and resolution return when available again.\n\nColor matrix can be disabled or replaced by an imported DCP. Its matrices and hue/saturation calibration apply before spektrafilm; creative look tables and tone curves stay off. Each capture keeps its calibration for export.\n\nViewfinder groups preview quality, framing, control opacity, peaking and overlays. Horizon level turns lime with a light haptic tick when aligned.\n\nGeneral contains Auto exposure, Photo saving, Volume buttons, Language and Guides. In Language, choose a built-in language by its flag and native name. For another language, export the current English JSON template, translate its values and import it, then choose Custom. Missing entries use English. After an update, Export updated custom language keeps your translations and adds new English entries. Choose English in Language settings to return to English. The volume-button action spektrafilm AE cycles automatic digital exposure in your look; use AE switch overlay to change the sensor exposure engine. Replay this tour with General > Guides > Show camera guide."),
    )
    if (abs(LocalCameraRotation.current) == 90f || step == CameraGuideStep.TOP_TOOLS && menuOpen) {
        val page = pages.getValue(step)
        GuideOverlay("CAMERA GUIDE", step.ordinal, CAMERA_GUIDE_STEPS, page.title, page.description,
            targetBounds?.translate(originInRoot), onSkip, onNext, tag = "Camera guide",
            nextLabel = when (step) {
                CameraGuideStep.OPEN_CONTROLS -> "Show controls"
                CameraGuideStep.CLOSE_CONTROLS -> "Hide controls"
                CameraGuideStep.CREATE_PROFILE -> "Open editor"
                CameraGuideStep.SETTINGS -> "Processing guide"
                else -> "Next"
            }, demonstration = when (step) {
                CameraGuideStep.REORDER_CARDS -> ({ CardReorderGuideDemo() })
                CameraGuideStep.FOCUS_METERING -> ({ FocusMeteringGuideDemo() })
                else -> null
            })
        return
    }
    val animation = rememberInfiniteTransition(label = "Camera guide pointer")
    val progress by animation.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1600, easing = LinearOutSlowInEasing)), label = "Guide gesture")
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag("Camera guide")) {
        val aboveQuickMenu = step == CameraGuideStep.TOP_TOOLS && menuOpen && targetBounds != null
        val cardBottom = (dockHeight + captureLift + 92.dp).coerceAtMost(maxHeight * .6f)
        val cardTop = if (step == CameraGuideStep.TOP_TOOLS && !menuOpen && targetBounds != null)
            with(density) { targetBounds.bottom.toDp() } + 12.dp else 12.dp
        val maxCardHeight = (if (aboveQuickMenu) with(density) { requireNotNull(targetBounds).top.toDp() } - 24.dp
            else maxHeight - cardBottom - cardTop).coerceIn(100.dp, 330.dp)
        Canvas(Modifier.fillMaxSize()) {
            val dockTop = size.height - (dockHeight + captureLift).toPx()
            val ink = CameraColors.Accent
            val stroke = 2.dp.toPx()
            if (targetBounds != null) {
                val target = targetBounds.inflate(4.dp.toPx())
                drawRoundRect(ink.copy(alpha = .3f + .5f * (1f - progress)),
                    target.topLeft, target.size, CornerRadius(12.dp.toPx()), style = Stroke(stroke))
                val tip = when (step) {
                    CameraGuideStep.EXPOSURE, CameraGuideStep.AE_BALANCE -> Offset(target.right + 5.dp.toPx(), target.center.y)
                    CameraGuideStep.SHADOWS, CameraGuideStep.HIGHLIGHTS -> Offset(target.left - 5.dp.toPx(), target.center.y)
                    CameraGuideStep.TOP_TOOLS -> if (!menuOpen) Offset(target.center.x, target.bottom + 5.dp.toPx())
                        else Offset(target.center.x, target.top - 5.dp.toPx())
                    else -> Offset(target.center.x, target.top - 5.dp.toPx())
                }
                val direction = when {
                    step in setOf(CameraGuideStep.EXPOSURE, CameraGuideStep.AE_BALANCE) -> Offset(-1f, 0f)
                    step in setOf(CameraGuideStep.SHADOWS, CameraGuideStep.HIGHLIGHTS) -> Offset(1f, 0f)
                    step == CameraGuideStep.TOP_TOOLS && !menuOpen -> Offset(0f, -1f)
                    else -> Offset(0f, 1f)
                }
                val animatedTip = tip - direction * ((1f - progress) * 8.dp.toPx())
                val perpendicular = Offset(-direction.y, direction.x)
                drawLine(ink, animatedTip - direction * 26.dp.toPx(), animatedTip, stroke, StrokeCap.Round)
                for (side in listOf(-1f, 1f)) {
                    drawLine(ink, animatedTip, animatedTip - direction * 7.dp.toPx() + perpendicular * (side * 6.dp.toPx()),
                        stroke, StrokeCap.Round)
                }
            }
            when (step) {
                CameraGuideStep.OPEN_CONTROLS, CameraGuideStep.CLOSE_CONTROLS -> {
                    val x = size.width * .72f
                    val bottom = dockTop - 8.dp.toPx()
                    val top = bottom - 64.dp.toPx()
                    val start = Offset(x, if (step == CameraGuideStep.OPEN_CONTROLS) bottom else top)
                    val end = Offset(x, if (step == CameraGuideStep.OPEN_CONTROLS) top else bottom)
                    drawLine(ink.copy(alpha = .35f), start, end, stroke, StrokeCap.Round)
                    val direction = if (step == CameraGuideStep.OPEN_CONTROLS) 1 else -1
                    drawLine(ink, end, end + Offset(-6.dp.toPx(), direction * 7.dp.toPx()), stroke, StrokeCap.Round)
                    drawLine(ink, end, end + Offset(6.dp.toPx(), direction * 7.dp.toPx()), stroke, StrokeCap.Round)
                    val finger = start + (end - start) * progress
                    drawCircle(CameraColors.Background, 7.dp.toPx(), finger)
                    drawCircle(ink, 7.dp.toPx(), finger, style = Stroke(stroke))
                }
                CameraGuideStep.CHOOSE_LOOK -> {
                    val y = dockTop + (dockHeight - actionsHeight).toPx() - 20.dp.toPx()
                    val start = Offset(size.width * .62f, y)
                    val end = Offset(size.width * .38f, y)
                    drawLine(ink.copy(alpha = .35f), start, end, stroke, StrokeCap.Round)
                    drawLine(ink, end, end + Offset(7.dp.toPx(), -6.dp.toPx()), stroke, StrokeCap.Round)
                    drawLine(ink, end, end + Offset(7.dp.toPx(), 6.dp.toPx()), stroke, StrokeCap.Round)
                    drawCircle(ink, 6.dp.toPx(), start + (end - start) * progress)
                }
                CameraGuideStep.PREVIEW, CameraGuideStep.SETTINGS -> {
                    val x = 8.dp.toPx() + (size.width - 16.dp.toPx()) * if (step == CameraGuideStep.PREVIEW) .3f else .9f
                    val shutterSize = (size.width / 5).coerceIn(58.dp.toPx(), 80.dp.toPx())
                    val y = size.height - (captureLift + actionsHeight).toPx() + shutterSize / 2
                    val radius = 31.dp.toPx() + progress * 7.dp.toPx()
                    drawCircle(ink.copy(alpha = 1f - progress), radius, Offset(x, y), style = Stroke(stroke))
                    val tip = Offset(x, y - 36.dp.toPx() - (1f - progress) * 8.dp.toPx())
                    drawLine(ink, tip - Offset(0f, 24.dp.toPx()), tip, stroke, StrokeCap.Round)
                    drawLine(ink, tip, tip + Offset(-6.dp.toPx(), -7.dp.toPx()), stroke, StrokeCap.Round)
                    drawLine(ink, tip, tip + Offset(6.dp.toPx(), -7.dp.toPx()), stroke, StrokeCap.Round)
                }
                else -> Unit
            }
        }
        Box(
            Modifier.align(if (aboveQuickMenu) Alignment.TopCenter else Alignment.BottomCenter)
                .padding(top = if (aboveQuickMenu) 12.dp else 0.dp, bottom = if (aboveQuickMenu) 0.dp else cardBottom)
                .widthIn(max = 352.dp).fillMaxWidth(.9f)) {
            val displayedStep = step
            val page = pages.getValue(displayedStep)
            GuideCard("CAMERA GUIDE", displayedStep.ordinal, CAMERA_GUIDE_STEPS, page.title,
                page.description, onSkip, onNext,
                Modifier.heightIn(max = maxCardHeight),
                nextLabel = when (displayedStep) {
                    CameraGuideStep.OPEN_CONTROLS -> "Show controls"
                    CameraGuideStep.CLOSE_CONTROLS -> "Hide controls"
                    CameraGuideStep.CREATE_PROFILE -> "Open editor"
                    CameraGuideStep.SETTINGS -> "Processing guide"
                    else -> "Next"
                }, demonstration = when (displayedStep) {
                    CameraGuideStep.REORDER_CARDS -> ({ CardReorderGuideDemo() })
                    CameraGuideStep.FOCUS_METERING -> ({ FocusMeteringGuideDemo() })
                    else -> null
                })
        }
    }
}
