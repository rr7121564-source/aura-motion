package app.absentmotion.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.effects.EffectParam
import app.absentmotion.media.ModelJoint
import app.absentmotion.media.ModelRigInfo
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import app.absentmotion.ui.theme.Am

@Composable
internal fun ModelRigPanel(state: EditorState, layer: Layer, rig: ModelRigInfo, bones: Boolean) {
    var chosen by remember(layer.id, rig) { mutableIntStateOf(rig.nodes.firstOrNull { it.joint }?.index ?: 0) }
    var picker by remember(layer.id, bones) { mutableStateOf(false) }
    var allNodes by remember(layer.id) { mutableStateOf(false) }
    val joint = rig.nodes.firstOrNull { it.index == chosen } ?: rig.nodes.first()
    val labels = listOf(R.string.model_rig_position, R.string.model_rig_rotation, R.string.model_rig_scale)
    val params = if (bones) listOf(
        EffectParam("bone${joint.index}.move", stringResource(labels[0]), "vector3", "0,0,0", -100000f, 100000f, .001f),
        EffectParam("bone${joint.index}.rotate", stringResource(labels[1]), "vector3", "0,0,0", -36000f, 36000f, .1f, unit = "°"),
        EffectParam("bone${joint.index}.scale", stringResource(labels[2]), "vector3", "1,1,1", -100f, 100f, .001f, percent = true),
    ) else listOf(
        EffectParam("modelAnimSpeed", stringResource(R.string.model_rig_speed), "float", "1", -1000f, 1000f, .01f),
        EffectParam("modelAnimOffset", stringResource(R.string.model_rig_offset), "float", "0", -100000f, 100000f, .01f, unit = "s"),
    )
    var activeIndex by remember(layer.id, bones) { mutableIntStateOf(if (bones) 1 else 0) }
    val active = params[activeIndex.coerceIn(params.indices)]
    SideEffect { state.keyTarget = KeyTarget(PropRef.shape(active.id), active.default, active.type) }
    val rest = stringResource(R.string.model_rig_rest)
    if (!bones) {
        val clips = remember(rig, rest) { listOf(rest) + rig.clips.map { "${it.name} · ${formatFixed(it.duration, 2)} s" } }
        val clip = (layer.prop("modelClip")?.firstOrNull()?.toInt() ?: if (rig.clips.isEmpty()) 0 else 1).coerceIn(0, rig.clips.size)
        RigSelector(stringResource(R.string.model_rig_clip), clips.getOrElse(clip) { rest }) { picker = true }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.model_rig_loop), Modifier.weight(1f), color = Am.OnDark, fontSize = 14.sp)
            PanelSwitch((layer.prop("modelAnimLoop")?.firstOrNull() ?: 1f) >= .5f) { on ->
                state.editSelected { it.withProp("modelAnimLoop", "float", if (on) "1" else "0") }
            }
        }
        if (picker) RigPicker(stringResource(R.string.model_rig_clip), clips.mapIndexed { i, title -> i to title }, clip,
            { picker = false }) { index -> state.editSelected { it.withProp("modelClip", "float", "$index") }; picker = false }
    } else {
        RigSelector(stringResource(R.string.model_rig_bone), joint.name) { picker = true }
        val parent = rig.nodes.firstOrNull { it.index == joint.parent }
        Text(stringResource(R.string.model_rig_parent, parent?.name ?: rest), Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            color = Am.OnDarkDim, fontSize = 12.sp)
        if (picker) {
            val hasJoints = remember(rig) { rig.nodes.any(ModelJoint::joint) }
            val nodes = remember(rig, allNodes, hasJoints) { rig.nodes.filter { allNodes || it.joint || !hasJoints } }
            fun depth(node: ModelJoint): Int {
                var parentId = node.parent; var depth = 0
                while (parentId >= 0 && depth < 12) { depth++; parentId = rig.nodes.getOrNull(parentId)?.parent ?: -1 }
                return depth
            }
            RigPicker(stringResource(R.string.model_rig_bone), nodes.map { it.index to ("  ".repeat(depth(it)) + "${it.index + 1}. ${it.name}") }, joint.index,
                { picker = false }, header = {
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.model_rig_all_nodes), Modifier.weight(1f), color = Am.OnDarkDim, fontSize = 12.sp)
                        PanelSwitch(allNodes) { allNodes = it }
                    }
                }) { index -> chosen = index; picker = false }
        }
    }
    params.forEachIndexed { index, param ->
        EffectNumbers(state, param, layer.propText(param.id) ?: param.default, true, index == activeIndex, { activeIndex = index }) { text, gesture ->
            activeIndex = index
            state.editSelected(if (gesture) "model-${param.id}" else null) { it.withProp(param.id, param.type, text) }
        }
    }
    if (bones) {
        Text(stringResource(R.string.model_rig_help), Modifier.padding(10.dp), color = Am.OnDarkDim, fontSize = 12.sp)
        TextButton({ state.editSelected { current -> params.fold(current) { value, param -> value.withProp(param.id, param.type, param.default) } } }) {
            Text(stringResource(R.string.model_rig_reset), color = Am.Accent)
        }
    }
}

@Composable
private fun RigSelector(label: String, value: String, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Am.Field).clickable(onClick = open).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Am.OnDarkDim, fontSize = 12.sp)
            Text(value, color = Am.OnDark, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Icon(app.absentmotion.ui.editor.effects.EffectIcons.Expand, null, Modifier.size(18.dp), tint = Am.OnDarkDim)
    }
}

@Composable
private fun RigPicker(title: String, choices: List<Pair<Int, String>>, selected: Int, close: () -> Unit, header: (@Composable () -> Unit)? = null, pick: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    Dialog(close) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(18.dp)).background(Am.Panel).padding(14.dp)) {
            Text(title, color = Am.OnDark, fontSize = 18.sp)
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp).clip(RoundedCornerShape(8.dp)).background(Am.Field).padding(12.dp),
                singleLine = true, textStyle = TextStyle(color = Am.OnDark, fontSize = 14.sp), cursorBrush = SolidColor(Am.Accent),
                decorationBox = { field -> if (query.isBlank()) Text(stringResource(R.string.model_rig_search), color = Am.OnDarkDim, fontSize = 14.sp); field() })
            header?.invoke()
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(choices.filter { it.second.contains(query, ignoreCase = true) }, key = { it.first }) { (id, name) ->
                    Text(name, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (id == selected) Am.Field else Am.Panel)
                        .clickable { pick(id) }.padding(12.dp), color = if (id == selected) Am.Accent else Am.OnDark, fontSize = 14.sp)
                }
            }
            TextButton(close, Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel), color = Am.Accent) }
        }
    }
}
