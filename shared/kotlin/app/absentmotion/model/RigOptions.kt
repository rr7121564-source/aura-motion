package app.absentmotion.model

import kotlin.math.*

data class BoneOptions(
    val limits: Boolean = false, val min: Float = -90f, val max: Float = 90f,
    val independent: Boolean = false, val independentBase: Float = 0f,
    val locked: Boolean = false, val stopIK: Boolean = false,
) {
    fun node() = XmlNode("trueBoneOptions", mapOf("limits" to "$limits", "min" to "$min", "max" to "$max",
        "independent" to "$independent", "independentBase" to "$independentBase", "locked" to "$locked", "stopIK" to "$stopIK"))
}

val Layer.boneOptions: BoneOptions get() = node.child("trueBoneOptions")?.let {
    val lo = it["min"]?.finiteFloat()?.coerceIn(-3600f, 3600f) ?: -90f
    val hi = it["max"]?.finiteFloat()?.coerceIn(lo, 3600f) ?: maxOf(lo, 90f)
    BoneOptions(it["limits"] == "true", lo, hi, it["independent"] == "true", it["independentBase"]?.finiteFloat() ?: 0f,
        it["locked"] == "true", it["stopIK"] == "true")
} ?: BoneOptions()

val Layer.restAngle: Float get() = floats(node.child("trueRest")?.child("rotation")?.get("value"), floatArrayOf(0f))[0]
val Layer.rigPinned: Boolean get() = node.child("trueRigTarget") != null
fun Layer.rigKeyRef(channel: Int): PropRef = when {
    channel == 3 && rigPinned -> PropRef(PropRef.Kind.Child,"trueRigTarget/point")
    channel == 1 -> PropRef.Location
    channel == 2 -> PropRef.Scale
    else -> PropRef.Rotation
}
fun Layer.rigTarget(): Pt? = node.child("trueRigTarget")?.child("point")?.valueAt(t)?.let {
    val v = floats(it, floatArrayOf(Float.NaN, Float.NaN))
    if (v.all(Float::isFinite)) Pt(v[0], v[1]) else null
}

fun Layer.constrainedAngle(value: Float): Float {
    val options = boneOptions
    if (!isBone || !options.limits) return value
    // Choose the turn nearest the rest angle, so 359 and -1 do not bend opposite ways.
    val centre = restAngle + (options.min + options.max) / 2
    val angle = value + 360f * round((centre - value) / 360f)
    return angle.coerceIn(restAngle + options.min, restAngle + options.max)
}
