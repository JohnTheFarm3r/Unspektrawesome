package com.unspektrawesome.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.discovery.*
import com.unspektrawesome.language.LanguageText as Text
import com.unspektrawesome.language.languageSemantics
import com.unspektrawesome.settings.AutoLensProfiles

@Composable
internal fun AutoLensDiscoveryDialog(
    progress: LensDiscoveryProgress,
    report: LensDiscoveryReport?,
    message: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onManualProfiles: () -> Unit,
    modifier: Modifier = Modifier,
    onResume: (() -> Unit)? = null,
) {
    var showFailures by rememberSaveable { mutableStateOf(false) }
    val results = if (progress.running) progress.results else report?.results.orEmpty()
    val verified = results.filter { it.status == LensProbeStatus.VERIFIED }
    val failures = results.filter { it.failed }
    LensToolDialog("Auto Lens Discovery", SettingsSymbol.Discover,
        onDismiss = { if (progress.running) onCancel() else onClose() }, modifier = modifier,
        dismissOnClickOutside = !progress.running, footer = {
            if (progress.running) {
                LensToolAction("Cancel", Modifier.fillMaxWidth(), SettingsSymbol.Close, onClick = onCancel)
            } else {
                if (report != null && !report.complete && onResume != null) {
                    LensToolAction("Continue discovery", Modifier.fillMaxWidth(), SettingsSymbol.Resume,
                        accent = true, onClick = onResume)
                }
                if (report == null) {
                    LensToolAction("Auto Discover / Configure Lenses", Modifier.fillMaxWidth(), SettingsSymbol.Discover,
                        accent = true, onClick = onStart)
                    LensToolActionPair("Manual Lens Profiles", "Done", onManualProfiles, onClose,
                        firstSymbol = SettingsSymbol.Sliders, secondSymbol = SettingsSymbol.Check, secondAccent = true)
                } else {
                    LensToolActionPair("Rescan", "Done", onStart, onClose,
                        firstSymbol = SettingsSymbol.Update, secondSymbol = SettingsSymbol.Check)
                    LensToolAction("Manual Lens Profiles", Modifier.fillMaxWidth(), SettingsSymbol.Sliders, onClick = onManualProfiles)
                }
            }
        }) {
        LazyColumn(Modifier.fillMaxSize().testTag("Lens discovery results"),
            contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (progress.running) {
                item(key = "progress") { DiscoveryProgressCard(progress) }
                item(key = "phases") {
                    Box(Modifier.clearAndSetSemantics {}) { DiscoveryPhaseFlow(progress.phase) }
                }
            }
            if (!progress.running) {
                item(key = "drawing") { DiscoveryIntroStage() }
                item(key = "introduction") {
                    LensToolCard {
                        Text("Find one working profile per lens. Discovery stops testing a lens once a suitable RAW stream is verified. This may take tens of seconds to a few minutes. You can cancel and continue later.",
                            color = Color(0xFFCFCFCF), fontSize = 12.5.sp, lineHeight = 18.sp)
                        Text("RAW10 is preferred, with RAW_SENSOR as a fallback. Automatic profiles use normal or binned resolutions. Manual profiles are kept.",
                            color = Diagram.Hint, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (message != null) item(key = "message") { LensToolNotice(message) }
            if (!progress.running && report != null) item(key = "summary") { DiscoverySummaryCard(report) }
            if (verified.isNotEmpty()) item(key = "results-heading") {
                DiagramSection(if (progress.running) "Results so far" else "Verified", Modifier.padding(top = 0.dp))
            }
            items(verified, key = { "verified:${it.configuration.key}" }) { result -> DiscoveryResultCard(result) }
            if (failures.isNotEmpty()) item(key = "failures-toggle") {
                DiagramButton(if (showFailures) "Hide failed streams" else "Show failed streams",
                    symbol = if (showFailures) SettingsSymbol.Collapse else SettingsSymbol.Dropdown) { showFailures = !showFailures }
            }
            if (showFailures) items(failures, key = { "failed:${it.configuration.key}" }) { result -> DiscoveryResultCard(result) }
        }
    }
}

@Composable
private fun DiscoveryProgressCard(progress: LensDiscoveryProgress) {
    LensToolCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (progress.cameraCount > 0) Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            LensToolBadge(SettingsSymbol.Update)
            Text("Testing camera {cameraNumber} of {cameraCount}", arguments = mapOf("cameraNumber" to (progress.cameraNumber), "cameraCount" to (progress.cameraCount)),
                color = CameraColors.Text, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        }
        val bar = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
        if (progress.total > 0) LinearProgressIndicator(progress = {
            progress.results.count { it.resolved }.coerceAtLeast(progress.tested).toFloat() / progress.total },
            modifier = bar, color = CameraColors.Accent, trackColor = Diagram.Border, strokeCap = StrokeCap.Round,
            gapSize = 0.dp, drawStopIndicator = {})
        else LinearProgressIndicator(modifier = bar, color = CameraColors.Accent, trackColor = Diagram.Border,
            strokeCap = StrokeCap.Round, gapSize = 0.dp)
        progress.current?.let {
            DiscoveryDivider()
            Text("Current camera", color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
            Text(it.name, color = CameraColors.Text, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp))
            Text("Lens ID {routeId} · {description}", arguments = mapOf("routeId" to (it.routeId), "description" to (it.description)),
                color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = Diagram.Mono, modifier = Modifier.padding(top = 2.dp))
        }
        Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsIcon(SettingsSymbol.Aperture, Modifier.size(15.dp), CameraColors.Accent)
            Text(progress.phase, color = CameraColors.Text, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        }
        DiscoveryDivider()
        val verifiedLenses = progress.results.filter { it.status == LensProbeStatus.VERIFIED }
            .map { it.configuration.sensorId }.distinct().size
        Text("{verifiedLenses} {value2} verified · {value3}", arguments = mapOf("verifiedLenses" to (verifiedLenses),
            "value2" to (if (verifiedLenses == 1) "lens" else "lenses"), "value3" to (testedStreamsLabel(progress.tested))),
            color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun DiscoveryDivider() {
    Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(Diagram.StageBorder))
}

@Composable
private fun DiscoverySummaryCard(report: LensDiscoveryReport) {
    val profileCount = AutoLensProfiles.recommended(report).size
    LensToolCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            LensToolBadge(SettingsSymbol.Aperture)
            Text("Discovery summary", color = CameraColors.Text, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        }
        Text(if (report.verified.isEmpty() && !report.complete)
            "No working configuration has been verified yet. Continue discovery or use manual Lens Profiles."
        else if (report.verified.isEmpty())
            "Automatic discovery could not find a compatible RAW camera configuration. Try the manual Lens Profiles settings; this does not mean the device is unsupported."
        else "$profileCount lens ${if (profileCount == 1) "profile" else "profiles"} · " +
            "${report.verified.size} verified ${if (report.verified.size == 1) "stream" else "streams"}",
            color = if (report.verified.isEmpty()) CameraColors.Text else CameraColors.Accent, fontSize = 13.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 7.dp))
        report.message?.let { Text(it, color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (report.verified.isNotEmpty() && profileCount == 0) {
            Text("Only high-resolution streams were verified. Add a profile in Manual Lens Profiles to use one.",
                color = Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text(testedStreamsLabel(report.testedCount) + if (report.skippedCount > 0) " · ${report.skippedCount} skipped" else "",
            color = Diagram.Hint, fontSize = 10.5.sp, lineHeight = 14.sp, fontFamily = Diagram.Mono, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DiscoveryResultCard(result: LensProbeResult) {
    val ok = result.status == LensProbeStatus.VERIFIED
    Row(Modifier.fillMaxWidth().clip(Diagram.ControlShape).background(Diagram.Glass)
        .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(18.dp).languageSemantics { language -> contentDescription = language.text(if (ok) "Verified" else "Failed") },
            contentAlignment = Alignment.Center) {
            if (ok) LensToolCheck(true) else SettingsIcon(SettingsSymbol.Close, Modifier.size(14.dp), Diagram.Hint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Text(result.configuration.name, color = CameraColors.Text, fontSize = 12.5.sp, lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = true))
                Text(result.status.label, color = if (ok) CameraColors.Accent else Diagram.Hint, fontSize = 11.sp, lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium)
            }
            Text("Lens ID {routeId} · {description}", arguments = mapOf("routeId" to (result.configuration.routeId), "description" to (result.configuration.description)),
                color = Diagram.Hint, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = Diagram.Mono)
        }
    }
}

private fun testedStreamsLabel(count: Int) = "$count RAW ${if (count == 1) "stream" else "streams"} tested"
