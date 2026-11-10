package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class RigTweenTest {
    @Test fun eachBoneChannelUsesTheSameCurveStorageAsTransform() {
        val n=XmlNode("bone",mapOf("id" to "10","startTime" to "0","endTime" to "1000"),children=listOf(
            XmlNode("trueRigTarget",children=listOf(XmlNode("point").withKeyframes(listOf(Keyframe(0f,"0,0"),Keyframe(1f,"200,100")))))))
        val base=Layer(n).withElement(PropRef.Rotation,XmlNode("rotation").withKeyframes(listOf(Keyframe(0f,"0"),Keyframe(1f,"90"))))
            .withElement(PropRef.Location,XmlNode("location").withKeyframes(listOf(Keyframe(0f,"0,0,0"),Keyframe(1f,"100,200,0"))))
            .withElement(PropRef.Scale,XmlNode("scale").withKeyframes(listOf(Keyframe(0f,"1,1"),Keyframe(1f,"2,3"))))
        val curve=Easing("cubicBezier",listOf(.2f,.8f,.3f,1f))
        for (channel in 0..3) {
            val ref=base.rigKeyRef(channel)
            val changed=base.at(500,30).withEasing(ref,curve)
            assertEquals(curve,changed.easing(ref))
            val saved=Layer(XmlNode.parse(changed.node.toXml())).at(500,30)
            assertEquals(curve,saved.easing(ref))
            assertEquals(base.keyframes(ref).map {it.v},saved.keyframes(ref).map {it.v})
            assertEquals(base.keyframes(ref).map {it.t},saved.keyframes(ref).map {it.t})
            for (other in (0..3)-channel) assertEquals(base.element(base.rigKeyRef(other)),saved.element(base.rigKeyRef(other)))
        }
    }
}
