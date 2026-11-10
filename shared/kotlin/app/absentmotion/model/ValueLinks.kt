package app.absentmotion.model

import kotlin.math.ceil

/** A scalar channel; vectors can link each axis independently. Values use scene units. */
data class ValueChannel(val ref: PropRef, val axis: Int = 0)
data class ValueLink(val source: Long, val channel: ValueChannel, val multiply: Float = 1f, val offset: Float = 0f, val delayMs: Int = 0)

private fun ValueChannel.attributes() = mapOf("kind" to ref.kind.name, "name" to ref.name, "effect" to "${ref.effectIndex}", "axis" to "$axis")
private fun XmlNode.channel(): ValueChannel? = runCatching {
    ValueChannel(PropRef(PropRef.Kind.valueOf(this["kind"]!!), this["name"]!!, this["effect"]?.toIntOrNull() ?: -1), this["axis"]!!.toInt())
}.getOrNull()
private fun XmlNode.link(): ValueLink? = channel()?.let { c ->
    this["source"]?.toLongOrNull()?.let { ValueLink(it, c, this["multiply"]?.finiteFloat() ?: 1f, this["offset"]?.finiteFloat() ?: 0f, this["delay"]?.toIntOrNull() ?: 0) }
}
private fun XmlNode.base() = child("trueLinkBase")?.copy(name = name) ?: copy(children = children.filterNot { it.name == "trueValueLink" })

fun ValueChannel.defaults(): FloatArray = when (ref) {
    PropRef.Location, PropRef.Pivot -> floatArrayOf(0f, 0f, 0f)
    PropRef.Scale -> floatArrayOf(1f, 1f)
    PropRef.Tilt, PropRef.Skew -> floatArrayOf(0f, 0f)
    PropRef.Opacity -> floatArrayOf(1f)
    else -> floatArrayOf(0f)
}
fun Layer.valueLink(channel: ValueChannel): ValueLink? = element(channel.ref)?.children
    ?.firstOrNull { it.name == "trueValueLink" && it["targetAxis"] == "${channel.axis}" }?.link()
fun Scene.resolvedValueLink(id: Long, channel: ValueChannel): ValueLink? {
    val metadata = layer(id)?.element(channel.ref)?.children?.firstOrNull { it.name == "trueValueLink" && it["targetAxis"] == "${channel.axis}" } ?: return null
    val link = metadata.link() ?: return null
    val token = metadata["effectKey"] ?: return link
    val index = layer(link.source)?.effects?.indexOfFirst { it["trueLinkKey"] == token } ?: -1
    return link.copy(channel = link.channel.copy(ref = link.channel.ref.copy(effectIndex = index)))
}
fun Layer.numericChannels(): List<ValueChannel> {
    val transforms = listOf(PropRef.Location, PropRef.Rotation, PropRef.Scale, PropRef.Opacity, PropRef.Tilt, PropRef.Skew, PropRef.Pivot)
        .flatMap { ref -> ValueChannel(ref).defaults().indices.map { ValueChannel(ref, it) } }
    fun channels(ref: PropRef, property: XmlNode): List<ValueChannel> {
        if (property["type"] !in setOf("float", "vec2", "vec3", "point", "int")) return emptyList()
        val values = property.valueAt(t)?.split(',')?.map { it.trim().finiteFloat() ?: return emptyList() } ?: return emptyList()
        return values.indices.map { ValueChannel(ref, it) }
    }
    return transforms + node.children.filter { it.name == "property" }.flatMap { channels(PropRef.shape(it["name"] ?: ""), it) } +
        effects.flatMapIndexed { index, effect -> effect.children.filter { it.name == "property" }.flatMap { channels(PropRef.effect(index, it["name"] ?: ""), it) } }
}

fun Scene.withValueLink(target: Long, channel: ValueChannel, link: ValueLink): Scene {
    require(link.multiply.isFinite() && link.offset.isFinite() && link.delayMs in -3_600_000..3_600_000)
    val dst = requireNotNull(layer(target)); val src = requireNotNull(layer(link.source))
    require(channel in dst.numericChannels() && link.channel in src.numericChannels()) { "Unsupported channel" }
    require(dst.node.child("trueCopy") == null && src.node.child("trueCopy") == null) { "Link the original layer" }
    val old = dst.element(channel.ref) ?: XmlNode(channel.ref.name, mapOf("value" to formatFloats(channel.defaults())))
    val base = old.child("trueLinkBase") ?: old.copy(name = "trueLinkBase", children = old.children.filterNot { it.name == "trueValueLink" })
    val effect = if (link.channel.ref.kind == PropRef.Kind.Effect) src.effects[link.channel.ref.effectIndex] else null
    val token = effect?.get("trueLinkKey") ?: effect?.let { java.util.UUID.randomUUID().toString() }
    val prepared = if (effect != null) update(src.id) { it.withEffect(link.channel.ref.effectIndex) { e -> e.with("trueLinkKey", token) } } else this
    val metadata = XmlNode("trueValueLink", link.channel.attributes() + mapOf("targetAxis" to "${channel.axis}", "source" to "${link.source}",
        "multiply" to "${link.multiply}", "offset" to "${link.offset}", "delay" to "${link.delayMs}"))
        .with("effectKey", token)
    val next = prepared.update(target) { it.withElement(channel.ref, old.copy(children = old.children.filterNot {
        it.name == "trueValueLink" && it["targetAxis"] == "${channel.axis}"
    }).withChild("trueLinkBase", base).let { p -> p.copy(children = p.children + metadata) }) }
    return next.refreshValueLinks() // Validates the entire dependency graph before committing.
}

fun Scene.withoutValueLink(target: Long, channel: ValueChannel): Scene = update(target) { layer ->
    val old = layer.element(channel.ref) ?: return@update layer
    val rest = old.copy(children = old.children.filterNot { it.name == "trueValueLink" && it["targetAxis"] == "${channel.axis}" })
    // Disconnecting keeps the currently evaluated animation.
    layer.withElement(channel.ref, if (rest.children.none { it.name == "trueValueLink" }) rest.withChild("trueLinkBase", null) else {
        val base = rest.copy(name = "trueLinkBase", children = rest.children.filterNot { it.name in setOf("trueValueLink", "trueLinkBase") })
        rest.withChild("trueLinkBase", base)
    })
}

/** Compile persistent links to ordinary animation keys, used identically by preview and export. */
fun Scene.refreshValueLinks(): Scene {
    val all = layers + bones
    val ports = all.filter { it.node.child("trueCopy") == null }.flatMap { l -> l.numericChannels().filter { l.valueLink(it) != null }.map { l.id to it } }
    if (ports.isEmpty()) return this
    val missing = ports.filter { (id, channel) ->
        val link = resolvedValueLink(id, channel)
        link == null || layer(link.source)?.numericChannels()?.contains(link.channel) != true
    }
    if (missing.isNotEmpty()) return missing.fold(this) { s, (id, channel) -> s.withoutValueLink(id, channel) }.refreshValueLinks()
    val source = all.associateBy { it.id }
    val visiting = mutableSetOf<Pair<Long, ValueChannel>>()
    val checked = mutableSetOf<Pair<Long, ValueChannel>>()
    fun validate(port: Pair<Long, ValueChannel>) {
        if (port in checked) return
        require(visiting.add(port)) { "Parameter link cycle" }
        resolvedValueLink(port.first, port.second)?.let { validate(it.source to it.channel) }
        visiting.remove(port); checked.add(port)
    }
    ports.forEach(::validate)
    fun sample(id: Long, channel: ValueChannel, ms: Double, depth: Int = 0): Float {
        require(depth < 128) { "Parameter link chain is too long" }
        val l = source[id] ?: return channel.defaults().getOrElse(channel.axis) { 0f }
        val link = resolvedValueLink(id, channel)
        if (link != null && source[link.source] != null) {
            return (sample(link.source, link.channel, ms - link.delayMs, depth + 1) * link.multiply + link.offset).also { require(it.isFinite()) }
        }
        val t = ((ms - l.startTime) / (l.endTime - l.startTime).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
        val value = l.element(channel.ref)?.base()?.valueAt(t)
        return floats(value, channel.defaults()).getOrElse(channel.axis) { 0f }
    }
    fun animated(id: Long, channel: ValueChannel): Boolean {
        val l = source[id] ?: return false
        val link = resolvedValueLink(id, channel)
        return if (link != null && source[link.source] != null) animated(link.source, link.channel) else l.element(channel.ref)?.base()?.isKeyframed == true
    }
    var next = this
    var budget = 0
    for ((id, channels) in ports.groupBy({ it.first }, { it.second })) {
        val l = source.getValue(id)
        for ((ref, axes) in channels.groupBy { it.ref }) {
            val prop = l.element(ref) ?: continue
            val defaults = ValueChannel(ref).defaults()
            val n = maxOf(defaults.size, floats(prop.base().valueAt(0f), defaults).size, axes.maxOf { it.axis } + 1)
            val needsKeys = prop.base().isKeyframed || axes.any { animated(id, it) }
            val count = if (needsKeys) ceil((l.endTime - l.startTime) * fps / 1000.0).toInt().coerceAtLeast(1) else 0
            budget += count + 1
            require(budget <= 500_000) { "Too many linked animation samples" }
            fun value(t: Float): String {
                val values = floats(prop.base().valueAt(t), defaults).copyOf(n)
                for (axis in axes) values[axis.axis] = sample(id, axis, l.startTime + (l.endTime - l.startTime) * t.toDouble())
                return formatFloats(values)
            }
            val baked = if (needsKeys) prop.withKeyframes((0..count).map { i ->
                val t = minOf(1f, i * 1000f / fps / (l.endTime - l.startTime).coerceAtLeast(1))
                Keyframe(t, value(t))
            }) else prop.copy(attrs = prop.attrs + ("value" to value(0f)), children = prop.children.filterNot { it.name == "kf" })
            next = next.update(id) { it.withElement(ref, baked) }
        }
    }
    return next
}

data class NumberRef(val ref: PropRef, val component: Int = 0)

data class NativeValueLink(
    val layer: Long,
    val source: NumberRef,
    val multiply: Float = 1f,
    val add: Float = 0f,
    val delay: Float = 0f,
    val fallback: Float = 0f,
) {
    fun node(component: Int) = XmlNode("valueLink", mapOf(
        "component" to "$component", "layer" to "$layer", "kind" to source.ref.kind.name.lowercase(),
        "property" to source.ref.name, "effect" to "${source.ref.effectIndex}", "sourceComponent" to "${source.component}",
        "multiply" to "$multiply", "add" to "$add", "delay" to "$delay", "fallback" to "$fallback",
    ))
}

fun XmlNode.valueLink(component: Int): NativeValueLink? {
    val n = children.firstOrNull { it.name == "valueLink" && (it["component"]?.toIntOrNull() ?: 0) == component } ?: return null
    val kind = PropRef.Kind.entries.firstOrNull { it.name.equals(n["kind"], true) } ?: return null
    return NativeValueLink(n["layer"]?.toLongOrNull() ?: return null,
        NumberRef(PropRef(kind, n["property"] ?: return null, n["effect"]?.toIntOrNull() ?: -1), n["sourceComponent"]?.toIntOrNull() ?: 0),
        n["multiply"]?.finiteFloat() ?: 1f, n["add"]?.finiteFloat() ?: 0f, n["delay"]?.finiteFloat() ?: 0f, n["fallback"]?.finiteFloat() ?: 0f)
}

fun Layer.withValueLink(target: NumberRef, link: NativeValueLink?, fallback: String = "0"): Layer {
    val old = element(target.ref) ?: XmlNode(target.ref.name, mapOf("value" to fallback))
    return withElement(target.ref, old.copy(children = old.children.filterNot {
        it.name == "valueLink" && (it["component"]?.toIntOrNull() ?: 0) == target.component
    } + listOfNotNull(link?.node(target.component))))
}

fun Scene.canLink(layer: Long, target: NumberRef, link: NativeValueLink): Boolean {
    val targetLayer = this.layer(layer) ?: return false
    val sourceLayer = this.layer(link.layer) ?: return false
    fun valid(l: Layer, n: NumberRef): Boolean = when (n.ref.kind) {
        PropRef.Kind.Transform -> n.component in 0 until when (n.ref.name) {
            "location", "pivot" -> 3
            "scale", "skew", "tilt" -> 2
            "rotation", "opacity" -> 1
            else -> 0
        }
        PropRef.Kind.Effect -> l.effects.getOrNull(n.ref.effectIndex) != null && l.element(n.ref)?.get("type") !in setOf("color", "bool", "string")
        else -> false
    }
    if (!valid(targetLayer, target) || !valid(sourceLayer, link.source)) return false
    if (target.component !in 0..2 || link.source.component !in 0..2) return false
    if (target.ref.kind !in listOf(PropRef.Kind.Transform, PropRef.Kind.Effect) || link.source.ref.kind !in listOf(PropRef.Kind.Transform, PropRef.Kind.Effect)) return false
    if (!link.multiply.isFinite() || !link.add.isFinite() || !link.delay.isFinite() || !link.fallback.isFinite()) return false
    val visited = hashSetOf<Pair<Long, NumberRef>>()
    fun follows(id: Long, number: NumberRef, depth: Int): Boolean {
        if (id == layer && number == target) return true
        if (depth >= 64) return true
        if (!visited.add(id to number)) return false
        val source = this.layer(id) ?: return false
        val next = source.element(number.ref)?.valueLink(number.component)
        if (next != null && follows(next.layer, next.source, depth + 1)) return true
        if (number.ref.kind == PropRef.Kind.Transform) {
            val parent = source.node["parent"]?.toLongOrNull()
            if ((parent != null || source.effects.any { it["id"] == "absent.randomdisplace" && it["hidden"] != "true" }) && number.ref == PropRef.Location && (0..2).any { c -> c != number.component && follows(id, NumberRef(PropRef.Location, c), depth + 1) }) return true
            if (parent != null && number.ref.name in setOf("location", "scale", "rotation")) {
                val refs = if (number.ref.name == "location") listOf(PropRef.Location, PropRef.Scale, PropRef.Pivot, PropRef.Rotation, PropRef.Tilt) else listOf(number.ref)
                if (refs.any { ref -> (0..if (ref == PropRef.Location) 2 else if (ref == PropRef.Rotation) 0 else 1).any { follows(parent, NumberRef(ref, it), depth + 1) } }) return true
            }
            if (source.effects.any { e -> e["hidden"] != "true" && e.children.any { p -> (0..2).any { c -> p.valueLink(c)?.let { follows(it.layer, it.source, depth + 1) } == true } } }) return true
        }
        return false
    }
    return !follows(link.layer, link.source, 0)
}

fun Scene.withValueLink(layer: Long, target: NumberRef, link: NativeValueLink?, fallback: String = "0"): Scene =
    if (link != null && !canLink(layer, target, link)) this else update(layer) { it.withValueLink(target, link, fallback) }

fun XmlNode.withoutValueLinks(ids: Set<Long>): XmlNode {
    if (name == "scene") return this
    val next = children.filterNot { it.name == "valueLink" && it["layer"]?.toLongOrNull() in ids }.map { it.withoutValueLinks(ids) }
    return if (next == children) this else copy(children = next)
}

fun XmlNode.withoutAnyValueLinks(): XmlNode {
    val next = children.filterNot { it.name == "valueLink" }.map { it.withoutAnyValueLinks() }
    return if (next == children) this else copy(children = next)
}

fun Scene.withValueLinksFollowing(before: Scene): Scene {
    val oldLayers = before.layers.associateBy { it.id }
    val newLayers = layers.associateBy { it.id }
    fun walk(node: XmlNode): XmlNode? {
        if (node.name == "scene") return node
        if (node.name == "valueLink" && node["kind"] == "effect") {
            val id = node["layer"]?.toLongOrNull() ?: return node
            val index = node["effect"]?.toIntOrNull() ?: return node
            val oldEffects = oldLayers[id]?.effects ?: return node
            val old = oldEffects.getOrNull(index) ?: return node
            val next = newLayers[id]?.effects ?: return null
            val moved = next.indexOfFirst { it === old }.takeIf { it >= 0 }
                ?: next.indexOfFirst { it == old }.takeIf { it >= 0 }
                ?: index.takeIf { next.size == oldEffects.size && next.getOrNull(index)?.get("id") == old["id"] }
                ?: return null
            return if (moved == index) node else node.with("effect", "$moved")
        }
        val children = node.children.mapNotNull(::walk)
        return if (children == node.children) node else node.copy(children = children)
    }
    return Scene(node.copy(children = node.children.mapNotNull(::walk)))
}
