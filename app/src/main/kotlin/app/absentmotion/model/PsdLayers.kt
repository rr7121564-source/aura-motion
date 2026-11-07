package app.absentmotion.model

import app.truemotion.vector.PsdFile

/** PSD layer records are stored from the back of the stack to its front. */
internal fun psdLayers(psd: PsdFile, images: Map<PsdFile.Layer, MediaDeclaration>, duration: Int, fps: Int = 30): List<Layer> {
    val stack = mutableListOf(mutableListOf<Layer>())
    fun appearance(layer: Layer, source: PsdFile.Layer): Layer {
        val blend = mapOf("mul " to "multiply", "scrn" to "screen", "over" to "overlay", "dark" to "darken",
            "lite" to "lighten", "diff" to "difference", "hLit" to "hard-light", "sLit" to "soft-light")
        return layer.withLabel(source.name).withHidden(source.flags and 2 != 0)
            .withTransform("opacity", source.opacity / 255f)
            .let { blend[source.blend]?.let(it::withBlend) ?: it }
    }
    for (source in psd.layers) {
        when (source.section) {
            3 -> stack.add(mutableListOf())
            1, 2 -> {
                val parts = if (stack.size > 1) stack.removeAt(stack.lastIndex) else mutableListOf()
                val inner = Scene(XmlNode("scene", mapOf("width" to "${psd.width}", "height" to "${psd.height}",
                    "fps" to "$fps", "totalTime" to "$duration", "retime" to "off", "bgcolor" to "#00000000"), parts.map { it.node }))
                val group = Layer(XmlNode("embedScene", mapOf("id" to "${Layer.newId()}", "startTime" to "0", "endTime" to "$duration"), listOf(inner.node)))
                    .withTransform("location", psd.width / 2f, psd.height / 2f, 0f)
                stack.last().add(appearance(group, source))
            }
            else -> images[source]?.let { image ->
                val layer = Layer(XmlNode("shape", mapOf("id" to "${Layer.newId()}", "startTime" to "0", "endTime" to "$duration",
                    "s" to ".rect", "fillType" to "media", "fillImage" to image.uri, "mediaFillMode" to "stretch")))
                    .withProp("size", "vec2", source.width / 2f, source.height / 2f)
                    .withTransform("location", source.left + source.width / 2f, source.top + source.height / 2f, 0f)
                stack.last().add(appearance(layer, source))
            }
        }
    }
    while (stack.size > 1) { val unfinished = stack.removeAt(stack.lastIndex); stack.last().addAll(unfinished) }
    return stack.single()
}
