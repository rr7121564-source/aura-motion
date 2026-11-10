package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.effects.EffectParam
import app.absentmotion.model.Scene
import app.absentmotion.model.finiteFloat
import app.absentmotion.model.formatNumber
import app.absentmotion.ui.editor.panels.EffectNumbers
import app.absentmotion.ui.theme.Am

internal val Scene.sharedDepthEnabled: Boolean get() = node["trueDepth3D"] == "true"

internal fun EditorState.setSharedDepth(on: Boolean) {
    if (editingGroup || on == scene.sharedDepthEnabled) return
    playing = false
    endGesture()
    edit { Scene(it.node.with("trueDepth3D", "true".takeIf { on })) }
}

private val SceneLightParams = listOf(
    EffectParam("trueLightAzimuth", "", "angle", "135", step = 1f) to R.string.depth_azimuth_label,
    EffectParam("trueLightElevation", "", "angle", "50", step = 1f) to R.string.depth_elevation_label,
    EffectParam("trueLightIntensity", "", "float", "1", 0f, 4f, .01f) to R.string.model_param_light_intensity,
    EffectParam("trueLightAmbient", "", "float", ".35", 0f, 1f, .01f) to R.string.model_param_ambient_light,
)

@Composable
internal fun SceneLightControls(state: EditorState, selected: Boolean, onSelect: () -> Unit) {
    var active by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(6.dp).clip(InspectorShape).background(Am.PanelInset)) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
            Text(stringResource(R.string.depth_light), color = Am.OnDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.depth_light_scope), color = Am.OnDarkDim, fontSize = 11.sp)
        }
        for ((base, label) in SceneLightParams) {
            val param = base.copy(label = stringResource(label))
            val value = state.scene.node[param.id]?.finiteFloat() ?: param.default.toFloat()
            fun select() {
                active = param.id
                onSelect()
            }
            EffectNumbers(state, param, formatNumber(value), enabled = true, active = selected && active == param.id,
                select = ::select) { text, gesture ->
                val next = text.finiteFloat()?.coerceIn(param.min, param.max) ?: return@EffectNumbers
                select()
                state.edit(if (gesture) param.id else null) { Scene(it.node.with(param.id, formatNumber(next))) }
            }
        }
    }
}
