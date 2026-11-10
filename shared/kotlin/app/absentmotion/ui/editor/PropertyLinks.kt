package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.i18n.AppText
import app.absentmotion.model.*
import app.absentmotion.platform.BackHandler
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import kotlin.math.roundToInt

@Composable
internal fun PropertyLinkEditor(state: EditorState, ref: PropRef, modifier: Modifier, close: () -> Unit) {
    val layer = state.selected ?: return
    val targets = layer.numericChannels().filter { it.ref == ref }
    if (targets.isEmpty()) return
    var axis by remember(layer.id, ref) { mutableIntStateOf(0) }
    val target = targets.getOrElse(axis) { targets.first() }
    val catalog by state.engine.effects.collectAsState()
    val sources = state.scene.layers.filter { it.copySource == null }
    val current = state.scene.resolvedValueLink(layer.id, target)
    val scroll = rememberScrollState()

    BackHandler(enabled = state.keypad == null, onBack = close)
    key(layer.id, target) {
        var failed by remember { mutableStateOf(false) }
        var sourceId by remember(current) {
            mutableLongStateOf(current?.source ?: sources.firstOrNull { it.id != layer.id }?.id ?: layer.id)
        }
        val source = sources.firstOrNull { it.id == sourceId } ?: sources.firstOrNull() ?: layer
        val channels = source.numericChannels().filterNot { source.id == layer.id && it == target }
        var pickedChannel by remember(source.id, current) { mutableStateOf(current?.channel) }
        val channel = pickedChannel?.takeIf { it in channels } ?: channels.first()
        var multiplier by remember(current) { mutableFloatStateOf(current?.multiply ?: 1f) }
        var offset by remember(current) { mutableFloatStateOf(current?.offset ?: 0f) }
        val fps = state.scene.fps
        var delay by remember(current, fps) { mutableFloatStateOf((current?.delayMs ?: 0) * fps / 1000f) }

        Column(modifier.background(Am.Panel).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(PanelIcons.Link, null, Modifier.size(18.dp), tint = Am.Accent)
                Text(channelTitle(layer, target, targets, catalog), Modifier.weight(1f).padding(horizontal = 7.dp),
                    color = Am.OnDark, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                InspectorIconButton(PanelIcons.Check, stringResource(R.string.motion_link_apply), active = true) {
                    val link = ValueLink(source.id, channel, multiplier, offset, (delay * 1000 / fps).roundToInt())
                    try {
                        state.edit { it.withValueLink(layer.id, target, link) }
                        close()
                    } catch (_: IllegalArgumentException) {
                        failed = true
                    }
                }
            }
            if (failed) {
                Text(stringResource(R.string.link_invalid), Modifier.padding(vertical = 4.dp),
                    color = Am.Warning, fontSize = 11.sp, lineHeight = 14.sp)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (targets.size > 1) {
                    Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        targets.forEachIndexed { index, component ->
                            InspectorSegment(axisTitle(component.axis), component == target, Modifier.weight(1f).fillMaxHeight()) {
                                axis = index
                            }
                        }
                    }
                }
                LinkChoice(stringResource(R.string.motion_source), sources, source, { it.shownName() }) {
                    failed = false
                    sourceId = it.id
                }
                key(source.id) {
                    LinkChoice(stringResource(R.string.motion_channel), channels, channel, { channelTitle(source, it, channels, catalog) }) {
                        failed = false
                        pickedChannel = it
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InspectorNumber(state, stringResource(R.string.link_multiplier), multiplier, Modifier.weight(1f)) { multiplier = it }
                    InspectorNumber(state, stringResource(R.string.link_offset), offset, Modifier.weight(1f)) { offset = it }
                }
                val maxDelay = 3600f * fps
                InspectorNumber(state, stringResource(R.string.motion_delay), delay, Modifier.fillMaxWidth(),
                    min = -maxDelay, max = maxDelay) { delay = it }
                if (current != null) {
                    InspectorAction(stringResource(R.string.motion_unlink), PanelIcons.Link, Modifier.fillMaxWidth()) {
                        state.edit { it.withoutValueLink(layer.id, target) }
                        close()
                    }
                }
                Text(stringResource(R.string.motion_link_help), color = Am.OnDarkDim, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun <T> LinkChoice(label: String, values: List<T>, selected: T, title: (T) -> String, choose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(InspectorShape).background(Am.Field)
                .clickable(role = Role.Button) { open = true }.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Am.OnDarkDim, fontSize = 10.sp, maxLines = 1)
                Text(title(selected), color = Am.OnDark, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(ChromeIcons.ChevronDown, null, Modifier.size(18.dp), tint = Am.OnDarkDim)
        }
        Presence(open) {
            MenuPopup(DropOrRise, { open = false }) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(values) { value ->
                        MenuRow(title(value), trailing = PanelIcons.Check.takeIf { value == selected }) {
                            choose(value)
                            open = false
                        }
                    }
                }
            }
        }
    }
}

internal fun Layer.hasValueLink(ref: PropRef): Boolean =
    element(ref)?.children?.any { it.name == "trueValueLink" } == true

private fun axisTitle(index: Int): String = listOf("X", "Y", "Z", "W").getOrElse(index) { "${index + 1}" }

private fun channelTitle(layer: Layer, channel: ValueChannel, channels: List<ValueChannel>, catalog: EffectCatalog): String {
    val ref = channel.ref
    val transform = when (ref) {
        PropRef.Location -> R.string.true_position
        PropRef.Rotation -> R.string.true_rotation
        PropRef.Scale -> R.string.true_scale
        PropRef.Opacity -> R.string.true_opacity
        PropRef.Pivot -> R.string.link_anchor
        PropRef.Tilt -> R.string.link_tilt
        PropRef.Skew -> R.string.link_skew
        else -> null
    }
    val name = if (ref.kind == PropRef.Kind.Effect) {
        val effect = layer.effects.getOrNull(ref.effectIndex)?.get("id")?.let(catalog::find)
        val parameter = effect?.params?.firstOrNull { it.id == ref.name }?.label ?: ref.name
        "${ref.effectIndex + 1}. ${effect?.name ?: "FX"} · $parameter"
    } else {
        transform?.let { AppText.get(it) } ?: ref.name
    }
    val axis = if (channels.any { it.ref == ref && it.axis > 0 }) " ${axisTitle(channel.axis)}" else ""
    return name + axis
}
