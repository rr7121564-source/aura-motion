package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.model.Layer
import app.absentmotion.model.parentCandidates
import app.absentmotion.model.shownName
import app.absentmotion.ui.theme.Am

private val ParentShape = RoundedCornerShape(14.dp)

@Composable
internal fun ParentPicker(
    state: EditorState, selectedIds: Set<Long>, current: Long?, below: Boolean,
    onDismiss: () -> Unit, mixed: Boolean = false, onPick: (Long?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val scene = state.scene
    val choices = remember(scene, selectedIds) { scene.parentCandidates(selectedIds) }
    val positions = remember(scene) { scene.layers.asReversed().mapIndexed { i, layer -> layer.id to i + 1 }.toMap() }
    val found = remember(choices, query) { choices.filter { query.isBlank() || it.shownName().contains(query.trim(), ignoreCase = true) } }
    val choose: (Long?) -> Unit = { onPick(it); onDismiss() }
    Popup(if (below) DropOrRise else ChoicePosition, onDismiss, PopupProperties(focusable = true)) {
        Column(Modifier.width(352.dp).heightIn(max = 480.dp).clip(ParentShape).background(Am.Panel)
            .border(1.dp, Am.MenuLine, ParentShape).padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.parent_picker_title), Modifier.weight(1f).padding(4.dp), color = Am.OnDark, fontSize = 16.sp)
                Box(Modifier.size(40.dp).clickable(onClick = onDismiss), Alignment.Center) {
                    Icon(ChromeIcons.Close, stringResource(R.string.action_close), Modifier.size(20.dp), tint = Am.Caption)
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Am.Field).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(TextIcons.Search, null, Modifier.size(18.dp), tint = Am.Caption)
                BasicTextField(query, { query = it }, Modifier.weight(1f).padding(12.dp), singleLine = true,
                    textStyle = TextStyle(color = Am.OnDark, fontSize = 14.sp), cursorBrush = SolidColor(Am.Accent),
                    decorationBox = { field ->
                        Box { if (query.isEmpty()) Text(stringResource(R.string.parent_picker_search), color = Am.Caption, fontSize = 14.sp); field() }
                    })
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 330.dp).padding(top = 6.dp)) {
                item(key = "none") {
                    ParentRow(null, stringResource(R.string.none), null, current == null && !mixed, state) { choose(null) }
                }
                items(found, key = { it.id }) { layer ->
                    ParentRow(layer, layer.shownName(), stringResource(R.string.parent_picker_layer_number, positions[layer.id] ?: 0),
                        layer.id == current && !mixed, state) { choose(layer.id) }
                }
                if (found.isEmpty() && query.isNotBlank()) item(key = "empty") {
                    Text(stringResource(R.string.parent_picker_empty), Modifier.padding(16.dp), color = Am.Caption, fontSize = 13.sp)
                }
            }
            Text(stringResource(R.string.parent_picker_hint), Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                color = Am.Caption, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun ParentRow(layer: Layer?, title: String, detail: String?, selected: Boolean, state: EditorState, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) Am.FieldHi else Am.Panel)
        .selectable(selected, role = Role.RadioButton, onClick = choose).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(7.dp)).background(Am.Well), Alignment.Center) {
            when {
                layer == null -> Icon(PanelIcons.Unlink, null, Modifier.size(24.dp), tint = Am.Caption)
                layer.kind == "nullobj" -> Icon(ChromeIcons.NullObject, null, Modifier.size(28.dp), tint = Am.OnDark)
                else -> LayerThumbnail(layer, state.engine, Modifier.fillMaxSize().padding(3.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Names wrap instead of clipping: duplicated layer names can be distinguished by preview and timeline position.
            Text(title, color = Am.OnDark, fontSize = 14.sp, lineHeight = 18.sp)
            if (detail != null) Text(detail, color = Am.Caption, fontSize = 11.sp)
        }
        if (selected) Icon(PanelIcons.CheckCircle, null, Modifier.size(22.dp), tint = Am.Accent)
    }
}
