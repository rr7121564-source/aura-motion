package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class RigTimingTest {
    private fun bone(id: Long, parent: Long? = null, end: Int = 999): XmlNode {
        val attrs=buildMap {
            put("id", "$id");put("startTime", "0");put("endTime", "$end");put("length", "60")
            parent?.let { put("parent", "$it") }
        }
        val rotation=XmlNode("rotation").withKeyframes(listOf(Keyframe(0f,"10"),Keyframe(1f,"70")))
        val target=XmlNode("point").withKeyframes(listOf(Keyframe(0f,"120,80"),Keyframe(1f,"160,100")))
        return XmlNode("bone",attrs,listOf(XmlNode("transform",children=listOf(rotation)),XmlNode("trueRigTarget",children=listOf(target))))
    }
    private fun part(id: Long, parent: Long, start: Int, end: Int) = XmlNode("shape", mapOf("id" to "$id", "parent" to "$parent", "startTime" to "$start", "endTime" to "$end"))
    private fun scene(vararg n: XmlNode) = Scene(XmlNode("scene", mapOf("width" to "256","height" to "256","fps" to "30","totalTime" to "4999"), n.toList()))

    @Test fun extendingArtworkExtendsTheWholeSkeletonAndKeepsAbsoluteKeyTimes() {
        val source=scene(bone(10),bone(11,10),bone(12,11),part(1,11,0,4999))
        val out=source.withRigSpans()
        assertEquals(listOf(4999,4999,4999),out.bones.map {it.endTime})
        for(ms in listOf(0,250,500,999)) for(id in 10L..12L) {
            val before=source.layer(id)!!.at(ms,source.fps)
            val after=out.layer(id)!!.at(ms,out.fps)
            assertEquals(before.transform("rotation",0f)[0],after.transform("rotation",0f)[0],.001f)
            assertEquals(before.node.child("trueRigTarget")!!.child("point")!!.valueAt(before.t)!!.split(',')[0].toFloat(),
                after.node.child("trueRigTarget")!!.child("point")!!.valueAt(after.t)!!.split(',')[0].toFloat(),.001f)
        }
        assertEquals(70f,out.layer(12)!!.at(4000,30).transform("rotation",0f)[0],.001f)
        assertEquals(out,out.withRigSpans())
    }

    @Test fun sharedRigUsesEveryPartsRangeAndLeavesUnrelatedBonesAlone() {
        val source=scene(bone(10),bone(11,10),bone(20,end=600),part(1,11,-500,1999),part(2,10,250,3999))
        val out=source.withRigSpans()
        assertEquals(-500,out.layer(10)!!.startTime);assertEquals(3999,out.layer(11)!!.endTime)
        assertEquals(source.layer(20),out.layer(20))
        assertEquals(source.layer(1),out.layer(1));assertEquals(source.layer(2),out.layer(2))
    }

    @Test fun groupsAndBindingHelpersKeepTheCorrectLocalClock() {
        val inner=scene(bone(10),bone(11,10),XmlNode("nullobj",mapOf("id" to "30","parent" to "11","startTime" to "0","endTime" to "999")),part(1,30,0,2999))
        val group=XmlNode("embedScene",mapOf("id" to "100","startTime" to "2000","endTime" to "4999"),listOf(inner.node))
        val out=scene(group).withRigSpans()
        assertEquals(2999,out.layer(100)!!.nested!!.layer(11)!!.endTime)
        assertEquals(group.attrs,out.layer(100)!!.node.attrs)
        val native=out.node.withNativeBones()
        assertEquals("2999",native.child("embedScene")!!.child("scene")!!.children.first {it["id"]=="11"}["endTime"])
    }

    @Test fun malformedCyclesTerminateWithoutDroppingArtwork() {
        val source=scene(bone(10,11),bone(11,10),part(1,10,0,2999))
        assertEquals(source.layers,source.withRigSpans().layers)
    }
}
