package app.absentmotion

import app.absentmotion.audio.*
import app.absentmotion.export.*
import app.absentmotion.model.*
import org.junit.Assert.*
import org.junit.Test

class FreeNumericControlsTest {
    @Test fun speedRetimesEveryLayerKindWithoutFlatteningNestedContent() {
        for(kind in listOf("shape","text","drawing","camera","nullobj","model","embedScene","audio")) {
            val children=listOf(XmlNode("transform",children=listOf(XmlNode("rotation").withKeyframes(listOf(Keyframe(0f,"0"),Keyframe(1f,"90"))))))+
                if(kind=="embedScene") listOf(XmlNode("scene",mapOf("width" to "100","height" to "100","fps" to "30","totalTime" to "1000"))) else emptyList()
            val original=Layer(XmlNode(kind,mapOf("id" to "1","startTime" to "200","endTime" to "1200"),children))
            val twice=original.withNormalSpeed(2f)
            assertEquals(kind,700,twice.endTime)
            assertEquals(kind,2f,twice.normalSpeed(),.0001f)
            assertEquals(original.node.child("scene"),twice.node.child("scene"))
            assertEquals(original.keyframes(PropRef.Rotation),twice.keyframes(PropRef.Rotation))
            assertEquals(original.endTime,twice.withNormalSpeed(1f).endTime)
        }
    }
    @Test fun gainAboveTheSliderRangeIsPreservedAndRenderedWithoutNonfiniteSamples() {
        val layer=Layer(XmlNode("audio",mapOf("id" to "1","startTime" to "0","endTime" to "999"),listOf(XmlNode("gain",mapOf("value" to "25")))))
        assertEquals(25f,AudioClip(layer,"test").gainAt(400.0),0f)
        val voice=Voice();voice.begin(0.0,1.0,512);voice.win.fill(.8f)
        val out=FloatArray(1024)
        voice.render(out,0,512,0.0,1.0,0f,Float.MAX_VALUE,0,10.0,1000.0/48000,999.0)
        assertTrue(out.all {it.isFinite()})
        out[0]=Float.POSITIVE_INFINITY;out[1]=Float.NaN
        SafeLimiter(48000).process(out,512)
        assertTrue(out.all {it.isFinite() && kotlin.math.abs(it)<=1f})
    }
    @Test fun arbitraryProjectAndExportNumbersRoundTripAndLargeCountsDoNotWrap() {
        val scene=Scene.create("numeric",12345,56789,137,0)
        assertEquals(12345,scene.width);assertEquals(56789,scene.height);assertEquals(137,scene.fps)
        val s=ExportSettings(4321,137,customResolution=true,customFrameRate=true)
        val saved=settingsFromJson(settingsToJson(s),ExportSettings(360,30))
        assertEquals(s,saved)
        assertEquals(s,forScene(ExportKind.Video,s,ExportSettings(360,30)))
        val big=exportSize(scene,Int.MAX_VALUE,true)
        assertTrue(big.first>0 && big.second>0 && big.first%2==0 && big.second%2==0)
        val huge=Scene(scene.node.with("totalTime",Int.MAX_VALUE.toString()).with("fps",Int.MAX_VALUE.toString()))
        assertEquals(Int.MAX_VALUE,frameCount(huge,Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE,huge.lastFrame)
        assertTrue(huge.frameTime(Int.MAX_VALUE)>0)
    }
}
