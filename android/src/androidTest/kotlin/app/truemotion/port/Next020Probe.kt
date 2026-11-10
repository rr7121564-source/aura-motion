package app.truemotion.port

import android.graphics.Bitmap
import android.content.Context
import app.absentmotion.effects.EffectDefinition
import app.absentmotion.engine.NativeEngine
import app.absentmotion.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

internal object Next020Probe {
    fun run(context: Context, engine: NativeEngine, directory: File): JSONObject {
        val report = JSONObject().put("backend", JSONObject(engine.info))
        val failures = JSONArray()
        fun test(name: String, body: () -> Any) {
            try { report.put(name, body()) } catch (t: Throwable) { failures.put("$name: ${t.stackTraceToString()}") }
        }
        test("loopingBounce") {
            val curve = Easing("bounce", listOf(2f / 7f, 1f))
            var worst = 0f
            for (i in 0..7) {
                val actual = curve.at(i / 7f)
                worst = max(worst, abs(actual - if (i % 2 == 0) 0f else 1f))
            }
            check(worst < .00001f) { "Repeated bounce height changed: $worst" }
            // A partial last arc should still move instead of snapping to the end early.
            val partial = Easing("bounce", listOf(.3f, 1f))
            check(partial.at(.9f) < .05f && partial.at(.98f) < .4f)
            for (i in 0..100) {
                val u = i / 100f
                val reversed = Easing.parse("reverse $curve").at(u)
                check(abs(reversed - (1 - curve.at(1 - u))) < .00001f)
            }
            JSONObject().put("worstError", worst).put("partialArc", partial.at(.98f))
        }
        test("extendedRig") {
            val xml = """<scene width="256" height="256" fps="30" totalTime="2999">
                <bone id="10" length="60" startTime="0" endTime="999"><transform><location value="60,60,0"/><rotation value="25"/></transform></bone>
                <bone id="11" parent="10" length="55" startTime="0" endTime="999"><transform><location value="60,0,0"/><rotation value="40"/></transform></bone>
                <bone id="12" parent="11" length="45" startTime="0" endTime="999"><transform><location value="55,0,0"/><rotation value="20"/></transform><trueRigTarget><point value="150,150"/></trueRigTarget></bone>
                <shape id="1" parent="12" s=".rect" fillType="color" startTime="0" endTime="2999"><transform><location value="35,0,0"/></transform><property name="size" type="vec2" value="15,8"/><fillColor value="#ffffffff"/></shape>
                </scene>"""
            fun error(): Float {
                val m = checkNotNull(engine.layerMatrix(12, 2000.0))
                val w = m[6] * 45 + m[8]
                return hypot((m[0] * 45 + m[2]) / w - 150, (m[3] * 45 + m[5]) / w - 150)
            }
            // The old native bridge left bones inactive after their original end time.
            engine.setScene(xml.replace("<bone ", "<nullobj trueBone=\"true\" ").replace("</bone>", "</nullobj>"))
            val oldError = error()
            check(oldError > 5) { "Fixture does not reproduce the original inactive-IK bug: $oldError" }
            engine.setScene(xml)
            val fixedError = error()
            check(fixedError < 1.5f) { "IK stopped after the old rig end: $fixedError" }
            val before = checkNotNull(engine.still(500.0, 256, 256))
            val after = checkNotNull(engine.still(2000.0, 256, 256))
            check(before.contentEquals(after)) { "Held rig artwork changed after the original end" }
            check((3 until after.size step 4).count { (after[it].toInt() and 255) > 0 } > 100)
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(after))
            File(directory, "extended-rig.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val reopened = Scene.parse(Scene.parse(xml).node.toXml())
            check(reopened.bones.all { it.endTime == 2999 })
            engine.setScene(reopened.node.toXml())
            check(error() < 1.5f)
            JSONObject().put("oldTargetError", oldError).put("targetError", fixedError).put("reopen", true).put("frameIdentical", true)
        }
        test("largeTextShadow") {
            val definition = EffectDefinition.parse(JSONObject(context.assets.open("effects/dropshadow.json").bufferedReader().use { it.readText() }))
            check(engine.loadEffect(definition.runtimeXml()) == "ok")
            fun text(factor: Int, expanded: Boolean): ByteArray {
                val shift = if (expanded) 256 else 0
                val side = if (expanded) 768 else 256
                val xml = """<scene width="$side" height="$side" fps="30" totalTime="999" bgcolor="#00000000">
                    <text id="1" size="${240L * factor}" wrapWidth="${1600L * factor}" align="center" startTime="0" endTime="999" fillType="color"><content>TEXT</content>
                    <transform><location value="${100 + shift},${125 + shift},0"/><scale value="${1.0 / factor},${1.0 / factor}"/></transform><fillColor value="#ffffffff"/>
                    <effect id="${definition.id}"><property name="color" type="color" value="#ffff0000"/><property name="opacity" type="float" value="1"/>
                    <property name="blur" type="float" value="24"/><property name="x" type="float" value="20"/><property name="y" type="float" value="12"/></effect></text></scene>"""
                engine.setScene(xml)
                return checkNotNull(engine.still(0.0, side, side))
            }
            val results = JSONArray()
            for (factor in listOf(1, 10, 100, 1000)) {
                val direct = text(factor, false)
                val larger = text(factor, true)
                var lost = 0; var worst = 0; var error = 0L; var shadow = 0
                for (y in 0..255) for (x in 0..255) {
                    val a = (y * 256 + x) * 4; val b = ((y + 256) * 768 + x + 256) * 4
                    if ((larger[b+3].toInt() and 255) > 64 && (direct[a+3].toInt() and 255) < 8) lost++
                    if ((direct[a].toInt() and 255) > 120 && (direct[a+1].toInt() and 255) < 40 && (direct[a+3].toInt() and 255) > 32) shadow++
                    for (c in 0..3) { val delta = abs((direct[a+c].toInt() and 255) - (larger[b+c].toInt() and 255)); error += delta; worst = max(worst, delta) }
                }
                val mean = error / (256.0 * 256 * 4)
                check(lost < 16 && mean < 1 && shadow > 100) { "Shadow clipping at font factor $factor: lost=$lost mean=$mean max=$worst shadow=$shadow" }
                results.put(JSONObject().put("factor",factor).put("lostPixels",lost).put("meanCropError",mean).put("maxDelta",worst).put("shadowPixels",shadow))
            }
            results
        }
        test("characterTools") {
            Text020Probe.run(context,engine,directory).also { check(it.optBoolean("ok")) { it.toString() } }
        }
        test("glbRigs") {
            ModelRig020Probe.run(context,engine,directory).also { check(it.optBoolean("ok")) { it.toString() } }
        }
        return report.put("failures", failures).put("ok", failures.length() == 0)
    }
}
