package app.absentmotion.model

import app.truemotion.vector.PsdFile
import org.junit.Assert.*
import org.junit.Test

class PsdLayersTest {
    private fun source(name: String,section: Int=0)=PsdFile.Layer().apply {this.name=name;this.section=section;left=10;top=20;width=100;height=80}
    private fun media(name: String)=MediaDeclaration(XmlNode("media",mapOf("uri" to "file:/$name.png")))

    @Test fun psdStackFolderAndPixelOffsetsBecomeEditableTimelineLayers() {
        val psd=PsdFile().apply {width=400;height=300}
        val bottom=source("Background")
        val lower=source("Lower")
        val upper=source("Upper").apply {opacity=128;flags=2;blend="mul "}
        val folder=source("Folder",1)
        psd.layers.addAll(listOf(bottom,source("end",3),lower,upper,folder))
        val layers=psdLayers(psd,mapOf(bottom to media("bottom"),lower to media("lower"),upper to media("upper")),999)
        assertEquals(listOf("Background","Folder"),layers.map {it.label})
        val inside=layers.last().nested!!.layers
        assertEquals(listOf("Lower","Upper"),inside.map {it.label})
        assertArrayEquals(floatArrayOf(60f,60f,0f),inside.first().location,.001f)
        assertTrue(inside.last().hidden)
        assertEquals(128f/255,inside.last().opacity,.001f)
        assertEquals("multiply",inside.last().node["blending"])
        assertEquals("file:/upper.png",inside.last().mediaFill!!.uri)
    }
}
