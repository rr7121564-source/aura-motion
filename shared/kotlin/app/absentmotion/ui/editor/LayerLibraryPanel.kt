package app.absentmotion.ui.editor

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.EditorState
import app.absentmotion.editor.KeyTarget
import app.absentmotion.editor.stepBack
import app.absentmotion.media.cachedMediaThumbnail
import app.absentmotion.media.mediaThumbnail
import app.absentmotion.model.*
import app.absentmotion.platform.LocalContext
import app.absentmotion.ui.theme.Am

@Composable
internal fun LayerLibraryPanel(state: EditorState, modifier: Modifier) {
    val layer = state.selected ?: return
    val scene = state.scene
    val active = scene.activeLayerAsset(layer, state.timeMs)
    val onKey = scene.onAssetKey(layer, state.timeMs)
    val inRange = state.timeMs in layer.startTime..layer.endTime
    val canChoose = !layer.isMediaSwitch || (onKey && inRange)
    val busy = state.addingMedia
    val assets = scene.layerAssets(layer)
    val keyFrames = layer.mediaSwitchKeys.map { scene.frameAt(layer.timeOf(it.t)) }
    val previous = keyFrames.lastOrNull { it < state.frame }
    val next = keyFrames.firstOrNull { it > state.frame }

    SideEffect { state.keyTarget = KeyTarget(MediaSwitchRef, active?.uri.orEmpty(), "string") }
    DisposableEffect(state) {
        onDispose { if (state.keyTarget?.ref == MediaSwitchRef) state.keyTarget = null }
    }

    Column(modifier.background(Am.Panel).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(PanelIcons.Back, stringResource(R.string.topbar_back), Modifier.size(44.dp)) { state.stepBack() }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(layer.shownName(), color = Am.OnDark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (active != null) {
                    Text(active.title.ifBlank { active.filename }, color = Am.OnDarkDim,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (busy) {
                Box(Modifier.size(44.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Am.Accent, strokeWidth = 2.dp)
                }
            } else {
                InspectorIconButton(ChromeIcons.Plus, stringResource(R.string.asset_import), active = true) {
                    MediaPicks.openVisual(layer.id)
                }
            }
        }

        if (assets.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text(stringResource(R.string.library_empty_layer), color = Am.OnDarkDim, fontSize = 12.sp)
            }
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(82.dp),
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(assets, key = { it.uri }) { media ->
                    LibraryAssetCard(media, active?.uri == media.uri, !busy && canChoose) {
                        state.edit { it.chooseLayerAsset(layer.id, state.timeMs, media) }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(when {
                    onKey -> R.string.library_at_key
                    !inRange -> R.string.library_outside_layer
                    !canChoose -> R.string.library_create_key_hint_short
                    else -> R.string.library_choose_hint_short
                }),
                Modifier.weight(1f).padding(horizontal = 4.dp),
                color = if (onKey) Am.Accent else Am.OnDarkDim, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2,
            )
            InspectorIconButton(ChromeIcons.SkipBack, stringResource(R.string.library_previous_key), enabled = previous != null && !busy) {
                previous?.let { state.frame = it }
            }
            InspectorIconButton(
                if (onKey) PanelIcons.KeyframeRemove else PanelIcons.Keyframe,
                stringResource(if (onKey) R.string.asset_remove_key else R.string.a11y_add_keyframe),
                enabled = active != null && inRange && !busy,
                active = onKey,
            ) { state.edit { it.toggleAssetKey(layer.id, state.timeMs) } }
            InspectorIconButton(ChromeIcons.SkipForward, stringResource(R.string.library_next_key), enabled = next != null && !busy) {
                next?.let { state.frame = it }
            }
        }
    }
}

@Composable
private fun LibraryAssetCard(media: MediaDeclaration, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    val context = LocalContext.current
    val uri = remember(media.uri) { Uri.parse(media.uri) }
    val bitmap by produceState(cachedMediaThumbnail(uri), uri) { value = mediaThumbnail(context, uri) }
    Column(
        Modifier.clip(InspectorShape).background(if (selected) Am.FieldHi else Am.Field)
            .then(if (selected) Modifier.border(1.dp, Am.Accent, InspectorShape) else Modifier)
            .selectable(selected, enabled = enabled, role = Role.Button, onClick = choose),
    ) {
        Box(Modifier.fillMaxWidth().height(64.dp).background(Am.Well)) {
            val thumbnail = bitmap
            if (thumbnail != null) {
                Image(thumbnail.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(ChromeIcons.MediaTab, null, Modifier.align(Alignment.Center).size(24.dp), tint = Am.OnDarkDim)
            }
            if (media.type.startsWith("video/")) {
                MediaPlayIcon(Modifier.align(Alignment.BottomStart).padding(5.dp).size(14.dp))
            }
            if (selected) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                    .background(Am.Panel, InspectorShape), Alignment.Center) {
                    Icon(PanelIcons.Check, null, Modifier.size(15.dp), tint = Am.Accent)
                }
            }
        }
        Text(media.title.ifBlank { media.filename }, Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            color = Am.OnDark, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
