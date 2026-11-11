package com.unspektrawesome.ui.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.discovery.*
import com.unspektrawesome.language.LanguageText as Text
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
            contentPadding = PaddingValues(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (progress.running) item(key = "progress") { DiscoveryProgressCard(progress) }
            if (!progress.running) item(key = "introduction") {
                SettingsCard {
                    Text("Find one working profile per lens. Discovery stops testing a lens once a suitable RAW stream is verified. This may take tens of seconds to a few minutes. You can cancel and continue later.",
                        color = CameraColors.Muted, fontSize = 14.sp, lineHeight = 21.sp)
                    Text("RAW10 is preferred, with RAW_SENSOR as a fallback. Automatic profiles use normal or binned resolutions. Manual profiles are kept.",
                        color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            if (message != null) item(key = "message") { LensToolNotice(message) }
            if (!progress.running && report != null) item(key = "summary") { DiscoverySummaryCard(report) }
            if (verified.isNotEmpty()) item(key = "results-heading") {
                SettingsSection(if (progress.running) "Results so far" else "Verified")
            }
            items(verified, key = { "verified:${it.configuration.key}" }) { result -> DiscoveryResultCard(result) }
            if (failures.isNotEmpty()) item(key = "failures-toggle") {
                LensToolAction(if (showFailures) "Hide failed streams" else "Show failed streams", Modifier.fillMaxWidth(),
                    if (showFailures) SettingsSymbol.Collapse else SettingsSymbol.Dropdown) { showFailures = !showFailures }
            }
            if (showFailures) items(failures, key = { "failed:${it.configuration.key}" }) { result -> DiscoveryResultCard(result) }
        }
    }
}

@Composable
private fun DiscoveryProgressCard(progress: LensDiscoveryProgress) {
    SettingsCard {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (progress.cameraCount > 0) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsIconBadge(SettingsSymbol.Update)
                Text("Testing camera {cameraNumber} of {cameraCount}", arguments = mapOf("cameraNumber" to (progress.cameraNumber), "cameraCount" to (progress.cameraCount)),
                    color = CameraColors.Text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
            }
            if (progress.total > 0) LinearProgressIndicator(progress = {
                progress.results.count { it.resolved }.coerceAtLeast(progress.tested).toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(), color = CameraColors.Accent, trackColor = CameraColors.MenuCard)
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CameraColors.Accent,
                trackColor = CameraColors.MenuCard)
            progress.current?.let {
                SettingsDivider()
                Text("Current camera", color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text(it.name, color = CameraColors.Text, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                Text("Lens ID {routeId} · {description}", arguments = mapOf("routeId" to (it.routeId), "description" to (it.description)),
                    color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsIcon(SettingsSymbol.Aperture, Modifier.size(17.dp), CameraColors.Accent)
                Text(progress.phase, color = CameraColors.Text, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
            }
            SettingsDivider()
            val verifiedLenses = progress.results.filter { it.status == LensProbeStatus.VERIFIED }
                .map { it.configuration.sensorId }.distinct().size
            Text("{verifiedLenses} {value2} verified · {value3}", arguments = mapOf("verifiedLenses" to (verifiedLenses),
                "value2" to (if (verifiedLenses == 1) "lens" else "lenses"), "value3" to (testedStreamsLabel(progress.tested))),
                color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun DiscoverySummaryCard(report: LensDiscoveryReport) {
    val profileCount = AutoLensProfiles.recommended(report).size
    SettingsCard("Discovery summary", symbol = SettingsSymbol.Aperture) {
        Text(if (report.verified.isEmpty() && !report.complete)
            "No working configuration has been verified yet. Continue discovery or use manual Lens Profiles."
        else if (report.verified.isEmpty())
            "Automatic discovery could not find a compatible RAW camera configuration. Try the manual Lens Profiles settings; this does not mean the device is unsupported."
        else "$profileCount lens ${if (profileCount == 1) "profile" else "profiles"} · " +
            "${report.verified.size} verified ${if (report.verified.size == 1) "stream" else "streams"}",
            color = if (report.verified.isEmpty()) CameraColors.Text else CameraColors.Accent, fontSize = 14.sp, lineHeight = 21.sp)
        report.message?.let { Text(it, color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp) }
        if (report.verified.isNotEmpty() && profileCount == 0) {
            Text("Only high-resolution streams were verified. Add a profile in Manual Lens Profiles to use one.",
                color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Text(testedStreamsLabel(report.testedCount) + if (report.skippedCount > 0) " · ${report.skippedCount} skipped" else "",
            color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun DiscoveryResultCard(result: LensProbeResult) {
    SettingsCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result.status == LensProbeStatus.VERIFIED) LensToolCheck(true)
            else SettingsIcon(SettingsSymbol.Close, Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(result.configuration.name, color = CameraColors.Text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                Text("Lens ID {routeId} · {description}", arguments = mapOf("routeId" to (result.configuration.routeId), "description" to (result.configuration.description)),
                    color = CameraColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Text(result.status.label, color = if (result.status == LensProbeStatus.VERIFIED) CameraColors.Accent else CameraColors.Muted,
                    fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

private fun testedStreamsLabel(count: Int) = "$count RAW ${if (count == 1) "stream" else "streams"} tested"
