package app.absentmotion.editor

import app.absentmotion.model.*
import kotlin.math.*

internal fun EditorState.changeBoneOptions(change: (BoneOptions) -> BoneOptions) {
    val id = rigBoneId ?: return
    endGesture(); playing = false
    edit { s -> s.update(id) { b ->
        val old = b.boneOptions
        var options = change(old)
        if (options.independent && !old.independent) {
            val parent = s.rigParentAffine(b, sampleMs)
            options = options.copy(independentBase = Math.toDegrees(atan2(parent.b, parent.a)).toFloat())
        }
        b.copy(node = b.node.withChild("trueBoneOptions", options.node()))
    } }
}

internal fun EditorState.renameBone(name: String) {
    val id = rigBoneId ?: return
    edit("rig-name") { it.update(id) { b -> b.withLabel(name.take(128)) } }
}

internal fun EditorState.setBoneLength(value: Float) {
    val id = rigBoneId ?: return
    endGesture()
    edit { s ->
        val before = s.layer(id) ?: return@edit s
        val after = value.coerceIn(1f, 10000f)
        // Children attached to the old tip stay attached when the rest length changes.
        val changed = s.update(id) { b -> b.copy(node = b.node.with("length", "$after")) }
        s.bones.filter { it.node["parent"] == "$id" && abs(it.location[0] - before.rigLength) < .01f && abs(it.location[1]) < .01f }
            .fold(changed) { scene, child -> scene.update(child.id) { b ->
                b.copy(node = b.node.mapTransform("location") { v -> v[0] += after - before.rigLength }
                    .withChild("trueRest", b.node.child("trueRest")?.let { rest ->
                        rest.withChild("location", rest.child("location")?.let { n ->
                            val v = floats(n["value"], floatArrayOf(before.rigLength,0f,0f)); v[0] += after-before.rigLength; n.with("value",formatFloats(v))
                        })
                    }))
            } }
    }
}

internal fun EditorState.toggleBonePin() {
    val id = rigBoneId ?: return
    val b = scene.layer(id) ?: return
    val point = scene.rigSegment(b, sampleMs).second
    endGesture(); playing = false
    edit { it.update(id) { bone -> bone.copy(node = bone.node.withChild("trueRigTarget", if (bone.rigPinned) null else
        XmlNode("trueRigTarget", mapOf("flip" to "$rigFlip"), children = listOf(XmlNode("point", mapOf("value" to "${point.x},${point.y}")))))) } }
    rigKeyChannel=if(b.rigPinned) 0 else 3
}

internal fun EditorState.openRigTween() {
    endGesture();playing=false
    if (easing != null) {rigSettingsOpen=false;easingOpen=true} else easingHint=true
}

internal fun Layer.withRigTarget(target: Pt, keyed: Boolean): Layer {
    val old = node.child("trueRigTarget")?.child("point") ?: return this
    var seeded = old
    if (keyed && old.keyframes.isEmpty()) seeded = old.withKeyframes(listOf(Keyframe(0f, old["value"] ?: "${target.x},${target.y}")))
    val point = if (keyed) seeded.withValueAt(t, "${target.x},${target.y}", snap) else XmlNode("point", mapOf("value" to "${target.x},${target.y}"))
    return copy(node = node.withChild("trueRigTarget", node.child("trueRigTarget")!!.withChild("point",point)))
}

/** CCD works on chains with any number of joints, respecting limits and the chosen chain root. */
internal fun Scene.withRigIK(id: Long, target: Pt, time: Int, keyed: Boolean, flipped: Boolean): Scene {
    val tip = layer(id)?.takeIf { it.isBone && !it.boneOptions.locked } ?: return this
    var next = this
    val chain = mutableListOf<Long>()
    var bone: Layer? = tip
    while (bone != null && bone.isBone && chain.size < 16 && bone.id !in chain && !bone.boneOptions.locked) {
        chain += bone.id
        if (bone.boneOptions.stopIK) break
        bone = bone.node["parent"]?.toLongOrNull()?.let(::layer)
    }
    // A straight limb has two equivalent solutions. Seed the preferred elbow for stable bending.
    if (chain.size >= 2 && abs(tip.at(time,fps).transform("rotation",0f)[0] - tip.restAngle) < .01f) {
        next = next.update(id,time) { it.withRigRotation(it.constrainedAngle(it.restAngle + if (flipped) -1f else 1f),keyed) }
    }
    repeat(18) {
        for (jointId in chain) {
            val current = next.layer(jointId) ?: continue
            val end = next.rigSegment(next.layer(id)!!,time).second
            if (hypot(end.x-target.x,end.y-target.y) < .25f) return next
            val origin = next.rigSegment(current,time).first
            val inverse = next.rigParentAffine(current,time).inverse() ?: continue
            val a = inverse.map(end); val b = inverse.map(target); val o = inverse.map(origin)
            val ax = a.x-o.x; val ay = a.y-o.y; val bx = b.x-o.x; val by = b.y-o.y
            if (hypot(ax,ay) < .0001f || hypot(bx,by) < .0001f) continue
            val delta = Math.toDegrees(atan2((ax*by-ay*bx).toDouble(),(ax*bx+ay*by).toDouble())).toFloat()
            next = next.update(jointId,time) { joint -> joint.withRigRotation(joint.constrainedAngle(joint.transform("rotation",0f)[0]+delta),keyed) }
        }
    }
    return next
}

internal fun EditorState.resetRigPose(all: Boolean) {
    val ids = if (all) scene.bones.map { it.id } else listOfNotNull(rigBoneId)
    endGesture(); playing = false
    edit { s -> ids.fold(s) { scene, id -> scene.update(id,sampleMs) { b ->
        var rested = b.withRigRotation(b.restAngle,rigKeyed)
        for ((part,defaults) in listOf("location" to floatArrayOf(0f,0f,0f),"scale" to floatArrayOf(1f,1f))) {
            val ref = PropRef(PropRef.Kind.Transform,part)
            val value = b.node.child("trueRest")?.child(part)?.get("value") ?: formatFloats(defaults)
            if (rigKeyed) {
                if (rested.keyframes(ref).isEmpty()) rested = rested.withElement(ref,XmlNode(part).withKeyframes(listOf(Keyframe(0f,formatFloats(b.transform(part,*defaults))))))
                rested = rested.withValue(ref,value)
            } else rested = rested.withElement(ref,XmlNode(part,mapOf("value" to value)))
        }
        rested.copy(node = rested.node.withChild("trueRigTarget",null))
    } } }
}
