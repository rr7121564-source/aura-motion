package app.truemotion.port

import android.content.Context
import android.graphics.Bitmap
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.engine.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

internal object Text018Probe {
    fun run(context: Context,engine: NativeEngine,directory: File): JSONObject {
        val identity=EffectDefinition.parse(JSONObject("""{"id":"qa-text-identity","name":"Identity","category":"color","description":"QA.","params":[],"sksl":"uniform shader image; half4 main(float2 p) {return image.eval(p);}"}"""))
        check(engine.loadEffect(identity.runtimeXml())=="ok")
        fun scene(factor: Int,world: Boolean,fx: Boolean=false,stroke: Boolean=false): String = """<scene width="${256L*if(world) factor else 1}" height="${256L*if(world) factor else 1}" fps="30" totalTime="999" bgcolor="#00000000">
            <text id="1" size="${40L*factor}" wrapWidth="${600L*factor}" align="center" startTime="0" endTime="999" fillType="color"><content>AaO</content>
            <transform><location value="${128L*if(world) factor else 1},${128L*if(world) factor else 1},0"/><scale value="${if(world) 1.0 else 1.0/factor},${if(world) 1.0 else 1.0/factor}"/></transform><fillColor value="#ffffffff"/>
            ${if(fx) "<effect id=\"${identity.id}\"/>" else ""}
            ${if(stroke) "<path-stroke enabled=\"true\"><size value=\"${8L*if(world) factor else 1}\"/><color value=\"#ffff0000\"/></path-stroke>" else ""}</text></scene>"""
        fun draw(xml: String): ByteArray {engine.setScene(xml);return checkNotNull(engine.still(0.0,256,256))}
        fun save(bytes: ByteArray,name: String) {val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes));File(directory,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        val results=JSONArray()
        for(world in listOf(false,true)) for(factor in listOf(1,10,100,1000)) {
            val plain=draw(scene(factor,world));val filtered=draw(scene(factor,world,fx=true));val edged=draw(scene(factor,world,stroke=true))
            val prefix=if(world) "world-" else ""
            save(plain,"${prefix}plain-$factor");save(filtered,"${prefix}effect-$factor");save(edged,"${prefix}stroke-$factor")
            val distances=IntArray(256*256) {if((plain[it*4+3].toInt() and 255)>=16) 0 else 65535}
            for(y in 0..255) for(x in 0..255) {val i=y*256+x
                if(x>0) distances[i]=min(distances[i],distances[i-1]+1)
                if(y>0) {distances[i]=min(distances[i],distances[i-256]+1);if(x>0) distances[i]=min(distances[i],distances[i-257]+1);if(x<255) distances[i]=min(distances[i],distances[i-255]+1)}
            }
            for(y in 255 downTo 0) for(x in 255 downTo 0) {val i=y*256+x
                if(x<255) distances[i]=min(distances[i],distances[i+1]+1)
                if(y<255) {distances[i]=min(distances[i],distances[i+256]+1);if(x>0) distances[i]=min(distances[i],distances[i+255]+1);if(x<255) distances[i]=min(distances[i],distances[i+257]+1)}
            }
            var ink=0;var lost=0;var red=0;var stray=0;var maxDelta=0
            for(i in distances.indices) {
                val a=plain[i*4+3].toInt() and 255;val b=filtered[i*4+3].toInt() and 255
                if(a>16) ink++
                if(a>128 && b<32) lost++
                maxDelta=maxOf(maxDelta,abs(a-b))
                if((edged[i*4].toInt() and 255)>(edged[i*4+1].toInt() and 255)+32 && (edged[i*4+3].toInt() and 255)>32) {red++;if(distances[i]>7) stray++}
            }
            results.put(JSONObject().put("factor",factor).put("world",world).put("ink",ink).put("effectLostPixels",lost).put("effectMaxAlphaDelta",maxDelta).put("strokeRedPixels",red).put("strokeStrayPixels",stray))
            check(ink>5000) {"Large text lost its glyph coverage: factor=$factor world=$world ink=$ink"}
            check(lost<=16) {"Text effect clipped glyphs: factor=$factor world=$world lost=$lost"}
            check(red>5000 && stray==0) {"Text stroke does not follow glyph ink: factor=$factor world=$world stray=$stray"}
        }
        val assist=EffectDefinition.parse(JSONObject(context.assets.open("effects/legacy-954728cb0c8b.json").bufferedReader().use {it.readText()}))
        check(engine.loadEffect(assist.runtimeXml())=="ok")
        val scales=JSONArray()
        for(axis in 0..3) {
            val fx=assist.instance().copy(children=listOf(
                app.absentmotion.model.XmlNode("property",mapOf("name" to "axis","type" to "float","value" to "$axis")),
                app.absentmotion.model.XmlNode("property",mapOf("name" to "scale","type" to "float","value" to "1.6")),
                app.absentmotion.model.XmlNode("property",mapOf("name" to "damp","type" to "float","value" to "0.5"))))
            draw("""<scene width="256" height="256" fps="30" totalTime="999"><shape id="1" s=".rect" startTime="0" endTime="999" fillType="color"><transform><location value="128,128,0"/><scale value="1.2,0.8"/></transform><property name="size" type="vec2" value="20,20"/>${fx.toXml().substringAfter('\n')}</shape></scene>""")
            val m=checkNotNull(engine.layerMatrix(1,0.0))
            val expectedX=if(axis==1) 1.2f else 1.92f
            val expectedY=when(axis) {0->.8f;1->1.28f;2->.64f;else->.56f}
            check(abs(m[0]-expectedX)<.001f && abs(m[4]-expectedY)<.001f) {"Scale Assist axis $axis did not change scale"}
            scales.put(JSONObject().put("axis",axis).put("x",m[0]).put("y",m[4]))
        }
        val raw="""{"env":{},"el":{"transform":{"scale":{"x":1.2,"y":0.8}}},"p":{"axis":2,"scale":1.6,"damp":0.5}}"""
        val scriptResult=String(LegacyRuntime.evaluate(assist.id.toByteArray(),assist.legacyScript.toByteArray(),raw.toByteArray()))
        return JSONObject().put("backend",JSONObject(engine.info)).put("text",results).put("scaleAssist",scales).put("legacyScaleRuntime",scriptResult)
    }
}
