package app.truemotion.port

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import app.absentmotion.engine.NativeEngine
import app.absentmotion.engine.PreviewView
import app.absentmotion.editor.*
import app.absentmotion.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import kotlin.math.*

/** Regression for a moved anchor, a stretched parent and an independently rotating child. */
internal object Parent018Probe {
    private fun Layer.constant(name: String, vararg values: Float): Layer = withElement(
        PropRef(PropRef.Kind.Transform,name),XmlNode(name,mapOf("value" to formatFloats(values))))
    private const val Fixture = """<scene width="1080" height="1080" fps="60" totalTime="1999" bgcolor="#ff000000">
        <shape id="67158861" s=".roundrect" startTime="0" endTime="1999" fillType="color"><transform><location value="543.453003,985.293335,0"/><scale value="1,1.965694"/><pivot value="-0.159956,98.032616"/></transform><fillColor value="#ffa5d794"/><property name="size" type="vec2" value="100,100"/><property name="cornerRadius" type="float" value="25"/></shape>
        <shape id="67158862" parent="67158861" s=".roundrect" startTime="0" endTime="1999" fillType="color"><transform><location value="3.453508,-96.638771,0"/><scale value="1,1"/><pivot value="-0.159956,98.032616"/><rotation><kf t="0.004002" v="-10.580745"/><kf t="0.995998" v="148.638992"/></rotation></transform><fillColor value="#ffa5d794"/><property name="size" type="vec2" value="100,100"/><property name="cornerRadius" type="float" value="25"/></shape></scene>"""

    fun run(context: Context, engine: NativeEngine, directory: File): JSONObject {
        val input = File(context.filesDir, "parent018-input.alight")
        val source = Scene.parse(if (input.isFile) ZipFile(input).use { zip -> zip.getInputStream(zip.getEntry("scene.xml")).bufferedReader().use { it.readText() } } else Fixture)
        val child = source.layers.single { it.node["parent"] != null }
        val parent = checkNotNull(source.layer(child.node["parent"]!!.toLong()))
        val results = JSONArray()
        var worstShear = 0.0
        var worstMatrix = 0.0
        var worstPixels = 0
        var worstHandles = 0.0
        fun draw(scene: Scene, ms: Int): ByteArray {
            engine.setScene(scene.toXml())
            return checkNotNull(engine.still(ms.toDouble(), 360, 360))
        }
        fun save(pixels: ByteArray, name: String) {
            val image = Bitmap.createBitmap(360,360,Bitmap.Config.ARGB_8888)
            image.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            File(directory,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
        }
        fun measure(scene: Scene, ms: Int, name: String, save: Boolean = false) {
            val p = scene.layerAt(parent.id,ms)!!
            val c = scene.layerAt(child.id,ms)!!
            val pr = Math.toRadians(p.transform("rotation",0f)[0].toDouble())
            val cr = Math.toRadians(c.transform("rotation",0f)[0].toDouble())
            val x = (c.location[0]-p.pivot[0])*p.scale[0]
            val y = (c.location[1]-p.pivot[1])*p.scale[1]
            val anchorX = p.location[0]+cos(pr)*x-sin(pr)*y
            val anchorY = p.location[1]+sin(pr)*x+cos(pr)*y
            val sx = p.scale[0]*c.scale[0]
            val sy = p.scale[1]*c.scale[1]
            val angle = pr+cr
            val a = cos(angle)*sx; val b = sin(angle)*sx
            val cc = -sin(angle)*sy; val d = cos(angle)*sy
            val tx = anchorX-a*c.pivot[0]-cc*c.pivot[1]
            val ty = anchorY-b*c.pivot[0]-d*c.pivot[1]
            val expected = doubleArrayOf(a,cc,tx,b,d,ty,0.0,0.0,1.0)
            val actualPixels = draw(scene.update(parent.id) { it.copy(node=it.node.with("hidden","true")) },ms)
            val matrix = checkNotNull(engine.layerMatrix(child.id,ms.toDouble()))
            val ui=checkNotNull(scene.worldMatrix(child.id,ms))
            val uiValues=FloatArray(9).also {ui.getValues(it)}
            worstHandles=max(worstHandles,(0..8).maxOf {abs(uiValues[it].toDouble()-matrix[it])})
            val shear = abs(matrix[0]*matrix[1]+matrix[3]*matrix[4])/max(1e-12,hypot(matrix[0].toDouble(),matrix[3].toDouble())*hypot(matrix[1].toDouble(),matrix[4].toDouble()))
            val error = expected.indices.maxOf { abs(expected[it]-matrix[it]) }
            val reference = scene.update(child.id) {
                it.copy(node=it.node.with("parent",null)).constant("location",anchorX.toFloat(),anchorY.toFloat(),0f)
                    .constant("rotation",Math.toDegrees(angle).toFloat()).constant("scale",sx,sy)
            }.update(parent.id) { it.copy(node=it.node.with("hidden","true")) }
            val referencePixels = draw(reference,ms)
            val changed = actualPixels.indices.count { abs((actualPixels[it].toInt() and 255)-(referencePixels[it].toInt() and 255))>2 }
            if(save) {save(actualPixels,"$name-actual");save(referencePixels,"$name-reference")}
            worstShear=max(worstShear,shear);worstMatrix=max(worstMatrix,error);worstPixels=max(worstPixels,changed)
            results.put(JSONObject().put("case",name).put("ms",ms).put("shear",shear).put("matrixError",error).put("changedChannels",changed))
        }
        for(ms in listOf(8,333,700,1000,1400,1800,1991)) measure(source,ms,"project-$ms",ms==700)
        for((sx,sy) in listOf(1f to 1.965694f,2.4f to .6f,-1.3f to .7f)) {
            for(pa in listOf(-45f,0f,67f)) for(ca in listOf(-90f,-30f,0f,45f,135f)) {
                val scene=source.update(parent.id) {it.constant("rotation",pa).constant("scale",sx,sy)}
                    .update(child.id) {it.constant("rotation",ca)}
                measure(scene,1000,"scale-$sx-$sy-parent-$pa-child-$ca")
            }
        }
        var worstPivotDrift=0.0
        val pivots=JSONArray()
        for(id in listOf(parent.id,child.id)) {
            val moved=source.withPivotMoved(id,700,38f,-52f)
            for(ms in listOf(8,700,1400,1991)) {
                draw(source,ms);val before=checkNotNull(engine.layerMatrix(child.id,ms.toDouble()))
                draw(moved,ms);val after=checkNotNull(engine.layerMatrix(child.id,ms.toDouble()))
                val drift=(0..8).maxOf {abs(before[it].toDouble()-after[it])}
                worstPivotDrift=max(worstPivotDrift,drift)
                pivots.put(JSONObject().put("layer",id).put("ms",ms).put("drift",drift))
            }
        }
        val rigStress=stressBindings(engine,source,parent.id,child.id)
        return JSONObject().put("backend",JSONObject(engine.info)).put("actualUserProject",input.isFile)
            .put("cases",results).put("worstShear",worstShear).put("worstMatrixError",worstMatrix).put("worstChangedChannels",worstPixels)
            // Animated pivot compensation uses the editor's existing 1 px adaptive bake tolerance.
            .put("handleMatrixError",worstHandles).put("pivotEditDrift",worstPivotDrift).put("pivotBakeTolerance",1.0).put("pivotCases",pivots).put("rigStress",rigStress)
            .put("ok",worstShear<1e-5 && worstMatrix<.002 && worstPixels<48 && worstHandles<.002 && worstPivotDrift<1.0)
    }

    private fun stressBindings(engine: NativeEngine,source: Scene,parentId: Long,childId: Long): JSONObject {
        val thread=HandlerThread("qa-rig-present").apply {start()}
        val reader=ImageReader.newInstance(360,360,PixelFormat.RGBA_8888,3)
        reader.setOnImageAvailableListener({r->r.acquireLatestImage()?.close()},Handler(thread.looper))
        var target=0L
        var worstBind=0.0
        try {
            target=engine.attach(reader.surface,false)
            repeat(80) { i ->
                val id=1000L+i*2
                val before=source.rigAffine(source.layer(childId)!!,700)
                var s=source.withDrawnBone(childId,null,Pt(500f,600f),Pt(610f,520f),id,700)
                    .withDrawnBone(parentId,null,Pt(540f,980f),Pt(560f,850f),id+1,700)
                s=s.withRigParent(childId,id+1,700)
                engine.setScene(s.toXml());check(engine.still(700.0,360,360)!=null)
                val m=checkNotNull(engine.layerMatrix(childId,700.0))
                val expected=doubleArrayOf(before.a,before.c,before.tx,before.b,before.d,before.ty,0.0,0.0,1.0)
                worstBind=max(worstBind,expected.indices.maxOf {abs(expected[it]-m[it])})
                for(angle in listOf(-35f,0f,70f)) {
                    val turned=s.update(id+1) {it.withTransform("rotation",angle)}
                    engine.setScene(turned.toXml())
                    val view=PreviewView(700,1f/3,0f,0f,childId)
                    check(engine.renderScene(target,700.0,view.scale,0f,0f,0xff151722.toInt(),childId,8,view)==0) {"Rig preview presentation failed"}
                }
            }
            check(worstBind<.004) {"Binding moved stretched anchored artwork by $worstBind"}
            return JSONObject().put("createdBones",160).put("rebindings",80).put("presentedFrames",240).put("worstBindMatrixError",worstBind)
        } finally {
            if(target!=0L) engine.detach(target)
            reader.setOnImageAvailableListener(null,null)
            thread.quitSafely();thread.join(2000)
            reader.close()
        }
    }
}
