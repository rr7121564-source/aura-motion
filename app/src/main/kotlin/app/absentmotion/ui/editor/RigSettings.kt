package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.model.*
import app.absentmotion.ui.theme.Am

@Composable
internal fun RigSettingsPanel(state: EditorState, modifier: Modifier) {
    val bone = state.rigBoneId?.let { state.scene.layerAt(it,state.sampleMs) }
    Column(modifier.background(Am.Panel).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rig_bone_settings), Modifier.weight(1f).padding(start = 6.dp), color = Am.OnDark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            InspectorIconButton(PanelIcons.Check,stringResource(R.string.done),active = true) { state.endGesture(); state.rigSettingsOpen = false }
        }
        if (bone == null) {
            Text(stringResource(R.string.rig_choose_bone), color = Am.OnDarkDim, fontSize = 12.sp)
        } else key(bone.id) {
            val options = bone.boneOptions
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(40.dp),horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val names=listOf(R.string.rig_key_rotation,R.string.rig_key_position,R.string.rig_key_scale,R.string.rig_key_target)
                    names.forEachIndexed { i,name -> if(i!=3 || bone.rigPinned) InspectorSegment(stringResource(name),state.rigKeyChannel==i,Modifier.weight(1f).fillMaxHeight()) {
                        state.endGesture();state.rigKeyChannel=i
                    } }
                }
                InspectorAction(stringResource(R.string.a11y_easing),PanelIcons.Easing,Modifier.fillMaxWidth(),state::openRigTween)
                val nameLabel = stringResource(R.string.rig_bone_name)
                Column(Modifier.fillMaxWidth().clip(InspectorShape).background(Am.Field).padding(10.dp)) {
                    Text(nameLabel, color = Am.OnDarkDim, fontSize = 10.sp)
                    BasicTextField(bone.label, state::renameBone, Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = nameLabel },
                        textStyle = TextStyle(color = Am.OnDark,fontSize = 15.sp),cursorBrush = SolidColor(Am.Accent),singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InspectorNumber(state,stringResource(R.string.rig_bone_length),bone.rigLength,Modifier.weight(1f),min = 1f,max = 10000f,onChange = state::setBoneLength)
                    InspectorNumber(state,stringResource(R.string.rig_bone_angle),bone.transform("rotation",0f)[0],Modifier.weight(1f),unit = "°") { angle ->
                        state.edit { it.update(bone.id,state.sampleMs) { b -> b.withRigRotation(b.constrainedAngle(angle),state.rigKeyed) } }
                    }
                }
                RigOption(stringResource(R.string.rig_angle_limits),options.limits) { state.changeBoneOptions { it.copy(limits = !it.limits) } }
                if (options.limits) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InspectorNumber(state,stringResource(R.string.rig_min_angle),options.min,Modifier.weight(1f),unit = "°",min = -3600f,max = options.max) { value -> state.changeBoneOptions { it.copy(min = value) } }
                    InspectorNumber(state,stringResource(R.string.rig_max_angle),options.max,Modifier.weight(1f),unit = "°",min = options.min,max = 3600f) { value -> state.changeBoneOptions { it.copy(max = value) } }
                }
                RigOption(stringResource(R.string.rig_independent_angle),options.independent) { state.changeBoneOptions { it.copy(independent = !it.independent) } }
                RigOption(stringResource(R.string.rig_pin_tip),bone.rigPinned,state::toggleBonePin)
                if (bone.rigPinned) Text(stringResource(R.string.rig_pin_hint),color = Am.OnDarkDim,fontSize = 11.sp,lineHeight = 15.sp)
                RigOption(stringResource(R.string.rig_ik_root),options.stopIK) { state.changeBoneOptions { it.copy(stopIK = !it.stopIK) } }
                RigOption(stringResource(R.string.rig_lock_bone),options.locked) { state.changeBoneOptions { it.copy(locked = !it.locked) } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InspectorAction(stringResource(R.string.rig_reset_bone),PanelIcons.TabRotate,Modifier.weight(1f)) { state.resetRigPose(false) }
                    InspectorAction(stringResource(R.string.rig_reset_all),PanelIcons.Bone,Modifier.weight(1f)) { state.resetRigPose(true) }
                }
                InspectorAction(stringResource(R.string.rig_unbind_artwork),PanelIcons.Unlink,Modifier.fillMaxWidth()) {
                    state.selectedId?.let { id -> state.edit { it.withRigParent(id,null,state.sampleMs) } }
                }
                Text(stringResource(R.string.rig_constraints_hint),color = Am.OnDarkDim,fontSize = 11.sp,lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun RigOption(label: String, on: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(InspectorShape).background(if(on) Am.FieldHi else Am.Field)
        .semantics { toggleableState = if(on) ToggleableState.On else ToggleableState.Off }
        .clickable(role = Role.Checkbox,onClick = action).padding(horizontal = 10.dp),verticalAlignment = Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),color = Am.OnDark,fontSize = 13.sp)
        Icon(PanelIcons.Check,null,Modifier.size(18.dp),tint = if(on) Am.Accent else Am.OnDarkDim.copy(alpha = .2f))
    }
}
