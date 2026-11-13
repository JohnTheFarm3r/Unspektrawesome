package com.unspektrawesome.ui.camera

import android.hardware.camera2.CameraManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import com.unspektrawesome.language.LanguageText as Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.CameraRoute
import com.unspektrawesome.camera.session.AvailableVendorKey
import com.unspektrawesome.camera.session.availableVendorKeys
import com.unspektrawesome.language.LocalAppLanguage
import com.unspektrawesome.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VendorOverridesEditor(value: VendorOverrides, route: CameraRoute, onChange: (VendorOverrides) -> Unit) {
    var searching by remember { mutableStateOf<Int?>(null) }
    val errors = value.tags.mapIndexed { index, tag ->
        val duplicate = value.tags.count { it.target == tag.target && it.key.trim() == tag.key.trim() } > 1
        tag.validationError() ?: if (duplicate) "Each key can appear only once per target." else null
    }
    Column(Modifier.fillMaxWidth()) {
        SensorArtSwitchRow("CUSTOM VENDOR OVERRIDES", value.enabled, caption = true,
            toggleLabel = "Custom vendor overrides") { onChange(value.copy(enabled = it)) }
        if (value.enabled) {
            VendorStage(value, errors)
            VendorTextField(value.opMode, "Custom opmode", "Vendor opmode", value.opModeError(), number = 1) {
                onChange(value.copy(opMode = it))
            }
            DiagramHint("0 keeps the regular session. Decimal and hex values such as 4660 or 0x1234 are accepted.")
            value.tags.forEachIndexed { index, tag ->
                fun update(next: VendorTagOverride) = onChange(value.copy(tags = value.tags.toMutableList().apply { set(index, next) }))
                val error = errors[index]
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).sensorArtGroup(error == null).testTag("Vendor tag $index")
                    .padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (index < 2) DiagramNumber(index + 2, active = error == null)
                        Text(if (error == null) "Tag {number} · Ready to apply" else "Tag {number} · Incomplete",
                            arguments = mapOf("number" to index + 1), modifier = Modifier.weight(1f),
                            color = if (error == null) CameraColors.Accent else Diagram.Hint,
                            fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                        SensorArtQuietAction("Remove", Modifier.testTag("Remove vendor tag $index")) {
                            onChange(value.copy(tags = value.tags.filterIndexed { i, _ -> i != index }))
                        }
                    }
                    VendorTextField(tag.key, "Vendor key", "Vendor key $index") { update(tag.copy(key = it)) }
                    DiagramButton("Search keys", Modifier.padding(vertical = 2.dp).testTag("Search vendor keys $index"),
                        symbol = SettingsSymbol.Discover, onClick = { searching = index })
                    Box(Modifier.fillMaxWidth()) {
                        VendorChoice("TARGET", "Vendor target $index", tag.target.label, VendorTagTarget.entries.map { it.label }) { selected ->
                            update(tag.copy(target = VendorTagTarget.entries[selected]))
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        VendorChoice("VALUE TYPE", "Vendor type $index", tag.type?.label ?: "Select type",
                            VendorValueType.entries.map { it.label }, translateValue = tag.type == null) { selected ->
                            update(tag.copy(type = VendorValueType.entries[selected]))
                        }
                    }
                    VendorTextField(tag.value, "Tag value", "Vendor value $index", singleLine = false) { update(tag.copy(value = it)) }
                    if (tag.type?.name?.endsWith("_ARRAY") == true)
                        DiagramHint("Separate array values with commas or spaces. Brackets are optional.", Modifier.padding(vertical = 0.dp))
                    if (error != null) DiagramHint(error)
                }
            }
            DiagramButton("Add tag", Modifier.fillMaxWidth().padding(vertical = 6.dp).heightIn(min = 48.dp),
                enabled = value.tags.size < VendorOverrides.MAX_TAGS, onClick = {
                    onChange(value.copy(tags = value.tags + VendorTagOverride()))
                })
        } else if (value.tags.isNotEmpty() || value.opMode != "0") {
            DiagramHint("Saved opmode and {size} vendor tags are kept while overrides are off.", arguments = mapOf("size" to (value.tags.size)))
        }
    }
    searching?.let { index ->
        val tag = value.tags.getOrNull(index)
        if (tag != null) VendorKeySearch(route, tag.key, tag.target, onClose = { searching = null }) { name ->
            onChange(value.copy(tags = value.tags.toMutableList().apply { set(index, tag.copy(key = name)) }))
            searching = null
        }
    }
}

/** Where the overrides go: the opmode and session tags configure the session, request tags ride on every frame. */
@Composable
private fun VendorStage(value: VendorOverrides, errors: List<String?>) {
    val renderer = LocalAppLanguage.current.renderer
    val indexed = value.tags.withIndex().toList()
    val session = indexed.filter { it.value.target == VendorTagTarget.SESSION }
    val request = indexed.filter { it.value.target == VendorTagTarget.REQUEST }
    val shownSession = session.take(2)
    val shownRequest = request.take(2)
    val sessionHeight = 52 + shownSession.size * 30 + if (session.size > 2) 12 else 0
    val requestHeight = 40 + shownRequest.size * 30 + if (request.size > 2) 12 else 0
    val requestTop = 36
    val bottom = maxOf(28 + sessionHeight, requestTop + requestHeight, 120)
    val opModeOn = value.opModeError() == null && runCatching { value.sessionMode() }.getOrNull() != 0
    val anyError = value.opModeError() != null || errors.any { it != null }
    SensorArtStage((bottom + 34).dp, "Where the overrides go") {
        DiagramDrawing {
            box(12.dp, 28.dp, 118.dp, sessionHeight.dp, 6.dp, Diagram.Ink, fill = SensorArt.Fill)
            box(20.dp, 48.dp, 102.dp, 18.dp, 3.dp, if (opModeOn) CameraColors.Accent else SensorArt.Dim, 1.dp, dashed = !opModeOn,
                fill = if (opModeOn) SensorArt.AccentFill else null)
            shownSession.forEachIndexed { row, entry ->
                val ready = errors[entry.index] == null
                box(20.dp, (72 + row * 30).dp, 102.dp, 26.dp, 3.dp, if (ready) CameraColors.Accent else SensorArt.Dim, 1.dp, dashed = !ready)
            }
            arrow(130.dp, 62.dp, 146.dp, 62.dp, CameraColors.Accent)
            box(160.dp, (requestTop - 8).dp, 96.dp, requestHeight.dp, 5.dp, Color3A, 1.dp, fill = SensorArt.Fill)
            box(156.dp, (requestTop - 4).dp, 96.dp, requestHeight.dp, 5.dp, Diagram.Line, 1.dp, fill = SensorArt.Fill)
            box(152.dp, requestTop.dp, 96.dp, requestHeight.dp, 5.dp, Diagram.Ink, fill = SensorArt.Fill)
            shownRequest.forEachIndexed { row, entry ->
                val ready = errors[entry.index] == null
                box(160.dp, (requestTop + 22 + row * 30).dp, 80.dp, 26.dp, 3.dp, if (ready) CameraColors.Accent else SensorArt.Dim, 1.dp,
                    dashed = !ready, fill = if (ready) SensorArt.AccentFill else null)
            }
            arrow(256.dp, 70.dp, 270.dp, 70.dp, CameraColors.Accent)
            box(284.dp, 46.dp, 40.dp, 32.dp, 0.dp, Diagram.Line, 1.dp, fill = SensorArt.Fill)
            box(279.dp, 51.dp, 40.dp, 32.dp, 0.dp, Diagram.Line, 1.dp, fill = SensorArt.Fill)
            box(274.dp, 56.dp, 40.dp, 32.dp, 0.dp, Diagram.Ink, 1.dp, fill = SensorArt.Fill)
            val legend = (bottom + 16).dp
            line(14.dp, legend, 30.dp, legend, CameraColors.Accent)
            line(116.dp, legend, 132.dp, legend, SensorArt.Dim, dashed = true)
            if (anyError) dot(212.dp, legend, 3.dp, CameraColors.FocusFailed)
        }
        DiagramLabel("Where the overrides go", modifier = Modifier.at(12.dp, 9.dp), maxWidth = 260.dp)
        DiagramText("Camera session", Modifier.at(20.dp, 32.dp), color = CameraColors.Text, size = 10f, weight = FontWeight.SemiBold,
            maxWidth = 88.dp, maxLines = 1)
        DiagramText(renderer.format("opmode {value}", mapOf("value" to value.opMode.short(10))), Modifier.at(26.dp, 51.dp),
            translate = false, mono = true, size = 8.5f, color = if (opModeOn) CameraColors.Accent else Diagram.Hint, maxWidth = 94.dp,
            maxLines = 1)
        SensorArtMarker(1, 112.dp, 40.dp, opModeOn)
        shownSession.forEachIndexed { row, entry -> VendorStageTag(entry.index, entry.value, errors[entry.index] == null, 26.dp, 72 + row * 30, 92.dp) }
        if (session.size > 2) DiagramText(renderer.format("+{count} more", mapOf("count" to session.size - 2)),
            Modifier.at(20.dp, (72 + 2 * 30).dp), translate = false, size = 8.5f, maxWidth = 100.dp, maxLines = 1)
        DiagramText("Each request", Modifier.at(160.dp, (requestTop + 4).dp), color = CameraColors.Text, size = 10f,
            weight = FontWeight.SemiBold, maxWidth = 84.dp, maxLines = 1)
        shownRequest.forEachIndexed { row, entry ->
            VendorStageTag(entry.index, entry.value, errors[entry.index] == null, 166.dp, requestTop + 22 + row * 30, 70.dp)
        }
        if (request.size > 2) DiagramText(renderer.format("+{count} more", mapOf("count" to request.size - 2)),
            Modifier.at(160.dp, (requestTop + 22 + 2 * 30).dp), translate = false, size = 8.5f, maxWidth = 78.dp, maxLines = 1)
        DiagramText("repeats every frame", Modifier.at(158.dp, (requestTop + requestHeight - 13).dp), size = 8f, maxWidth = 88.dp, maxLines = 1)
        DiagramText("RAW frames", Modifier.at(270.dp, 92.dp), color = SensorArt.Soft, size = 9f, maxWidth = 62.dp)
        DiagramText("Ready to apply", Modifier.at(34.dp, (bottom + 10).dp), color = SensorArt.Soft, size = 8.5f, maxWidth = 80.dp)
        DiagramText("Incomplete", Modifier.at(136.dp, (bottom + 10).dp), color = SensorArt.Soft, size = 8.5f, maxWidth = 70.dp)
        if (anyError) DiagramText("Not saved until all are ready", Modifier.at(219.dp, (bottom + 10).dp),
            color = CameraColors.FocusFailed, size = 8.5f, maxWidth = 112.dp)
    }
}

private val Color3A = androidx.compose.ui.graphics.Color(0xFF3A3A3A)

private fun String.short(length: Int) = trim().let { if (it.length > length) it.take(length - 1) + "…" else it }

@Composable
private fun VendorStageTag(index: Int, tag: VendorTagOverride, ready: Boolean, x: Dp, y: Int, width: Dp) {
    val renderer = LocalAppLanguage.current.renderer
    val color = if (ready) CameraColors.Accent else Diagram.Hint
    if (index < 2) SensorArtMarker(index + 2, x + 80.dp, (y - 6).dp, ready)
    DiagramText(renderer.format("Tag {number} · {target}", mapOf("number" to index + 1, "target" to renderer.text(tag.target.label))),
        Modifier.at(x, (y + 2).dp), translate = false, size = 8.5f, color = if (ready) CameraColors.Accent else SensorArt.Soft,
        maxWidth = minOf(width, 76.dp), maxLines = 1)
    val type = tag.type?.label ?: "?"
    DiagramText(if (ready) renderer.format("{type} = {value}", mapOf("type" to type, "value" to tag.value.short(10)))
        else renderer.format("{type} · incomplete", mapOf("type" to type)),
        Modifier.at(x, (y + 13).dp), translate = false, mono = true, size = 8.5f, color = color, maxWidth = width, maxLines = 1)
}

private val VendorTextFieldScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(available.x, 0f)
}

@Composable
private fun VendorTextField(value: String, label: String, tag: String, error: String? = null,
                            singleLine: Boolean = true, number: Int? = null, onChange: (String) -> Unit) {
    SensorArtField(value, onChange, label, modifier = Modifier.fillMaxWidth().testTag(tag)
        .then(if (singleLine) Modifier.nestedScroll(VendorTextFieldScrollConnection) else Modifier),
        singleLine = singleLine, error = error, number = number)
}

@Composable
private fun VendorChoice(label: String, tag: String, value: String, choices: List<String>, translateValue: Boolean = true,
    onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.testTag(tag)) {
        DiagramDropdownField(label, value, null, expanded, translateValue = translateValue,
            onExpand = { expanded = true }, onDismiss = { expanded = false }) {
            choices.forEachIndexed { index, choice ->
                DiagramMenuItem(choice, selected = choice == value) { expanded = false; onSelect(index) }
            }
        }
    }
}

@Composable
private fun VendorKeySearch(route: CameraRoute, initial: String, target: VendorTagTarget,
                            onClose: () -> Unit, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf(initial) }
    val result by produceState<Result<List<AvailableVendorKey>>?>(null, route) {
        value = withContext(Dispatchers.IO) { runCatching {
            availableVendorKeys(context.getSystemService(CameraManager::class.java), route)
        } }
    }
    DiagramDialog("Search vendor keys", onDismiss = onClose, modifier = Modifier.testTag("Vendor key search"),
        buttons = { DiagramButton("Close", accent = true, onClick = onClose) }) {
        VendorTextField(query, "Search by name", "Vendor key query") { query = it }
        DiagramBody("Keys advertised for this camera. You can also enter unlisted keys manually.")
        val keys = result?.getOrNull().orEmpty().filter {
            it.name.contains(query.trim(), ignoreCase = true) && (target != VendorTagTarget.SESSION || it.session)
        }
        when {
            result == null -> CircularProgressIndicator(color = CameraColors.Accent, strokeWidth = 2.dp,
                modifier = Modifier.padding(vertical = 8.dp).size(24.dp))
            result?.isFailure == true -> DiagramNote("Could not read this camera's vendor keys. Manual entry is still available.")
            keys.isEmpty() -> DiagramNote("No matching {value1} keys advertised.", arguments = mapOf("value1" to (target.label.lowercase())))
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(keys, key = { it.name }) { key ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { onSelect(key.name) }
                        .diagramHairline().padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
                        Text(key.name, translate = false, color = CameraColors.Text, fontSize = 12.sp, lineHeight = 16.sp,
                            fontFamily = Diagram.Mono)
                        Text(if (key.session) "Request / Session" else "Request", color = SensorArt.Detail, fontSize = 10.5.sp,
                            lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}
