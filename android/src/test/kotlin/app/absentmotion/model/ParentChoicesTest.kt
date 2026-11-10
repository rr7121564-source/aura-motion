package app.absentmotion.model

import org.junit.Assert.*
import org.junit.Test

class ParentChoicesTest {
    private fun layer(id: Long, parent: Long? = null) = XmlNode("shape", buildMap {
        put("id", "$id"); parent?.let { put("parent", "$it") }
    })
    private fun scene(vararg nodes: XmlNode) = Scene(XmlNode("scene", children = nodes.toList()))

    @Test fun descendantsAreNotOfferedAndTimelineOrderIsKept() {
        val scene = scene(layer(1), layer(2, 1), layer(3, 2), layer(4), layer(5))
        assertEquals(listOf(5L, 4L), scene.parentCandidates(setOf(1)).map { it.id })
        assertEquals(listOf(5L, 4L, 1L), scene.parentCandidates(setOf(2, 3)).map { it.id })
    }

    @Test fun malformedImportedCyclesAreNotSelectable() {
        val scene = scene(layer(1, 2), layer(2, 1), layer(3, 1), layer(4, 999), layer(5))
        assertEquals(listOf(5L, 4L), scene.parentCandidates(emptySet()).map { it.id })
    }
}
