package app.truemotion.port

import android.content.Context
import android.graphics.Bitmap
import app.absentmotion.effects.EffectCatalog
import app.absentmotion.engine.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.*

/** Spatial media-frame regression: blur may extend past a decoded frame's quad. */
internal object Edges0191Probe {
    fun run(context: Context, engine: NativeEngine, directory: File): JSONArray {
        val catalog=EffectCatalog.parse { context.assets.open("effects/$it") }
        for(id in listOf("absent.motionblur","absent.shutterblur")) check(engine.loadEffect(catalog.find(id)!!.runtimeXml())=="ok")
        val bitmap=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888).apply { eraseColor(0xffffffff.toInt()) }
        check(engine.setPicture("qa-edge-video",bitmap))
        bitmap.recycle()
        fun xml(effect: String)="""<scene width="256" height="256" fps="30" totalTime="999"><shape id="1" s=".rect" fillType="media" fillVideo="qa-edge-video" mediaFillMode="stretch" startTime="0" endTime="999"><transform><location><kf t="0" v="40,128,0"/><kf t="1" v="216,128,0"/></location></transform><property name="size" type="vec2" value="20,32"/>$effect</shape></scene>"""
        fun capture(effect: String,accurate: Boolean):ByteArray {
            engine.setScene(xml(effect))
            return checkNotNull(engine.previewStill(500.0,256,256,accurate))
        }
        val source=capture("",true)
        val covered=(3 until source.size step 4).count { (source[it].toInt() and 255)>128 }
        check(covered>500) {"Video-frame fixture was not drawn: $covered"}
        val report=JSONArray()
        for(accurate in listOf(false,true)) for(id in listOf("absent.motionblur","absent.shutterblur")) {
            val effect=if(id.endsWith("motionblur")) """<effect id="$id"><property name="tune" type="float" value="4"/></effect>"""
                else """<effect id="$id"><property name="shutter" type="float" value="360"/><property name="samples" type="float" value="16"/></effect>"""
            val result=capture(effect,accurate)
            val quad=checkNotNull(engine.layerQuad(1,500.0))
            val left=quad.filterIndexed { i,_->i%2==0 }.min();val right=quad.filterIndexed { i,_->i%2==0 }.max()
            var outside=0
            for(y in 0..255) for(x in 0..255) if(x+.5f<left-1 || x+.5f>right+1) {
                if((result[(y*256+x)*4+3].toInt() and 255)>2) outside++
            }
            val image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            image.copyPixelsFromBuffer(ByteBuffer.wrap(result))
            File(directory,"media-${id.substringAfter('.')}-${if(accurate) "full" else "fast"}.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
            check(outside>10) {"$id was clipped to the decoded media quad; accurate=$accurate outside=$outside"}
            report.put(JSONObject().put("effect",id).put("accurate",accurate).put("outsideQuadPixels",outside))
        }
        return report
    }
}
