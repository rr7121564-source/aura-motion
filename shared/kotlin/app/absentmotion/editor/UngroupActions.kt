package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.model.*

internal fun Layer.ungroupProblem(): Int? {
    val inner = nested ?: return R.string.ungroup_not_group
    if (effects.isNotEmpty() || node["clippingMask"] == "true" || node["blending"].let { it != null && it !in setOf("normal", "pass-through") } ||
        node.children.any { it.name in setOf("border", "shadow") } ||
        element(PropRef.Opacity)?.let { it.isKeyframed || floats(it["value"], floatArrayOf(1f))[0] != 1f } == true) return R.string.ungroup_compositing
    val speed = node.child("speedMap")
    if (inner.retime !in setOf("off", "freeze") || speed?.isKeyframed == true ||
        (speed?.get("value") ?: node["speed"] ?: "1").toFloatOrNull() != 1f ||
        endTime - startTime + nestedCut > inner.totalTime + 1) return R.string.ungroup_timing
    return null
}

/** A transform-only controller preserves the group's entire animated matrix without rasterizing. */
internal fun Scene.ungrouped(id: Long): Pair<Scene, List<Long>>? {
    val group = layer(id) ?: return null
    if (group.ungroupProblem() != null) return null
    val inner = group.nested ?: return null
    val copied = copyLayers(inner.layers + inner.bones)
    val controller = group.node.copy(name = "nullobj", attrs = group.node.attrs.filterKeys {
        it in setOf("id", "label", "startTime", "endTime", "parent", "hidden", "tag")
    } + ("trueGroupController" to "true"), children = group.node.children.filter { it.name in setOf("transform", "trueRigBind", "trueValueLink") })
        .with("label", AppText.get(R.string.ungroup_controller, group.label))
    val shift = group.startTime - group.nestedCut
    val members = copied.mapNotNull { item ->
        val moved = item.movedTo(item.startTime + shift)
        val start = maxOf(group.startTime, moved.startTime)
        val end = minOf(group.endTime, moved.endTime)
        if (end < start) return@mapNotNull null
        var child = moved.trimmedTo(start, end)
        if (child.node["parent"] == null) {
            val offset = Affine.translate(-inner.width / 2.0, -inner.height / 2.0) *
                (child.rigBinding?.affine() ?: Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
            child = child.copy(node = child.node.with("parent", "$id").withChild("trueRigBind",
                RigBinding(offset.a, offset.b, offset.c, offset.d, offset.tx, offset.ty, child.rigBinding?.z ?: 0f).node()))
        }
        child
    }
    val media = inner.media.fold(this) { scene, record -> scene.withMedia(record) }
    val nodes = media.node.children.flatMap { child ->
        if (child.name == "embedScene" && Layer(child).id == id) listOf(controller) + members.map { it.node } else listOf(child)
    }
    return Scene(media.node.copy(children = nodes)) to members.filterNot { it.isBone }.map { it.id }
}

fun EditorState.ungroupSelected(): Int? {
    val group = selected ?: return R.string.ungroup_not_group
    group.ungroupProblem()?.let { return it }
    val (next, ids) = scene.ungrouped(group.id) ?: return R.string.ungroup_not_group
    endGesture()
    playing = false
    edit { next }
    selectedId = ids.lastOrNull() ?: group.id
    panel = EditorPanel.Tools
    MultiSelect.clear()
    return null
}
