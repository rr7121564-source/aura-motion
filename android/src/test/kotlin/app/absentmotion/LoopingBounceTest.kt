package app.absentmotion

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class LoopingBounceTest {
    @Test fun draggingBounceToFullRestitutionAndReopeningDoesNotDampIt() {
        val original = Easing("bounce", listOf(.3f, .8f))
        val handle = original.handles().single()
        val loop = handle.moved(.3f, 0f)
        assertEquals(1f, loop.p[1], 0f)
        assertEquals(0f, loop.handles().single().y, 0f)
        val property = XmlNode("rotation").withKeyframes(listOf(Keyframe(0f, "0"), Keyframe(1f, "90", loop.toString())))
        assertEquals(loop, Easing.parse(XmlNode.parse(property.toXml()).keyframes.last().e))
        val reversed = loop.reversed()!!
        assertEquals(1f, reversed.handles().single().moved(.7f, 1f).p[1], 0f)
    }

    @Test fun repeatingBounceIsDistinctFromDampedBounceInThePresetCatalog() {
        val repeat = EasingCatalog.families.flatten().single { it.label == R.string.easing_bounce_loop }.easing
        assertEquals("bounce", repeat.type)
        assertEquals(1f, repeat.p[1], 0f)
        assertEquals(R.string.easing_bounce_loop, EasingCatalog.presetOf(repeat).label)
        assertEquals(R.string.easing_bounce_loop, EasingCatalog.presetOf(repeat.copy(p = listOf(.4f, 1f))).label)
        assertEquals(R.string.keyframe_easing_bounce, EasingCatalog.presetOf(Easing("bounce", listOf(.4f, .7f))).label)
    }
}
