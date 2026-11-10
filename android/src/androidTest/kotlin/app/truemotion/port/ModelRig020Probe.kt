package app.truemotion.port

import android.content.Context
import android.graphics.Bitmap
import app.absentmotion.engine.NativeEngine
import app.absentmotion.media.parseModel
import app.absentmotion.model.Scene
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.*

internal object ModelRig020Probe {
    fun run(context: Context, engine: NativeEngine, directory: File): JSONObject {
        val report=JSONObject();val failures=JSONArray()
        fun test(name: String, body: () -> Any) { try { report.put(name,body()) } catch(t: Throwable) { failures.put("$name: ${t.stackTraceToString()}") } }
        val assets=context.createPackageContext("${context.packageName}.test",0).assets
        val mesh=parseModel("TwoBone.glb",assets.open("rig020/TwoBone.glb").use { it.readBytes() }) { null }
        val uri="qa-020-skinned"
        check(engine.setModel(uri,mesh))
        fun scene(clip: Int,extra: String="",end: Int=1999)="""<scene width="512" height="512" fps="30" totalTime="$end" depthOrder="true" depthSort="piece"><model id="1" src="$uri" startTime="0" endTime="$end"><transform><location value="256,256,0"/></transform><property name="size" type="float" value="110"/><property name="modelClip" type="float" value="$clip"/>$extra</model></scene>"""
        fun pose(clip: Int,ms: Double,extra: String=""): FloatArray {engine.setScene(scene(clip,extra));return checkNotNull(engine.modelPose(1,ms,false))}
        fun close(a: FloatArray,b: FloatArray): Float {check(a.size==b.size);val error=a.indices.maxOf { abs(a[it]-b[it]) };check(error<.00003f) { "Skin transform error $error" };return error}
        fun translated(dx: Float)=FloatArray(mesh.positions.size) { mesh.positions[it]+if(it%3==0) dx/sqrt(1.25f) else 0f }
        fun bent(degrees: Float): FloatArray {
            val r=checkNotNull(mesh.rig);val angle=degrees*PI.toFloat()/180;val c=cos(angle);val s=sin(angle);val radius=sqrt(1.25f)
            return FloatArray(mesh.positions.size).also { out -> for(v in 0 until mesh.vertexCount) {
                val x=r.positions[v*3];val y=r.positions[v*3+1];var tip=0f
                repeat(8) { k -> if(r.influences[v*8+k]==1) tip+=r.weights[v*8+k] }
                val bx=1+c*(x-1)-s*(y-1);val by=1+s*(x-1)+c*(y-1)
                out[v*3]=(x+(bx-x)*tip)/radius;out[v*3+1]=(1-(y+(by-y)*tip))/radius;out[v*3+2]=0f
            } }
        }
        fun save(name: String,ms: Double) {
            val rgba=checkNotNull(engine.still(ms,512,512));check((3 until rgba.size step 4).count { (rgba[it].toInt() and 255)>128 }>100)
            val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        test("bindPoseAndSlerp") {
            val rest=close(pose(0,0.0),mesh.positions)
            val middle=close(pose(1,500.0),bent(45f));val end=close(pose(1,1000.0),bent(90f));save("glb-bend",1000.0)
            JSONObject().put("restError",rest).put("midSlerpError",middle).put("bentError",end)
        }
        test("stepCubicAntipodalAndPlayback") {
            close(pose(2,500.0),mesh.positions);close(pose(2,1000.0),translated(1f))
            close(pose(3,500.0),translated(.75f));close(pose(4,500.0),mesh.positions)
            close(pose(1,250.0,"""<property name="modelAnimSpeed" type="float" value="2"/><property name="modelAnimOffset" type="float" value="0.25"/>"""),bent(67.5f))
            close(pose(1,2500.0),bent(45f))
            val clamped=pose(3,1500.0,"""<property name="modelAnimLoop" type="float" value="0"/>""")
            close(clamped,translated(1f));JSONObject().put("step",true).put("cubicHermite",true).put("quaternionShortArc",true).put("speedOffsetLoopClamp",true)
        }
        test("ownBoneKeysReopenAndCacheInvalidation") {
            val key="""<property name="bone2.rotate" type="vec3"><kf t="0" v="0,0,0"/><kf t="1" v="0,0,90"/></property>"""
            val own=pose(0,999.5,key);close(own,bent(45f));save("glb-own-keys",999.5)
            val xml=Scene.parse(scene(0,key)).node.toXml();engine.setScene(Scene.parse(xml).node.toXml());close(checkNotNull(engine.modelPose(1,999.5,false)),own)
            // Change the scene at the same clock value: a pose from the previous layer must not leak.
            close(pose(0,999.5),mesh.positions)
            val animated=pose(1,500.0,"""<property name="bone2.rotate" type="vec3" value="0,0,45"/>""");close(animated,bent(90f))
            val joints=checkNotNull(engine.modelPose(1,500.0));check(joints.size==12 && joints.all(Float::isFinite))
            JSONObject().put("ownKeys",true).put("clipPlusOwnPose",true).put("saveReopen",true).put("sameTimeInvalidation",true)
        }
        test("officialRiggedModelsAndRepeatedFrames") {
            val cases=JSONArray()
            for(name in listOf("RiggedSimple","Fox")) {
                val model=parseModel("$name.glb",assets.open("rig020/$name.glb").use { it.readBytes() }) { null }
                check(engine.setModel(uri,model));engine.setScene(scene(1))
                val first=checkNotNull(engine.modelPose(1,0.0,false));val middle=checkNotNull(engine.modelPose(1,500.0,false))
                check(first.all(Float::isFinite) && middle.all(Float::isFinite) && !first.contentEquals(middle)) { "$name skin did not animate" }
                repeat(60) { frame -> val p=checkNotNull(engine.modelPose(1,frame*33.0,false));check(p.all(Float::isFinite));if(frame%10==0) checkNotNull(engine.still(frame*33.0,256,256)) }
                save("glb-${name.lowercase()}",500.0)
                cases.put(JSONObject().put("name",name).put("bones",model.rig!!.nodes.count { it.info.joint }).put("clips",model.rig.clips.size).put("triangles",model.triangleCount))
            }
            cases
        }
        engine.setModel(uri,null)
        return report.put("failures",failures).put("ok",failures.length()==0)
    }
}
