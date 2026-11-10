package app.absentmotion.model

/** Extend a skeleton with its artwork, preserving every key's project time. */
fun Scene.withRigSpans(recursive: Boolean = true): Scene {
    val nodes = node.children.map { child ->
        if (recursive && child.name == "embedScene") {
            val inner = child.child("scene") ?: return@map child
            val next = Scene(inner).withRigSpans().node
            if (inner === next) child else child.withChild("scene", next)
        } else child
    }
    val scene = if (nodes == node.children) this else Scene(node.copy(children = nodes))
    val all = scene.node.children.filter { it.name in Scene.LAYER_TAGS || it.name == "bone" }.map(::Layer)
    fun Layer.bone() = isBone || node["trueBone"] == "true"
    val bones = all.filter { it.bone() }
    if (bones.isEmpty()) return scene
    val byId = all.associateBy { it.id }
    val roots = HashMap<Long, Long>()
    fun root(bone: Layer): Long = roots.getOrPut(bone.id) {
        var at: Layer? = bone
        var root = bone.id
        val seen = HashSet<Long>()
        val visitedBones = ArrayList<Long>()
        while (at != null && seen.size < 1024) {
            if (!seen.add(at.id)) return@getOrPut visitedBones.minOrNull() ?: bone.id
            if (at.bone()) { root = at.id; visitedBones += at.id }
            at = at.node["parent"]?.toLongOrNull()?.let(byId::get)
        }
        root
    }
    data class Span(var start: Int, var end: Int) {
        fun include(layer: Layer) { start = minOf(start, layer.startTime); end = maxOf(end, layer.endTime) }
    }
    val used = HashMap<Long, Span>()
    for (layer in all) {
        if (layer.bone()) continue
        var parent = layer.node["parent"]?.toLongOrNull()?.let(byId::get)
        val seen = HashSet<Long>()
        while (parent != null && seen.size < 1024 && seen.add(parent.id)) {
            if (parent.bone()) {
                used.getOrPut(root(parent)) { Span(layer.startTime, layer.endTime) }.include(layer)
                break
            }
            parent = parent.node["parent"]?.toLongOrNull()?.let(byId::get)
        }
    }
    if (used.isEmpty()) return scene
    // IK targets can be on an unbound child bone of the same skeleton.
    for (bone in bones) used[root(bone)]?.include(bone)
    val changed = HashMap<Long, XmlNode>()
    for (bone in bones) {
        val span = used[root(bone)] ?: continue
        if (bone.startTime != span.start || bone.endTime != span.end) {
            changed[bone.id] = bone.withTimes(span.start, span.end).node
        }
    }
    return if (changed.isEmpty()) scene else Scene(scene.node.copy(children = scene.node.children.map { child ->
        if (child.name == "bone" || child.name == "nullobj" && child["trueBone"] == "true") changed[Layer(child).id] ?: child else child
    }))
}
