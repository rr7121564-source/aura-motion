package app.truemotion.port

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.engine.EngineThread
import app.absentmotion.engine.NativeEngine
import app.absentmotion.editor.ungrouped
import app.absentmotion.export.ExportSettings
import app.absentmotion.export.exportVideo
import app.absentmotion.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

internal object Next018Probe {
    fun run(context: Context, engine: NativeEngine, directory: File): JSONObject {
        val catalog=EffectCatalog.parse { context.assets.open("effects/$it") }
        val readerThreads=Thread.getAllStackTraces().keys.count {it.isAlive && it.name=="am-video-images"}
        repeat(8) {check(runCatching {app.absentmotion.media.decodedFrames(0,0,3)}.isFailure)}
        check(Thread.getAllStackTraces().keys.count {it.isAlive && it.name=="am-video-images"}==readerThreads) {"Failed readers leaked threads"}
        val invalid=app.absentmotion.media.Mesh(floatArrayOf(Float.NaN,0f,0f,1f,0f,0f,0f,1f,0f),FloatArray(9),FloatArray(6),IntArray(3),intArrayOf(0,1,2),intArrayOf(-1),emptyList(),false)
        check(!engine.setModel("qa018-invalid",invalid)) {"JNI accepted non-finite model vertices"}
        val helper=checkNotNull(catalog.find("absent.parenthelper"))
        check(engine.loadEffect(helper.runtimeXml()) == "ok")
        fun set(xml: String) { engine.setScene(xml);check(engine.still(0.0,256,256)!=null) }
        fun matrix(id: Long,ms: Double=0.0)=checkNotNull(engine.layerMatrix(id,ms))
        fun point(m: FloatArray,x: Float=0f,y: Float=0f): Pt {
            val w=m[6]*x+m[7]*y+m[8]
            return Pt((m[0]*x+m[1]*y+m[2])/w,(m[3]*x+m[4]*y+m[5])/w)
        }
        fun angle(m: FloatArray)=Math.toDegrees(atan2(m[3].toDouble(),m[0].toDouble()))
        fun close(a: Double,b: Double,epsilon: Double=.01) {check(abs(a-b)<epsilon) {"Expected $a, got $b"}}
        val parent="""<scene width="256" height="256" fps="30" totalTime="999" bgcolor="#ff14202a">
            <nullobj id="1" startTime="0" endTime="999"><transform><location value="100,70,0"/><rotation value="90"/><scale value="2,2"/></transform></nullobj>
            <shape id="2" parent="1" s=".rect" fillType="color" startTime="0" endTime="999"><transform><location value="40,0,0"/><rotation value="10"/></transform><property name="size" type="vec2" value="18,8"/><fillColor value="#ff4bd5bc"/>%s</shape></scene>"""
        fun effect(rotation: Int,scale: Int=0,rw: Float=1f,sw: Float=1f)="""<effect id="absent.parenthelper"><property name="rotateMode" type="float" value="$rotation"/><property name="scaleMode" type="float" value="$scale"/><property name="rotateWeight" type="float" value="$rw"/><property name="scaleWeight" type="float" value="$sw"/></effect>"""
        set(parent.format(""));val baseline=point(matrix(2))
        set(parent.format(effect(1)));close(10.0,angle(matrix(2)));check(point(matrix(2)).let {hypot(it.x-baseline.x,it.y-baseline.y)}<.001f)
        set(parent.format(effect(0,1)));close(1.0,hypot(matrix(2)[0].toDouble(),matrix(2)[3].toDouble()))
        set(parent.format(effect(2,2,2f,2f)));close(-170.0,angle(matrix(2)));close(4.0,hypot(matrix(2)[0].toDouble(),matrix(2)[3].toDouble()))
        // Exercise the real preset and a sheared ancestor matrix, not only a uniform parent.
        val keepSize=helper.instance(helper.presets.single {it.slug=="keep-size"},999).toXml().substringAfter('\n')
        val nestedParent="""<scene width="256" height="256" fps="30" totalTime="999">
            <nullobj id="1" startTime="0" endTime="999"><transform><location value="120,100,0"/><rotation value="35"/><scale value="3,1"/></transform></nullobj>
            <nullobj id="2" parent="1" startTime="0" endTime="999"><transform><location value="10,5,0"/><rotation value="20"/><scale value="1.5,0.75"/></transform></nullobj>
            <shape id="3" parent="2" s=".rect" fillType="color" startTime="0" endTime="999"><transform><rotation value="40"/><scale value="1.2,0.8"/></transform><property name="size" type="vec2" value="20,10"/>$keepSize</shape></scene>"""
        set(nestedParent)
        val sizeMatrix=matrix(3)
        close(1.2,hypot(sizeMatrix[0].toDouble(),sizeMatrix[3].toDouble()))
        close(.8,hypot(sizeMatrix[1].toDouble(),sizeMatrix[4].toDouble()))
        close(95.0,angle(sizeMatrix))
        val rig="""<scene width="256" height="256" fps="30" totalTime="999" bgcolor="#ff14202a">
            <bone id="10" length="60" startTime="0" endTime="999"><transform><location><kf t="0" v="60,60,0"/><kf t="1" v="100,60,0"/></location><rotation value="25"/></transform><trueRest><rotation value="25"/></trueRest></bone>
            <bone id="11" parent="10" length="55" startTime="0" endTime="999"><transform><location value="60,0,0"/><rotation value="40"/></transform><trueRest><rotation value="40"/></trueRest></bone>
            <bone id="12" parent="11" length="45" startTime="0" endTime="999"><transform><location value="55,0,0"/><rotation value="20"/></transform><trueRest><rotation value="20"/></trueRest><trueRigTarget><point value="150,150"/></trueRigTarget></bone>
            <shape id="20" parent="10" s=".rect" startTime="0" endTime="999" fillType="color"><transform><location value="30,0,0"/></transform><property name="size" type="vec2" value="30,6"/><fillColor value="#ff50d5bd"/></shape>
            <shape id="21" parent="11" s=".rect" startTime="0" endTime="999" fillType="color"><transform><location value="27.5,0,0"/></transform><property name="size" type="vec2" value="27.5,5"/><fillColor value="#ffffba68"/></shape>
            <shape id="22" parent="12" s=".rect" startTime="0" endTime="999" fillType="color"><transform><location value="22.5,0,0"/></transform><property name="size" type="vec2" value="22.5,4"/><fillColor value="#ffed558b"/></shape>
            </scene>"""
        set(rig)
        var worst=0f
        for(ms in listOf(0.0,100.0,500.0,950.0,250.0,700.0,0.0)) {
            val p=point(matrix(12,ms),45f)
            val error=hypot(p.x-150,p.y-150)
            worst=max(worst,error)
            check(error<1.5f) {"Pinned 3-joint IK at $ms moved by $error ($p)"}
        }
        val pinned=checkNotNull(engine.still(500.0,256,256))
        File(directory,"rig.xml").writeText(rig)
        val bitmap=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pinned))
        File(directory,"rig-pinned.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val constrained=Scene.parse(rig).update(12) {it.copy(node=it.node.withChild("trueRigTarget",null).withChild("trueBoneOptions",BoneOptions(limits=true,min=-5f,max=10f).node()))
            .withTransform("rotation",140f)}
        engine.setScene(constrained.toXml())
        val relative=(angle(matrix(12))-angle(matrix(11))+540).mod(360.0)-180
        close(30.0,relative)
        val independent=Scene.parse(rig).update(12) {it.copy(node=it.node.withChild("trueRigTarget",null).withChild("trueBoneOptions",BoneOptions(independent=true).node()))}
        engine.setScene(independent.toXml())
        close(20.0,angle(matrix(12,0.0)));close(20.0,angle(matrix(12,800.0)))
        val grouped=Scene.parse("""<scene width="256" height="256" totalTime="999" fps="30"><embedScene id="1" label="Group" startTime="0" endTime="999"><transform><location value="140,120,0"/><rotation value="20"/><scale value="1.3,.7"/></transform><scene width="128" height="128" fps="30" totalTime="999" retime="off"><shape id="2" s=".rect" fillType="color" startTime="0" endTime="999"><transform><location value="40,50,0"/></transform><property name="size" type="vec2" value="28,18"/><fillColor value="#ff4bd5bc"/></shape></scene></embedScene></scene>""")
        engine.setScene(grouped.toXml());val before=checkNotNull(engine.still(500.0,256,256))
        engine.setScene(checkNotNull(grouped.ungrouped(1)).first.toXml());val after=checkNotNull(engine.still(500.0,256,256))
        val maxDiff=before.indices.maxOf {abs((before[it].toInt() and 255)-(after[it].toInt() and 255))}
        check(maxDiff<=1) {"Ungrouping changed native pixels by $maxDiff"}
        val video=export(context,Scene.parse(rig),directory)
        return JSONObject().put("backend",JSONObject(engine.info)).put("failedReadersLeakNoThreads",true).put("jniRejectsInvalidMesh",true).put("parentHelperInheritance",true).put("threeBonePinnedWorstPx",worst)
            .put("parentHelperKeepSizeNestedParents",true).put("jointLimits",true).put("independentAngle",true).put("ungroupMaxChannelDifference",maxDiff).put("compatibleExport",video)
    }

    private fun export(context: Context,scene: Scene,directory: File): JSONObject = runBlocking {
        val engine=EngineThread(0,context)
        try {
            val counts=mutableListOf<Int>()
            repeat(3) { attempt ->
                val file=File(directory,"compatible-$attempt.mp4")
                FileOutputStream(file).use { out ->
                    exportVideo(engine,scene,scene.toXml(),ExportSettings(256,30,compatibleEncoder=true),out.fd,null,progress={})
                }
                val extractor=MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    val track=(0 until extractor.trackCount).first {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true}
                    extractor.selectTrack(track)
                    var count=0
                    while(extractor.sampleTime>=0) {count++;extractor.advance()}
                    check(count==30) {"Compatible export wrote $count frames"}
                    counts+=count
                } finally {extractor.release()}
            }
            JSONObject().put("runs",counts.size).put("framesPerRun",org.json.JSONArray(counts))
        } finally {engine.close()}
    }
}
