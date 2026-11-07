package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class UngroupTest {
    private fun scene()=Scene.parse("""<scene width="800" height="600" fps="30" totalTime="1999">
        <embedScene id="10" label="Group" startTime="200" endTime="1199"><transform><location><kf t="0" v="50,100,0"/><kf t="1" v="250,300,0"/></location><rotation value="35"/><scale value="-1.5,.8"/><skew value=".2,0"/></transform>
        <scene width="400" height="300" fps="30" totalTime="999" retime="off">
        <shape id="1" label="Vector" s=".rect" startTime="0" endTime="999"><transform><location value="70,80,0"/><rotation value="15"/></transform><property name="size" type="vec2" value="40,50"/></shape>
        <shape id="2" label="Child" parent="1" s=".ellipse" startTime="0" endTime="999"><transform><location value="20,10,0"/></transform></shape>
        </scene></embedScene></scene>""")

    @Test fun transformedAnimatedGroupUngroupsWithoutMovingOrRasterizingArtwork() {
        val s=scene();val group=s.layer(10)!!;val inner=group.nested!!
        val (after,ids)=s.ungrouped(10)!!
        assertEquals(2,ids.size)
        assertEquals("nullobj",after.layer(10)!!.kind)
        val art=after.layers.single {it.label=="Vector"}
        val child=after.layers.single {it.label=="Child"}
        assertEquals("${art.id}",child.node["parent"])
        assertEquals(".rect",art.shape)
        assertNull(art.mediaFill)
        for(ms in listOf(200,500,1100)) for((old,new) in listOf(1L to art,2L to child)) {
            val expected=s.rigAffine(group,ms)*Affine.translate(-200.0,-150.0)*inner.rigAffine(inner.layer(old)!!,ms-200)
            val actual=after.rigAffine(new,ms)
            for(p in listOf(Pt(0f,0f),Pt(30f,-20f))) {
                assertEquals(expected.map(p).x,actual.map(p).x,.002f)
                assertEquals(expected.map(p).y,actual.map(p).y,.002f)
            }
        }
    }

    @Test fun masksAndCompositingAreNeverSilentlyChanged() {
        val s=scene().update(10) {it.addEffect(XmlNode("effect",mapOf("id" to "absent.blur")))}
        assertNotNull(s.layer(10)!!.ungroupProblem())
        assertNull(s.ungrouped(10))
    }

    @Test fun explicitRetimingNeedsToBeRestoredBeforeUngrouping() {
        val s=scene().update(10) {it.copy(node=it.node.withChild("scene",it.nested!!.node.with("retime","loop")))}
        assertNotNull(s.layer(10)!!.ungroupProblem())
    }
}
