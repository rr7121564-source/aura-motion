package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RigToolsTest {
    private fun rig() = Scene.parse("""<scene width="600" height="400" fps="30" totalTime="1999">
        <bone id="1" length="100" startTime="0" endTime="1999"><transform><location value="120,100,0"/><rotation value="20"/></transform><trueRest><rotation value="20"/></trueRest></bone>
        <bone id="2" parent="1" length="100" startTime="0" endTime="1999"><transform><location value="100,0,0"/><rotation value="40"/></transform><trueRest><rotation value="40"/></trueRest></bone>
        <bone id="3" parent="2" length="80" startTime="0" endTime="1999"><transform><location value="100,0,0"/><rotation value="-20"/></transform><trueRest><rotation value="-20"/></trueRest></bone>
        </scene>""")

    @Test fun threeJointIkReachesTargetWithoutChangingRestLengths() {
        val before = rig()
        val target = Pt(250f,240f)
        val after = before.withRigIK(3,target,500,true,false)
        val end = after.rigSegment(after.layer(3)!!,500).second
        assertTrue("$end",hypot(end.x-target.x,end.y-target.y) < 1f)
        for (b in before.bones) assertEquals(b.rigLength,after.layer(b.id)!!.rigLength,.001f)
        assertEquals(before.layer(1)!!.transform("rotation",0f)[0],after.layer(1)!!.at(0,30).transform("rotation",0f)[0],.001f)
    }

    @Test fun ikHonorsJointLimitsAndChainRoot() {
        val s = rig().update(2) { it.copy(node=it.node.withChild("trueBoneOptions",BoneOptions(true,-5f,10f,stopIK=true).node())) }
            .update(3) { it.copy(node=it.node.withChild("trueBoneOptions",BoneOptions(true,-10f,15f).node())) }
        val after=s.withRigIK(3,Pt(50f,50f),500,false,false)
        assertEquals(s.layer(1)!!.node,after.layer(1)!!.node)
        assertTrue(after.layer(2)!!.transform("rotation",0f)[0] in 35f..50f)
        assertTrue(after.layer(3)!!.transform("rotation",0f)[0] in -30f..-5f)
    }

    @Test fun independentAngleKeepsFootUprightWhileRootTurns() {
        val s=rig().update(1) { it.withElement(PropRef.Rotation,XmlNode("rotation").withKeyframes(listOf(Keyframe(0f,"0"),Keyframe(1f,"90")))) }
            .update(2) { it.copy(node=it.node.withChild("trueBoneOptions",BoneOptions(independent=true).node())) }
        fun angle(ms: Int): Double {val m=s.rigAffine(s.layer(2)!!,ms);return Math.toDegrees(atan2(m.b,m.a))}
        assertEquals(40.0,angle(0),.001)
        assertEquals(40.0,angle(1500),.001)
        assertNotEquals(s.rigSegment(s.layer(2)!!,0).first,s.rigSegment(s.layer(2)!!,1500).first)
    }

    @Test fun lockedTipCannotBeMovedByIk() {
        val s=rig().update(3) {it.copy(node=it.node.withChild("trueBoneOptions",BoneOptions(locked=true).node()))}
        assertEquals(s,s.withRigIK(3,Pt(20f,30f),500,true,false))
    }

    @Test fun limitUsesRestAngleAndNearestTurn() {
        val b=rig().layer(1)!!.copy(node=rig().layer(1)!!.node.withChild("trueBoneOptions",BoneOptions(true,-30f,40f).node()))
        assertEquals(25f,b.constrainedAngle(385f),.001f)
        assertEquals(60f,b.constrainedAngle(140f),.001f)
        assertEquals(-10f,b.constrainedAngle(-90f),.001f)
    }

    @Test fun pinnedTargetAnimatesAndSurvivesProjectRoundTrip() {
        val bone=rig().layer(3)!!.copy(node=rig().layer(3)!!.node.withChild("trueRigTarget",XmlNode("trueRigTarget",children=listOf(XmlNode("point",mapOf("value" to "250,180"))))))
        val after=bone.at(1000,30).withRigTarget(Pt(280f,220f),true)
        val loaded=Layer(XmlNode.parse(after.node.toXml()))
        assertTrue(loaded.rigPinned)
        assertEquals(Pt(250f,180f),loaded.at(0,30).rigTarget())
        assertEquals(Pt(280f,220f),loaded.at(1000,30).rigTarget())
    }
}
