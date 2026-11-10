package app.absentmotion.ui.editor.panels

import app.absentmotion.ui.editor.deskVerticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Layer
import app.absentmotion.model.PropRef
import androidx.compose.ui.res.stringResource
import app.absentmotion.R
import app.absentmotion.ui.editor.SceneLightControls
import app.absentmotion.ui.editor.sharedDepthEnabled
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ui.theme.Am
import app.absentmotion.media.ModelRigs

private val ModelParams = listOf(
    EffectParam("size", "", "float", "300", 1f, 20000f, 1f) to R.string.model_param_size,
    EffectParam("spin", "", "angle", "0", -3600f, 3600f, 1f) to R.string.model_param_auto_rotate,
    EffectParam("lightAngle", "", "angle", "135", -3600f, 3600f, 1f) to R.string.model_param_light_angle,
    EffectParam("ambient", "", "float", "0.35", 0f, 1f, 0.01f) to R.string.model_param_ambient_light,
    EffectParam("intensity", "", "float", "1", 0f, 4f, 0.01f) to R.string.model_param_light_intensity,
)

@Composable
internal fun ModelPanel(state: EditorState, layer: Layer, modifier: Modifier = Modifier) {
    val rigs by ModelRigs.loaded.collectAsState()
    val rig = rigs[layer.node["src"]]
    val tab = state.modelRigTab
    var active by remember { mutableStateOf(ModelParams[0].first) }
    var light by remember { mutableStateOf(false) }
    val global = state.scene.sharedDepthEnabled && !state.scene.depthOrder && !state.editingGroup
    if (tab == 0 || rig == null) SideEffect { state.keyTarget = if (global && light) null else KeyTarget(PropRef.shape(active.id), active.default) }
    Column(modifier.deskVerticalScroll()) {
        if (rig != null) {
            Row(Modifier.fillMaxWidth().padding(6.dp)) {
                listOf(R.string.model_rig_model, R.string.model_rig_animation, R.string.model_rig_bones).forEachIndexed { index, label ->
                    Text(stringResource(label), Modifier.weight(1f).background(if (tab == index) Am.Field else Am.Panel)
                        .clickable { state.modelRigTab = index }.padding(horizontal = 6.dp, vertical = 12.dp), color = if (tab == index) Am.Accent else Am.OnDark, fontSize = 13.sp)
                }
            }
            if (tab != 0) { ModelRigPanel(state, layer, rig, tab == 2); return@Column }
        }
        if (!app.absentmotion.platform.PlatformFeatures.desktop) MixPiecesRow(state, layer)
        for ((plain, label) in if (global) ModelParams.take(2) else ModelParams) {
            val param = plain.copy(label = stringResource(label))
            EffectNumbers(state, param, layer.propText(param.id) ?: param.default, true, active.id == param.id && !(global && light), {
                active = plain
                light = false
            }) { text, gesture ->
                active = plain
                light = false
                state.editSelected(if (gesture) "model-${param.id}" else null) { it.withProp(param.id, "float", text) }
            }
        }
        if (global) SceneLightControls(state, light) { light = true }
        if (app.absentmotion.platform.PlatformFeatures.desktop) MixPiecesRow(state, layer)
    }
}
