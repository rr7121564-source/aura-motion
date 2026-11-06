package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.model.*
import app.absentmotion.ui.theme.Am

/** Repeat settings live alongside effects. Use the usual layer controls to transform a copy. */
@Composable
internal fun RepeatedCopiesControls(state: EditorState, layer: Layer) {
    val sourceId = layer.copySource ?: layer.id
    val copies = state.scene.repeatedCopies(sourceId)
    val count = copies.size + 1
    val offset = layer.copyOffset
    var expanded by remember(layer.id) { mutableStateOf(count > 1 || offset != null) }
    var failed by remember(layer.id) { mutableStateOf(false) }

    fun edit(change: (Scene) -> Scene) {
        try {
            state.edit(change = change)
            failed = false
        } catch (_: IllegalArgumentException) {
            failed = true
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 6.5.dp, vertical = 3.dp).clip(InspectorShape).background(Am.Field)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ChromeIcons.Duplicate, null, Modifier.size(19.dp), tint = Am.OnDarkDim)
            Text(stringResource(R.string.motion_copies), Modifier.weight(1f).padding(horizontal = 8.dp),
                color = Am.OnDark, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("$count", Modifier.background(Am.PanelInset, InspectorShape).padding(horizontal = 7.dp, vertical = 3.dp),
                color = if (count > 1) Am.Accent else Am.OnDarkDim, fontSize = 12.sp)
            Icon(if (expanded) ChromeIcons.ChevronDown else ChromeIcons.ChevronRight, null,
                Modifier.padding(start = 4.dp).size(17.dp), tint = Am.OnDarkDim)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                .background(Am.PanelInset, InspectorShape).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.repeat_controls_help), color = Am.OnDarkDim, fontSize = 11.sp, lineHeight = 14.sp)
                if (offset == null) {
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
                        InspectorNumber(state, stringResource(R.string.motion_count), count.toFloat(), Modifier.weight(1f),
                            min = 1f, max = 200f, decimals = 0) { value ->
                            edit { it.withRepeatedCopies(sourceId, value.toInt()) }
                        }
                        InspectorIconButton(ChromeIcons.Plus, stringResource(R.string.repeat_add_copy),
                            enabled = count < 200, active = true) {
                            edit { scene -> scene.withRepeatedCopies(sourceId, scene.repeatedCopies(sourceId).size + 2) }
                        }
                    }
                } else {
                    InspectorNumber(state, stringResource(R.string.motion_delay), offset.delayFrames, Modifier.fillMaxWidth()) { value ->
                        edit { scene ->
                            val current = scene.layer(layer.id)?.copyOffset ?: return@edit scene
                            scene.withCopyOffset(layer.id, current.copy(delayFrames = value))
                        }
                    }
                }
                if (count > 1) {
                    val ids = listOf(sourceId) + copies.map { it.id }
                    val selectedIndex = ids.indexOf(layer.id).coerceAtLeast(0)
                    LazyRow(Modifier.fillMaxWidth().height(38.dp),
                        state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                            val label = if (index == 0) stringResource(R.string.repeat_original) else "$index"
                            InspectorSegment(label, id == layer.id, Modifier.widthIn(min = 40.dp).fillMaxHeight().padding(horizontal = 1.dp)) {
                                state.selectedId = id
                            }
                        }
                    }
                }
                if (offset != null) {
                    InspectorAction(stringResource(R.string.motion_detach), PanelIcons.Link, Modifier.fillMaxWidth()) {
                        edit { it.detachRepeatedCopy(layer.id) }
                    }
                }
                if (failed) Text(stringResource(R.string.repeat_edit_failed), color = Am.Warning, fontSize = 12.sp)
            }
        }
    }
}
