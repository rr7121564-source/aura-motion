package app.absentmotion.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.R
import app.absentmotion.editor.*
import app.absentmotion.model.*
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Presence
import kotlinx.coroutines.launch
import app.absentmotion.platform.Matrix
import kotlinx.coroutines.flow.collectLatest

private data class BoneLine(val bone: Layer, val from: Offset, val to: Offset, val plane: Matrix?)
private data class RigFrameRequest(val scene: Scene, val time: Int, val selected: Long?, val camera: Boolean)

private fun lines(state: EditorState, matrices: Map<Long,FloatArray>): List<BoneLine> =
    state.scene.bones.filter { it.activeAt(state.sampleMs) }.map { bone ->
        val values=matrices[bone.id]
        val points=if(values!=null) {
            val matrix=Matrix().apply {setValues(values.copyOfRange(0,9))}
            matrix.map(Pt(0f,0f)) to matrix.map(Pt(bone.rigLength,0f))
        } else state.scene.rigSegment(bone,state.sampleMs)
        BoneLine(bone,Offset(points.first.x,points.first.y),Offset(points.second.x,points.second.y),
            values?.let {Matrix().apply {setValues(it.copyOfRange(9,18))}})
    }

@Composable
internal fun RigViewport(state: EditorState, scale: Float, offset: Offset, quad: FloatArray?, modifier: Modifier) {
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(offset)
    var matrices by remember {mutableStateOf<Map<Long,FloatArray>>(emptyMap())}
    LaunchedEffect(state) {
        snapshotFlow {RigFrameRequest(state.scene,state.sampleMs,state.selectedId,state.cameraMode)}.collectLatest { request ->
            val ids=request.scene.bones.map {it.id}+listOfNotNull(request.selected)
            matrices=state.engine.layerMatrices(ids,request.time,request.camera)
        }
    }
    val currentLines by rememberUpdatedState(lines(state,matrices))
    val currentMatrices by rememberUpdatedState(matrices)
    val scope = rememberCoroutineScope()
    var ghost by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val accent = Am.Accent
    val normal = Am.Gold
    DisposableEffect(Unit) { onDispose { state.endGesture() } }
    BoxWithConstraints(modifier) {
        val compact = maxHeight < 190.dp && maxWidth >= 500.dp
        Canvas(Modifier.fillMaxSize().pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown()
                state.playing = false
                val initialScale = currentScale.coerceAtLeast(0.00001f)
                val initialOffset = currentOffset
                fun view(point: Offset) = (point - initialOffset) / initialScale
                val viewStart = view(down.position)
                val candidates = currentLines
                val reach = 22.dp.toPx() / initialScale
                val tip = candidates.minByOrNull { (it.to - viewStart).getDistance() }?.takeIf { (it.to - viewStart).getDistance() < reach }
                val root = candidates.filter { it.bone.node["parent"] == null }.minByOrNull { (it.from - viewStart).getDistance() }
                    ?.takeIf { (it.from - viewStart).getDistance() < reach }
                fun distance(line: BoneLine): Float {
                    val delta = line.to - line.from
                    val length2 = delta.x * delta.x + delta.y * delta.y
                    val u = if (length2 < .00001f) 0f else (((viewStart - line.from).x * delta.x + (viewStart - line.from).y * delta.y) / length2).coerceIn(0f, 1f)
                    return (viewStart - line.from - delta * u).getDistance()
                }
                val body = candidates.minByOrNull(::distance)?.takeIf { distance(it) < reach * .65f }
                val grabbed = tip ?: root ?: body
                val rootHandle = tip == null && root != null
                val plane = grabbed?.plane ?: currentMatrices[state.selectedId]?.let {v->Matrix().apply {setValues(v.copyOfRange(9,18))}}
                val inverse=plane?.let {p->Matrix().takeIf {p.invert(it)}}
                fun world(point: Offset): Offset = inverse?.map(Pt(point.x,point.y))?.let {Offset(it.x,it.y)} ?: point
                fun project(point: Offset): Offset = plane?.map(Pt(point.x,point.y))?.let {Offset(it.x,it.y)} ?: point
                val start=world(viewStart)
                val worldReach=maxOf((world(viewStart+Offset(reach,0f))-start).getDistance(),(world(viewStart+Offset(0f,reach))-start).getDistance())
                val attachment = state.scene.rigAttachment(Pt(start.x, start.y), worldReach, state.sampleMs, state.rigBoneId)
                val from = if (state.rigTool == 0) attachment?.point?.let { Offset(it.x, it.y) } ?: start else start
                val tool = state.rigTool
                var dragged = false
                var multi = false
                var end = start
                if (grabbed != null) state.rigBoneId = grabbed.bone.id
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
                    if (event.changes.size > 1) {
                        multi = true; ghost = null
                        val zoom = event.calculateZoom()
                        val next = (state.canvasZoom * zoom).coerceIn(0.01f, 64f)
                        val centre = event.calculateCentroid(useCurrent = false)
                        val about = if (centre.isSpecified) (centre - currentOffset) / currentScale - Offset(state.scene.width / 2f, state.scene.height / 2f) else Offset.Zero
                        state.canvasPan = pinchedPan(state.canvasPan + event.calculatePan() / currentScale, about, next / state.canvasZoom)
                        state.canvasZoom = next
                    } else if (!multi) {
                        end = world(view(change.position))
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                        if (dragged) {
                            if (tool == 0 && state.selected != null) ghost = project(from) to project(end)
                            else if (grabbed != null) state.poseViewportBone(grabbed.bone.id, Pt(end.x, end.y), rootHandle, tip != null)
                            else state.canvasPan += event.calculatePan() / currentScale
                        }
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                ghost = null
                if (!multi && dragged && tool == 0) state.drawRigBone(attachment?.parent, Pt(from.x, from.y), Pt(end.x, end.y))
                else if (!multi && !dragged) {
                    if (grabbed != null) {
                        state.rigBoneId = grabbed.bone.id
                    } else scope.launch {
                        val hit = state.engine.hitTest(viewStart.x, viewStart.y, state.timeMs)
                        state.scene.layer(hit)?.takeUnless { it.isBone || it.kind in setOf("audio", "camera", "nullobj") }?.let { art ->
                            state.selectedId = art.id
                            art.node["parent"]?.toLongOrNull()?.takeIf { state.scene.layer(it)?.isBone == true }?.let { state.rigBoneId = it }
                        }
                    }
                }
                state.endGesture()
            }
        }) {
            fun screen(point: Offset) = point * scale + offset
            fun drawBone(a: Offset, b: Offset, color: Color) {
                val direction = b - a
                val length = direction.getDistance().coerceAtLeast(1f)
                val normalAxis = Offset(-direction.y / length, direction.x / length) * minOf(9.dp.toPx(), length / 5)
                val shoulder = a + direction * .18f
                val shape = Path().apply { moveTo(a.x, a.y); lineTo((shoulder + normalAxis).x, (shoulder + normalAxis).y); lineTo(b.x, b.y); lineTo((shoulder - normalAxis).x, (shoulder - normalAxis).y); close() }
                drawPath(shape, color.copy(alpha = .24f))
                drawPath(shape, Color.Black.copy(alpha = .65f), style = Stroke(4.dp.toPx()))
                drawPath(shape, color, style = Stroke(2.dp.toPx()))
                drawCircle(Color.Black, 6.dp.toPx(), a)
                drawCircle(color, 4.dp.toPx(), a)
                drawCircle(Color.Black, 6.dp.toPx(), b)
                drawCircle(color, 4.dp.toPx(), b)
            }
            if (state.rigTool == 0 && quad != null) {
                val corners = List(4) { Offset(quad[it * 2], quad[it * 2 + 1]) * scale + offset }
                for (index in corners.indices) drawLine(accent.copy(alpha = .6f), corners[index], corners[(index + 1) % 4], 1.dp.toPx())
            }
            currentLines.forEach { line -> drawBone(screen(line.from), screen(line.to), if (state.rigBoneId == line.bone.id) accent else normal) }
            ghost?.let { drawBone(screen(it.first), screen(it.second), accent) }
        }
        val bone = state.rigBoneId?.let { state.scene.layerAt(it, state.sampleMs) }
        RigToolbar(state, bone, compact, Modifier.align(Alignment.TopCenter))
        if (bone != null) key(bone.id) {
            val frames = bone.keyframes(PropRef.Rotation).map { state.scene.frameAt(bone.timeOf(it.t)) }.distinct()
            if (frames.isNotEmpty()) {
                val listState = rememberLazyListState(frames.indexOf(state.frame).coerceAtLeast(0))
                LaunchedEffect(state.frame, frames) {
                    val index = frames.indexOf(state.frame)
                    if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                        listState.scrollToItem(index)
                    }
                }
                LazyRow(
                    Modifier.align(if (compact) Alignment.BottomStart else Alignment.BottomCenter)
                        .padding(if (compact) 4.dp else 8.dp).widthIn(max = if (compact) 254.dp else 480.dp)
                        .clip(InspectorShape).background(Am.Panel.copy(alpha = .96f)),
                    state = listState,
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(frames, key = { it }) { frame ->
                        InspectorSegment(TimelineMath.timecode(frame, state.scene.fps), frame == state.frame,
                            Modifier.height(36.dp).widthIn(min = 76.dp)) {
                            state.frame = frame
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RigToolbar(state: EditorState, bone: Layer?, compact: Boolean, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    val onKey = bone?.onKeyframe(PropRef.Rotation) == true
    val artwork = state.selected
    val status: @Composable RowScope.() -> Unit = {
        Icon(PanelIcons.Bone, null, Modifier.padding(start = 6.dp, end = 5.dp).size(19.dp), tint = Am.Accent)
        Column(Modifier.weight(1f).padding(end = 5.dp)) {
            Text(stringResource(R.string.rig_viewport_title), color = Am.OnDark, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when {
                bone != null -> stringResource(R.string.rig_selected_bone, bone.shownName())
                artwork != null -> stringResource(R.string.rig_draw_short)
                else -> stringResource(R.string.rig_pick_artwork)
            }, color = Am.OnDarkDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        InspectorIconButton(PanelIcons.AutoKey, stringResource(R.string.rig_auto_keys), active = state.rigKeyed) {
            state.endGesture()
            state.rigKeyed = !state.rigKeyed
        }
        Box {
            InspectorIconButton(PanelIcons.More, stringResource(R.string.rig_more)) { menu = true }
            Presence(menu) {
                MenuPopup(DropOrRise, { menu = false }) {
                    MenuRow(stringResource(R.string.rig_help_toggle), trailing = PanelIcons.Check.takeIf { help }) {
                        menu = false
                        help = !help
                    }
                    MenuRow(stringResource(R.string.rig_delete_bone), icon = ChromeIcons.Trash, enabled = bone != null) {
                        menu = false
                        state.deleteViewportBone()
                    }
                }
            }
        }
        InspectorIconButton(PanelIcons.Check, stringResource(R.string.done), active = true) {
            state.endGesture()
            state.rigEditing = false
            state.keyTarget = null
        }
    }
    val controls: @Composable RowScope.() -> Unit = {
        Row(Modifier.weight(if (compact) 1.6f else 1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(R.string.rig_draw, R.string.rig_pose, R.string.rig_ik_mode).forEachIndexed { index, title ->
                InspectorSegment(stringResource(title), state.rigTool == index, Modifier.weight(1f).fillMaxHeight()) {
                    state.endGesture()
                    state.rigTool = index
                }
            }
        }
        if (state.rigTool == 0) {
            InspectorIconButton(PanelIcons.Link, stringResource(R.string.rig_bind_selected),
                enabled = bone != null && artwork != null) {
                bone?.id?.let(state::bindViewportBone)
            }
        }
        if (state.rigTool == 2) {
            InspectorIconButton(PanelIcons.FlipJoint, stringResource(R.string.rig_flip), active = state.rigFlip) {
                state.rigFlip = !state.rigFlip
            }
        }
        InspectorIconButton(
            if (onKey) PanelIcons.KeyframeRemove else PanelIcons.Keyframe,
            stringResource(if (onKey) R.string.a11y_remove_keyframe else R.string.a11y_add_keyframe),
            enabled = bone != null, active = onKey,
        ) { state.toggleRigKey() }
    }
    Column(
        modifier.padding(if (compact) 4.dp else 8.dp).widthIn(max = if (compact) 720.dp else 480.dp)
            .fillMaxWidth().clip(InspectorShape).background(Am.Panel.copy(alpha = .96f))
            .pointerInput(Unit) {}.padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (compact) {
            Row(Modifier.fillMaxWidth().height(44.dp), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
                status()
                controls()
                actions()
            }
        } else {
            Row(Modifier.fillMaxWidth().height(44.dp), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
                status()
                actions()
            }
            Row(Modifier.fillMaxWidth().height(44.dp), Arrangement.spacedBy(4.dp)) { controls() }
        }
        if (help) {
            Text(stringResource(if (state.rigTool == 0) R.string.rig_draw_hint else R.string.rig_pose_hint),
                Modifier.padding(5.dp), color = Am.OnDarkDim, fontSize = 11.sp, lineHeight = 15.sp, maxLines = if (compact) 2 else 5)
        }
    }
}
