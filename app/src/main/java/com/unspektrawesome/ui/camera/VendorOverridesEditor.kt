package com.unspektrawesome.ui.camera

import android.hardware.camera2.CameraManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unspektrawesome.camera.CameraRoute
import com.unspektrawesome.camera.session.AvailableVendorKey
import com.unspektrawesome.camera.session.availableVendorKeys
import com.unspektrawesome.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun VendorOverridesEditor(value: VendorOverrides, route: CameraRoute, onChange: (VendorOverrides) -> Unit) {
    var searching by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("CUSTOM VENDOR OVERRIDES", color = CameraColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            CameraOverlayToggle(value.enabled, "Custom vendor overrides") { onChange(value.copy(enabled = it)) }
        }
        if (value.enabled) {
            VendorTextField(value.opMode, "Custom opmode", "Vendor opmode", value.opModeError()) {
                onChange(value.copy(opMode = it))
            }
            Text("0 keeps the regular session. Decimal and hex values such as 4660 or 0x1234 are accepted.",
                color = CameraColors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
            value.tags.forEachIndexed { index, tag ->
                fun update(next: VendorTagOverride) = onChange(value.copy(tags = value.tags.toMutableList().apply { set(index, next) }))
                val duplicate = value.tags.count { it.target == tag.target && it.key.trim() == tag.key.trim() } > 1
                val error = tag.validationError() ?: if (duplicate) "Each key can appear only once per target." else null
                Surface(color = androidx.compose.ui.graphics.Color.Transparent, border = BorderStroke(1.dp, CameraColors.MenuBorder),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                        .pageCardBackground(RoundedCornerShape(14.dp)).testTag("Vendor tag $index")) {
                    Column(Modifier.padding(if (LocalCompactSettings.current) 10.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (error == null) "Tag ${index + 1} · Ready to apply" else "Tag ${index + 1} · Incomplete",
                                modifier = Modifier.weight(1f), color = if (error == null) CameraColors.Accent else CameraColors.Muted,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = {
                                onChange(value.copy(tags = value.tags.filterIndexed { i, _ -> i != index }))
                            }, modifier = Modifier.testTag("Remove vendor tag $index")) { Text("Remove", color = CameraColors.Muted, fontSize = 12.sp) }
                        }
                        VendorTextField(tag.key, "Vendor key", "Vendor key $index") { update(tag.copy(key = it)) }
                        CameraOverlayAction("Search keys", modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .testTag("Search vendor keys $index"), onClick = { searching = index })
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.fillMaxWidth()) {
                                VendorChoice("TARGET", "Vendor target $index", tag.target.label, VendorTagTarget.entries.map { it.label }) { selected ->
                                    update(tag.copy(target = VendorTagTarget.entries[selected]))
                                }
                            }
                            Box(Modifier.fillMaxWidth()) {
                                VendorChoice("VALUE TYPE", "Vendor type $index", tag.type?.label ?: "Select type", VendorValueType.entries.map { it.label }) { selected ->
                                    update(tag.copy(type = VendorValueType.entries[selected]))
                                }
                            }
                        }
                        VendorTextField(tag.value, "Tag value", "Vendor value $index", singleLine = false) { update(tag.copy(value = it)) }
                        if (tag.type?.name?.endsWith("_ARRAY") == true) Text("Separate array values with commas or spaces. Brackets are optional.",
                            color = CameraColors.Muted, fontSize = 11.sp)
                        if (error != null) Text(error, color = CameraColors.Muted, fontSize = 11.sp)
                    }
                }
            }
            CameraOverlayAction("Add tag", modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = value.tags.size < VendorOverrides.MAX_TAGS, onClick = {
                    onChange(value.copy(tags = value.tags + VendorTagOverride()))
                })
        } else if (value.tags.isNotEmpty() || value.opMode != "0") {
            Text("Saved opmode and {size} vendor tags are kept while overrides are off.", arguments = mapOf("size" to (value.tags.size)), color = CameraColors.Muted, fontSize = 11.sp)
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

private val VendorTextFieldScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(available.x, 0f)
}

@Composable
private fun VendorTextField(value: String, label: String, tag: String, error: String? = null,
                            singleLine: Boolean = true, onChange: (String) -> Unit) {
    SettingsTextField(value, onChange, label, modifier = Modifier.fillMaxWidth().testTag(tag)
        .then(if (singleLine) Modifier.nestedScroll(VendorTextFieldScrollConnection) else Modifier),
        singleLine = singleLine, error = error)
}

@Composable
private fun VendorChoice(label: String, tag: String, value: String, choices: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.testTag(tag)) {
        SettingsDropdownField(label, value, null, expanded, onExpand = { expanded = true }, onDismiss = { expanded = false }) {
            choices.forEachIndexed { index, choice ->
                SettingsMenuChoice(choice, selected = choice == value) { expanded = false; onSelect(index) }
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
    AlertDialog(onDismissRequest = onClose, containerColor = CameraColors.Background,
        modifier = Modifier.testTag("Vendor key search"),
        title = { CameraWindowBackdrop(); Text("Search vendor keys", color = CameraColors.Text) },
        text = {
            Column(Modifier.background(CameraColors.MenuBackground), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VendorTextField(query, "Search by name", "Vendor key query") { query = it }
                Text("Keys advertised for this camera. You can also enter unlisted keys manually.",
                    color = CameraColors.Muted, fontSize = 12.sp)
                val keys = result?.getOrNull().orEmpty().filter {
                    it.name.contains(query.trim(), ignoreCase = true) && (target != VendorTagTarget.SESSION || it.session)
                }
                when {
                    result == null -> CircularProgressIndicator(color = CameraColors.Accent, modifier = Modifier.size(24.dp))
                    result?.isFailure == true -> Text("Could not read this camera's vendor keys. Manual entry is still available.", color = CameraColors.Muted)
                    keys.isEmpty() -> Text("No matching {value1} keys advertised.", arguments = mapOf("value1" to (target.label.lowercase())), color = CameraColors.Muted)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(keys, key = { it.name }) { key ->
                            TextButton(onClick = { onSelect(key.name) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(key.name, translate = false, color = CameraColors.Text, fontSize = 12.sp)
                                    Text(if (key.session) "Request / Session" else "Request", color = CameraColors.Muted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("Close", color = CameraColors.Accent) } })
}
