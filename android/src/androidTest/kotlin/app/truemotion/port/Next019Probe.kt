package app.truemotion.port

import android.content.Context
import android.graphics.Bitmap
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.engine.NativeEngine
import app.absentmotion.media.Mesh
import app.absentmotion.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.*

internal object Next019Probe {
    fun run(context: Context,engine: NativeEngine,directory: File): JSONObject {
        val report=JSONObject().put("backend",JSONObject(engine.info))
        val failures=JSONArray()
        fun test(name: String,body: ()->Any) {try {report.put(name,body())} catch(t: Throwable) {failures.put("$name: ${t.stackTraceToString()}")}}
        fun draw(xml: String): ByteArray {engine.setScene(xml);return checkNotNull(engine.still(0.0,256,256))}
        fun save(pixels: ByteArray,name: String) {
            val b=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);b.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            File(directory,"$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
        }
        test("nativeParentingHelper") {
            val definition=EffectCatalog.parse {context.assets.open("effects/$it")}.find("absent.parentinghelper")!!
            check(engine.loadEffect(definition.runtimeXml())=="ok")
            draw("""<scene width="256" height="256" fps="30" totalTime="999"><nullobj id="1" startTime="0" endTime="999"><transform><location value="100,100,0"/><rotation value="90"/><scale value="2,2"/></transform></nullobj><shape id="2" parent="1" s=".rect" startTime="0" endTime="999"><transform><location value="20,0,0"/><rotation value="10"/></transform><property name="size" type="vec2" value="10,10"/><effect id="absent.parentinghelper"><property name="rotateMode" type="float" value="2"/><property name="rotateWeight" type="float" value="2"/><property name="scaleMode" type="float" value="2"/><property name="scaleWeight" type="float" value="2"/></effect></shape></scene>""")
            val m=checkNotNull(engine.layerMatrix(2,0.0))
            val size=hypot(m[0],m[3]);val angle=Math.toDegrees(atan2(m[3].toDouble(),m[0].toDouble()))
            check(abs(size-3)<.001 && abs(angle+170)<.01) {"Native inheritance: size=$size angle=$angle"}
            JSONObject().put("scale",size).put("angle",angle)
        }
        test("legacyStateJson") {
            val definition=EffectDefinition.parse(JSONObject("""{"id":"qa-state","runtimeId":"tmold.qa.state","name":"QA state","category":"legacy-transform","dialect":"legacy","legacyScript":"function animate(env,el,p) { el.transform.location.x += p.amount; return el; }","params":[{"id":"amount","label":"Amount","type":"float","default":20,"min":0,"max":100}],"sksl":"uniform shader image; half4 main(float2 p) { return image.eval(p); }"}"""))
            check(engine.loadEffect(definition.runtimeXml())=="ok")
            val p=draw("""<scene width="256" height="256" fps="30" totalTime="999"><shape id="1" s=".rect" fillType="color" startTime="0" endTime="999"><transform><location value="100,100,0"/></transform><property name="size" type="vec2" value="20,10"/><fillColor value="#ffffffff"/><effect id="tmold.qa.state"><property name="amount" type="float" value="20"/></effect></shape></scene>""")
            val m=checkNotNull(engine.layerMatrix(1,0.0));check(abs(m[2]-120)<.001) {"Native legacy state was not evaluated: x=${m[2]}"}
            save(p,"legacy-state");true
        }
        test("depthOrder") {
            val pixels=draw("""<scene width="256" height="256" fps="30" totalTime="999" depthOrder="true" depthSort="piece"><shape id="1" s=".rect" threeD="true" startTime="0" endTime="999" fillType="color"><transform><location value="128,128,0"/></transform><property name="size" type="vec2" value="80,80"/><fillColor value="#ffff0000"/></shape><shape id="2" s=".rect" threeD="true" startTime="0" endTime="999" fillType="color"><transform><location value="128,128,100"/></transform><property name="size" type="vec2" value="80,80"/><fillColor value="#ff0000ff"/></shape></scene>""")
            val index=(128*256+128)*4;check((pixels[index].toInt() and 255)>230 && (pixels[index+2].toInt() and 255)<20) {"5.0 depth order followed the layer stack"}
            save(pixels,"depth-order");true
        }
        test("ambientLight") {
            val mesh=Mesh(floatArrayOf(-.3f,-.3f,0f,.3f,-.3f,0f,0f,.3f,0f),floatArrayOf(0f,0f,-1f,0f,0f,-1f,0f,0f,-1f),FloatArray(6),IntArray(3) {-1},intArrayOf(0,1,2),intArrayOf(-1),emptyList(),true)
            check(engine.setModel("qa-019-triangle",mesh))
            fun model(color: String)="""<scene width="256" height="256" fps="30" totalTime="999" depthOrder="true" depthSort="piece"><model id="1" src="qa-019-triangle" startTime="0" endTime="999"><transform><location value="128,128,0"/></transform></model><light id="2" type="ambient" startTime="0" endTime="999"><property name="color" type="color" value="$color"/><property name="intensity" type="float" value="1"/></light></scene>"""
            val white=draw(model("#ffffffff"));val red=draw(model("#ffff0000"))
            fun count(p: ByteArray,redOnly: Boolean)=(0 until p.size step 4).count {i->(p[i].toInt() and 255)>180 && if(redOnly) (p[i+1].toInt() and 255)<40 && (p[i+2].toInt() and 255)<40 else (p[i+1].toInt() and 255)>180 && (p[i+2].toInt() and 255)>180}
            val w=count(white,false);val r=count(red,true);save(white,"light-white");save(red,"light-red")
            check(w>100 && r>100) {"5.0 ambient light did not shade the model: white=$w red=$r"}
            JSONObject().put("whitePixels",w).put("redPixels",r)
        }
        test("mediaBlurEdges") { Edges0191Probe.run(context,engine,directory) }
        test("sameFramePinnedTarget") {
            val base="""<scene width="256" height="256" fps="30" totalTime="999"><bone id="10" length="60" startTime="0" endTime="999"><transform><location value="60,60,0"/><rotation value="25"/></transform></bone><bone id="11" parent="10" length="55" startTime="0" endTime="999"><transform><location value="60,0,0"/><rotation value="40"/></transform></bone><bone id="12" parent="11" length="45" startTime="0" endTime="999"><transform><location value="55,0,0"/><rotation value="20"/></transform><trueRigTarget><point value="%s"/></trueRigTarget></bone></scene>"""
            var worst=0f
            for(target in listOf(Pt(150f,150f),Pt(190f,90f),Pt(95f,170f))) {
                draw(base.format("${target.x},${target.y}"))
                val m=checkNotNull(engine.layerMatrix(12,0.0));val w=m[6]*45+m[8]
                val x=(m[0]*45+m[2])/w;val y=(m[3]*45+m[5])/w
                worst=max(worst,hypot(x-target.x,y-target.y))
            }
            check(worst<1.5f) {"IK kept an old target on the same frame: $worst px"}
            worst
        }
        return report.put("failures",failures).put("ok",failures.length()==0)
    }
}
