package app.absentmotion.compat

import app.absentmotion.effects.EffectCatalog
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ParentHelperTest {
    private val folder=File("../shared/assets/effects")
    @Test fun nativeDefinitionAndOriginalAlightKeysSurviveImport() {
        val catalog=EffectCatalog.parse { File(folder,it).inputStream() }
        val definition=catalog.find("absent.parenthelper")!!
        assertTrue(definition.native)
        assertEquals(3,definition.presets.size)
        val s=Scene.parse("""<scene width="200" height="200" fps="30"><shape id="1" parent="2"><effect id="com.alightcreative.effects.parenthelper"><property name="rotateMode" type="float" value="2"/><property name="rotateWeight" type="float"><kf t="0" v="0"/><kf t="1" v="2" e="cubicBezier 0.2 0.4 0.6 0.8"/></property></effect></shape><nullobj id="2"/></scene>""")
        val result=EffectMigration.parse(File(folder,"foreign.json").readText(),catalog).migrate(s)
        val effect=result.scene.layer(1)!!.effects.single()
        assertEquals("absent.parentinghelper",effect["id"])
        assertEquals(listOf(0f,2f),effect.effectProperty("rotateWeight")!!.keyframes.map {it.v.toFloat()})
        assertEquals("cubicBezier 0.2 0.4 0.6 0.8",effect.effectProperty("rotateWeight")!!.keyframes.last().e)
        assertTrue(result.dropped.isEmpty())
    }
    @Test fun scaleAssistImportsItsAxisDampingAndAnimatedScale() {
        val catalog=EffectCatalog.parse {File(folder,it).inputStream()}
        val scene=Scene.parse("""<scene width="256" height="256" fps="30"><shape id="1"><effect id="com.alightcreative.effects.scaleassist"><property name="axis" type="float" value="2"/><property name="damp" type="float" value="0.5"/><property name="scale" type="float"><kf t="0" v="1"/><kf t="1" v="2" e="cubicBezier 0.2 0.4 0.6 0.8"/></property></effect></shape></scene>""")
        val imported=EffectMigration.parse(File(folder,"foreign.json").readText(),catalog).migrate(scene)
        val effect=imported.scene.layer(1)!!.effects.single()
        assertEquals("absent.scaleassist",effect["id"])
        assertEquals(2f,effect.effectProperty("axis")!!["value"]!!.toFloat(),0f)
        assertEquals(.5f,effect.effectProperty("damp")!!["value"]!!.toFloat(),0f)
        assertEquals(listOf(1f,2f),effect.effectProperty("scale")!!.keyframes.map {it.v.toFloat()})
        assertTrue(imported.dropped.isEmpty())
    }
}
