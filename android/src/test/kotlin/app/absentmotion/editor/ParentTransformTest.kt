package app.absentmotion.editor

import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ParentTransformTest {
    private fun scene()=Scene.parse("""<scene width="1080" height="1080" fps="60" totalTime="1999">
        <shape id="1" startTime="0" endTime="1999"><transform><location value="543.453003,985.293335,0"/><scale value="1,1.965694"/><pivot value="-0.159956,98.032616"/></transform></shape>
        <shape id="2" parent="1" startTime="0" endTime="1999"><transform><location value="3.453508,-96.638771,0"/><scale value="1,1"/><pivot value="-0.159956,98.032616"/><rotation value="45"/></transform></shape>
        <bone id="3" startTime="0" endTime="1999"><transform><location value="200,300,0"/><rotation value="-27"/><scale value="1.4,.6"/></transform></bone></scene>""")
    private val points=listOf(Pt(0f,0f),Pt(100f,0f),Pt(0f,100f))
    private fun same(a: Affine,b: Affine) {
        for(p in points) {assertEquals(a.map(p).x,b.map(p).x,.002f);assertEquals(a.map(p).y,b.map(p).y,.002f)}
    }

    @Test fun rotatingAStretchedChildWithAnEdgeAnchorDoesNotIntroduceSkew() {
        val s=scene();val m=s.rigAffine(s.layer(2)!!,1000)
        assertEquals(0.0,m.a*m.c+m.b*m.d,1e-6)
        assertEquals(1.0,hypot(m.a,m.b),1e-6)
        assertEquals(1.965694,hypot(m.c,m.d),1e-6)
        val anchor=m.map(Pt(-.159956f,98.032616f))
        assertEquals(547.06647f,anchor.x,.002f)
        assertEquals(602.63025f,anchor.y,.002f)
    }

    @Test fun attachingStretchedArtworkToABonePreservesTheCompleteDisplayedTransform() {
        val s=scene();val before=s.rigAffine(s.layer(2)!!,1000)
        val bound=s.withRigParent(2,3,1000)
        same(before,bound.rigAffine(bound.layer(2)!!,1000))
        val compiled=Scene(bound.node.withNativeBones())
        same(before,compiled.rigAffine(compiled.layer(2)!!,1000))
        val helper=compiled.layer(compiled.layer(2)!!.node["parent"]!!.toLong())!!
        assertEquals("true",helper.node["trueAffineParent"])
        val detached=bound.withRigParent(2,null,1000)
        same(before,detached.rigAffine(detached.layer(2)!!,1000))
        assertEquals(s.layer(2)!!.node.child("transform"),bound.layer(2)!!.node.child("transform"))
    }

    @Test fun movingEitherAnchorKeepsTheArtAndItsChildrenInPlace() {
        val s=scene()
        for(id in listOf(1L,2L)) {
            val after=s.withPivotMoved(id,1000,38f,-52f)
            for(child in listOf(1L,2L)) same(s.rigAffine(s.layer(child)!!,1000),after.rigAffine(after.layer(child)!!,1000))
        }
    }
}
