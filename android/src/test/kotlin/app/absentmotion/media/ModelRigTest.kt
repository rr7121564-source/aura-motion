package app.absentmotion.media

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs

class ModelRigTest {
    private fun file(name: String) = File("src/androidTest/assets/rig020/$name.glb").readBytes()
    private fun mesh(name: String = "TwoBone") = parseModel("$name.glb", file(name)) { null }
    private fun document(): JSONObject {
        val bytes = file("TwoBone"); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val size = b.getInt(12); val json = JSONObject(String(bytes,20,size))
        val bin = bytes.copyOfRange(28+size,bytes.size)
        json.getJSONArray("buffers").getJSONObject(0).put("uri","data:application/octet-stream;base64,"+Base64.getEncoder().encodeToString(bin))
        return json
    }
    @Test fun retainsSkinIndicesWeightsAncestorsAndAllAnimationInterpolationModes() {
        val m=mesh();val rig=checkNotNull(m.rig)
        assertEquals(6,m.vertexCount);assertEquals(4,m.triangleCount)
        assertEquals(listOf("Root","Tip"),rig.nodes.filter { it.info.joint }.map { it.info.name })
        assertEquals(3,rig.nodes[1].info.parent);assertEquals(1,rig.nodes[2].info.parent)
        assertEquals(listOf("Bend","Step","Cubic","Antipodal"),rig.clips.map { it.name })
        assertEquals(listOf(1,0,2,1),rig.clips.map { it.channels.single().interpolation })
        assertEquals(listOf(1,2),rig.bindings.map { it.node })
        repeat(m.vertexCount) { v -> assertEquals(1f,rig.weights.sliceArray(v*8 until v*8+8).sum(),.000001f) }
        assertTrue(rig.influences.any { it==1 });assertTrue(rig.weights.any { abs(it-127/255f)<.000001f })
        assertTrue(rig.packed().size>1000)
    }
    @Test fun skinMeshNodeTransformIsIgnoredAndMissingNormalsRemainSkinnable() {
        val original=mesh()
        val d=document();d.getJSONArray("nodes").getJSONObject(0).remove("translation")
        val noMeshTransform=parseModel("same.gltf",d.toString().toByteArray()) { null }
        assertArrayEquals(original.positions,noMeshTransform.positions,.000001f)
        d.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0).getJSONObject("attributes").remove("NORMAL")
        val generated=parseModel("generated.gltf",d.toString().toByteArray()) { null }
        assertNotNull(generated.rig);assertTrue(generated.normals.all(Float::isFinite))
        assertTrue(checkNotNull(generated.rig).normals.any { abs(it)>.9f })
    }
    @Test fun geometryBudgetPreservesEveryRetainedSkinAttribute() {
        val original=mesh();val result=decimated(original,2);val rig=checkNotNull(result.rig)
        assertEquals(2,result.triangleCount);assertEquals(result.vertexCount*8,rig.weights.size)
        for(v in 0 until result.vertexCount) {
            val source=(0 until original.vertexCount).first { o -> (0..2).all { result.positions[v*3+it]==original.positions[o*3+it] } }
            assertArrayEquals(checkNotNull(original.rig).positions.copyOfRange(source*3,source*3+3),rig.positions.copyOfRange(v*3,v*3+3),0f)
            assertArrayEquals(original.rig.weights.copyOfRange(source*8,source*8+8),rig.weights.copyOfRange(v*8,v*8+8),0f)
        }
    }
    @Test fun officialRiggedModelsKeepSkeletonAndClips() {
        for(name in listOf("RiggedSimple","Fox")) {
            val m=mesh(name);val rig=checkNotNull(m.rig)
            assertTrue(rig.nodes.count { it.info.joint }>=2);assertTrue(rig.clips.isNotEmpty())
            assertTrue(rig.positions.all(Float::isFinite));assertEquals(m.vertexCount*8,rig.influences.size)
        }
    }
    @Test fun cyclicSkeletonsAndMalformedAnimationCountsAreRejected() {
        val cycle=document();cycle.getJSONArray("nodes").getJSONObject(2).put("children",org.json.JSONArray("[3]"))
        assertThrows(IllegalArgumentException::class.java) { parseModel("cycle.gltf",cycle.toString().toByteArray()) { null } }
        val wrong=document();wrong.getJSONArray("animations").getJSONObject(0).getJSONArray("samplers").getJSONObject(0).put("output",0)
        assertThrows(IllegalArgumentException::class.java) { parseModel("wrong.gltf",wrong.toString().toByteArray()) { null } }
    }
}
