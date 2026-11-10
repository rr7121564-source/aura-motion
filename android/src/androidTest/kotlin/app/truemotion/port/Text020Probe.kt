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

internal object Text020Probe {
    fun run(context: Context, engine: NativeEngine, directory: File): JSONObject {
        val report = JSONObject().put("backend", JSONObject(engine.info))
        val failures = JSONArray()
        fun test(name: String, body: () -> Any) {
            try { report.put(name,body()) } catch (t: Throwable) { failures.put("$name: ${t.stackTraceToString()}") }
        }
        for (key in listOf("true-text-animator","true-text-spacing")) {
            val definition = EffectDefinition.parse(JSONObject(context.assets.open("effects/$key.json").bufferedReader().use { it.readText() }))
            check(engine.loadEffect(definition.runtimeXml()) == "ok")
        }
        fun scene(effect: String, content: String = "HHH", extra: String = "", size: Int = 18) =
            """<scene width="512" height="256" fps="30" totalTime="999" bgcolor="#00000000"><text id="1" size="$size" wrapWidth="420" align="center" startTime="0" endTime="999" fillType="color"><content>$content</content><transform><location value="256,128,0"/></transform><fillColor value="#ffffffff"/>$effect</text>$extra</scene>"""
        fun draw(xml: String, ms: Double = 0.0): ByteArray { engine.setScene(xml); return checkNotNull(engine.still(ms,512,256)) }
        fun save(bytes: ByteArray, name: String) {
            val bitmap=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888);bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        fun ink(bytes: ByteArray,x0: Int=0,x1: Int=511) = (0..255).sumOf { y -> (x0..x1).count { x -> (bytes[(y*512+x)*4+3].toInt() and 255)>128 } }
        test("independentCharacter") {
            val plain=draw(scene(""));val columns=(0..511).filter { x -> (0..255).any { y -> (plain[(y*512+x)*4+3].toInt() and 255)>128 } }
            val runs=ArrayList<IntRange>();var start=-1;var end=-1
            for(x in columns) {if(x>end+1 && start>=0){runs+=start..end;start=x};if(start<0)start=x;end=x};if(start>=0)runs+=start..end
            check(runs.size==3) { "Expected three separate shaped letters, got $runs" }
            val fx="""<effect id="truemotion.textanimator"><property name="selection" type="float" value="1"/><property name="first" type="float" value="2"/><property name="last" type="float" value="2"/><property name="opacity" type="float" value="0"/></effect>"""
            val hidden=draw(scene(fx));save(hidden,"one-character-hidden")
            check(ink(hidden,runs[0].first,runs[0].last)==ink(plain,runs[0].first,runs[0].last))
            check(ink(hidden,runs[1].first,runs[1].last)==0)
            check(ink(hidden,runs[2].first,runs[2].last)==ink(plain,runs[2].first,runs[2].last))
            val transformed=fx.replace("<property name=\"opacity\" type=\"float\" value=\"0\"/>","""<property name="scaleXY" type="vec2" value="1.7,0.6"/><property name="rotation" type="float" value="25"/><property name="position" type="vec2" value="0,-55"/>""")
            draw(scene("<effect id=\"truemotion.textanimator\"/>"));val old=checkNotNull(engine.layerBounds(1,0.0))
            val moved=draw(scene(transformed));val bounds=checkNotNull(engine.layerBounds(1,0.0));save(moved,"one-character-transformed")
            check(bounds[1]<old[1]-25 && ink(moved)>300) { "Selected character did not move/transform beyond the old text frame" }
            JSONObject().put("untouchedSideLetters",true).put("selectedLetterHidden",true).put("transformedTop",bounds[1]).put("previousTop",old[1])
        }
        test("keyframedSpacing") {
            val fx="""<effect id="truemotion.textspacing"><property name="letter" type="float"><kf t="0" v="0"/><kf t="1" v="20"/></property></effect>"""
            val xml=scene(fx);draw(xml)
            val initial=checkNotNull(engine.layerBounds(1,0.0))
            draw(xml,999.0);val last=checkNotNull(engine.layerBounds(1,999.0))
            draw(xml,499.5);val middle=checkNotNull(engine.layerBounds(1,499.5))
            fun width(b: FloatArray)=b[2]-b[0]
            check(abs(width(last)-width(initial)-40)<.1f)
            check(abs(width(middle)-width(initial)-20)<.1f)
            check(abs((last[0]+last[2])-(initial[0]+initial[2]))<.1f)
            save(checkNotNull(engine.still(999.0,512,256)),"spacing-expanded")
            JSONObject().put("initialWidth",width(initial)).put("middleWidth",width(middle)).put("endWidth",width(last)).put("centerHeld",true)
        }
        test("wordAndLineSpacing") {
            fun fx(word: Int,line: Int)="""<effect id="truemotion.textspacing"><property name="word" type="float" value="$word"/><property name="line" type="float" value="$line"/></effect>"""
            draw(scene(fx(0,0),"H H\nH H".replace("\\n","\n")));val a=checkNotNull(engine.layerBounds(1,0.0))
            val p=draw(scene(fx(30,20),"H H\nH H".replace("\\n","\n")));val b=checkNotNull(engine.layerBounds(1,0.0))
            check(abs((b[2]-b[0])-(a[2]-a[0])-30)<.2f)
            check(abs((b[3]-b[1])-(a[3]-a[1])-20)<.2f)
            save(p,"word-line-spacing");true
        }
        test("effectOutsideOldFrame") {
            val glow=EffectDefinition.parse(JSONObject(context.assets.open("effects/dropshadow.json").bufferedReader().use { it.readText() }))
            check(engine.loadEffect(glow.runtimeXml())=="ok")
            val fx="""<effect id="truemotion.textanimator"><property name="position" type="vec2" value="0,-70"/></effect>"""
            val plain=draw(scene(fx))
            val shadow="""<effect id="${glow.id}"><property name="color" type="color" value="#ffff0000"/><property name="opacity" type="float" value="1"/><property name="blur" type="float" value="12"/><property name="x" type="float" value="8"/><property name="y" type="float" value="8"/></effect>"""
            val styled=draw(scene(fx+shadow));save(styled,"transformed-character-shadow")
            var lost=0;var red=0
            for(i in 0 until styled.size step 4) {
                if((plain[i+3].toInt() and 255)>128 && (styled[i+3].toInt() and 255)<32)lost++
                if((styled[i].toInt() and 255)>100 && (styled[i+1].toInt() and 255)<30 && (styled[i+3].toInt() and 255)>32)red++
            }
            check(lost==0 && red>100) { "Transformed text was cut by an old frame: lost=$lost shadow=$red" }
            JSONObject().put("lostGlyphPixels",lost).put("shadowPixels",red)
        }
        return report.put("failures",failures).put("ok",failures.length()==0)
    }
}
