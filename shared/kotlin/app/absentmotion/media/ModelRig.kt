package app.absentmotion.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Indices remain glTF node indices, so saved bone keys survive reload and detail changes. */
data class ModelJoint(val index: Int, val name: String, val parent: Int, val joint: Boolean)
data class ModelClipInfo(val name: String, val duration: Float)
data class ModelRigInfo(val nodes: List<ModelJoint>, val clips: List<ModelClipInfo>)

data class ModelRigNode(
    val info: ModelJoint,
    val matrix: FloatArray,
    val translation: FloatArray,
    val rotation: FloatArray,
    val scale: FloatArray,
    val matrixNode: Boolean,
)
data class ModelRigChannel(val node: Int, val path: Int, val interpolation: Int, val times: FloatArray, val values: FloatArray)
data class ModelRigClip(val name: String, val duration: Float, val channels: List<ModelRigChannel>)
data class ModelRigBinding(val node: Int, val inverseBind: FloatArray)

class ModelRig(
    val nodes: List<ModelRigNode>,
    val clips: List<ModelRigClip>,
    val bindings: List<ModelRigBinding>,
    val normalization: FloatArray,
    val positions: FloatArray,
    val normals: FloatArray,
    val influences: IntArray,
    val weights: FloatArray,
) {
    val info: ModelRigInfo get() = ModelRigInfo(nodes.map { it.info }, clips.map { ModelClipInfo(it.name, it.duration) })

    fun subset(keep: IntArray) = ModelRig(nodes, clips, bindings, normalization,
        FloatArray(keep.size * 3) { positions[keep[it / 3] * 3 + it % 3] },
        FloatArray(keep.size * 3) { normals[keep[it / 3] * 3 + it % 3] },
        IntArray(keep.size * 8) { influences[keep[it / 8] * 8 + it % 8] },
        FloatArray(keep.size * 8) { weights[keep[it / 8] * 8 + it % 8] })

    /** Bounded, versioned little-endian transfer; native validates every count before allocation. */
    fun packed(): ByteArray {
        val size = 24L + 64L + nodes.size * 112L + bindings.size * 68L + positions.size * 4L + normals.size * 4L +
            influences.size * 4L + weights.size * 4L + clips.sumOf { clip -> 8L + clip.channels.sumOf { 16L + it.times.size * 4L + it.values.size * 4L } }
        require(size <= 128L * 1024 * 1024) { "Model animation is too complex" }
        val bytes = ByteArrayOutputStream(size.toInt())
        val out = DataOutputStream(bytes)
        fun int(v: Int) = out.writeInt(Integer.reverseBytes(v))
        fun float(v: Float) { require(v.isFinite()); int(v.toRawBits()) }
        fun floats(v: FloatArray) = v.forEach(::float)
        int(0x47524d54); int(1); int(nodes.size); int(bindings.size); int(clips.size); int(positions.size / 3)
        floats(normalization)
        for (node in nodes) {
            int(node.info.parent); int(if (node.matrixNode) 1 else 0)
            floats(node.matrix); floats(node.translation); floats(node.rotation); floats(node.scale)
        }
        for (binding in bindings) { int(binding.node); floats(binding.inverseBind) }
        floats(positions); floats(normals); influences.forEach(::int); floats(weights)
        for (clip in clips) {
            float(clip.duration); int(clip.channels.size)
            for (channel in clip.channels) {
                int(channel.node); int(channel.path); int(channel.interpolation); int(channel.times.size)
                floats(channel.times); floats(channel.values)
            }
        }
        return bytes.toByteArray()
    }
}

/** Metadata follows loaded models rather than retaining whole meshes or rereading GLB on the UI thread. */
object ModelRigs {
    private val current = MutableStateFlow<Map<String, ModelRigInfo>>(emptyMap())
    val loaded: StateFlow<Map<String, ModelRigInfo>> get() = current
    @Synchronized fun set(uri: String, rig: ModelRig?) {
        current.value = if (rig == null) current.value - uri else current.value + (uri to rig.info)
    }
}
