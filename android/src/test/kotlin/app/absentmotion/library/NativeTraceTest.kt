package app.absentmotion.library

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class NativeTraceTest {
    private fun uint(value: Long): ByteArray {var n=value;val b=ByteArrayOutputStream();do {val v=(n and 127).toInt();n=n ushr 7;b.write(v or if(n!=0L) 128 else 0)}while(n!=0L);return b.toByteArray()}
    private fun number(key: Int,value: Long)=uint(key.toLong() shl 3)+uint(value)
    private fun bytes(key: Int,value: ByteArray)=uint((key.toLong() shl 3) or 2)+uint(value.size.toLong())+value
    private fun text(key: Int,value: String)=bytes(key,value.toByteArray())
    private fun thread(id: Long,name: String,function: String): ByteArray {
        val frame=number(1,4096)+text(4,function)+number(5,8)+text(6,"/system/lib64/libcodec.so")+text(8,"cafe123")
        return bytes(16,number(1,id)+bytes(2,number(1,id)+text(2,name)+bytes(4,frame)))
    }
    @Test fun crashedNativeThreadPrecedesUnrelatedJavaDaemon() {
        val trace=number(5,10)+number(6,15)+text(2,"test/device")+text(14,"HIDL DEAD_OBJECT")+
            bytes(10,number(1,6)+text(2,"SIGABRT"))+thread(11,"FinalizerWatchdogDaemon","sleep")+thread(15,"codec-release","releaseCodec")
        val result=nativeTrace(trace,4096)!!
        assertTrue(result.contains("HIDL DEAD_OBJECT"))
        assertTrue(result.contains("codec-release [CRASHED]"))
        assertTrue(result.indexOf("releaseCodec")<result.indexOf("FinalizerWatchdogDaemon"))
        assertTrue(result.contains("[BuildId cafe123]"))
    }
    @Test fun malformedAndTextLogsFallBackWithoutHanging() {
        assertNull(nativeTrace("plain native log".toByteArray(),4096))
        assertNull(nativeTrace(byteArrayOf(0x82.toByte(),0x80.toByte()),4096))
        assertNull(nativeTrace(byteArrayOf(0x12,0x7f),4096))
    }
}
