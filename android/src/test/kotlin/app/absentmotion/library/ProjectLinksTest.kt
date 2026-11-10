package app.absentmotion.library

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CancellationException

class ProjectLinksTest {
    private val link="https://alightcreative.com/am/share/u/Example_User/p/Example-Package-123"
    private val zip=byteArrayOf(0x50,0x4b,3,4)+ByteArray(100_000){it.toByte()}
    private class Connection(url: URL,val payload: ByteArray,val code: Int=200,val length: Long=payload.size.toLong()):HttpURLConnection(url) {
        var disconnected=false
        override fun connect() {}
        override fun disconnect(){disconnected=true}
        override fun usingProxy()=false
        override fun getResponseCode()=code
        override fun getContentLengthLong()=length
        override fun getInputStream()=ByteArrayInputStream(payload)
    }
    private fun temp(body:(File)->Unit) { val dir=Files.createTempDirectory("project-link-test").toFile();try{body(dir)}finally{dir.deleteRecursively()} }

    @Test fun officialShareLinksMapToTheActualPackageObject() {
        val expected="https://firebasestorage.googleapis.com/v0/b/alight-creative.appspot.com/o/share%2Fu%2FExample_User%2Fp%2FExample-Package-123%2Fprojectfiles.zip?alt=media"
        assertEquals(expected,ProjectLinks.packageUrl(link).toString())
        assertEquals(expected,ProjectLinks.packageUrl("  "+link.removePrefix("https://")+"/?tracking=1  ").toString())
        assertEquals(expected,ProjectLinks.packageUrl(link.replace("https://","http://www.")).toString())
    }

    @Test fun malformedOrUnrelatedLinksNeverStartADownload() {
        for (value in listOf("", "https://alightcreative.com.evil.test/am/share/u/a/p/b", "https://alightcreative.com@evil.test/am/share/u/a/p/b",
            "file:///am/share/u/a/p/b", "https://alightcreative.com/am/share/u/../p/a", "https://alightcreative.com/am/share/u/a/p/b/other")) {
            assertThrows(value,ImportFailed::class.java) {ProjectLinks.packageUrl(value)}
        }
    }

    @Test fun downloadedBytesArePreservedAndProgressReachesTheContentLength()=temp {dir->
        val connection=Connection(ProjectLinks.packageUrl(link),zip)
        var received=0L
        val file=ProjectLinks.download(dir,link,{done,total->assertEquals(zip.size.toLong(),total);assertTrue(done>=received);received=done},connect={connection})
        assertArrayEquals(zip,file.readBytes());assertEquals(zip.size.toLong(),received);assertTrue(connection.disconnected)
        ProjectLinks.removeDownload(dir,file);assertFalse(file.exists())
    }

    @Test fun cancellationAndIncompleteResponsesLeaveNoTemporaryPackage()=temp {dir->
        var cancelled=false
        val first=Connection(ProjectLinks.packageUrl(link),zip)
        assertThrows(CancellationException::class.java) {
            ProjectLinks.download(dir,link,{done,_->if(done>0)cancelled=true},{cancelled},{first})
        }
        assertTrue(first.disconnected);assertTrue(File(dir,"project-links").listFiles().isNullOrEmpty())
        val second=Connection(ProjectLinks.packageUrl(link),zip,length=zip.size+1L)
        assertThrows(IOException::class.java) {ProjectLinks.download(dir,link,connect={second})}
        assertTrue(second.disconnected);assertTrue(File(dir,"project-links").listFiles().isNullOrEmpty())
    }

    @Test fun unavailableOrOversizedResponsesAreReportedAndCleanedUp()=temp {dir->
        for(connection in listOf(Connection(ProjectLinks.packageUrl(link),zip,403),Connection(ProjectLinks.packageUrl(link),zip,length=ProjectLinks.MAX_PACKAGE_BYTES+1))) {
            assertThrows(ImportFailed::class.java){ProjectLinks.download(dir,link,connect={connection})}
            assertTrue(connection.disconnected);assertTrue(File(dir,"project-links").listFiles().isNullOrEmpty())
        }
    }

    @Test fun cleanupCannotRemoveAnotherFile()=temp {dir->
        val file=File(dir,"project-user.alight").apply{writeBytes(zip)}
        ProjectLinks.removeDownload(dir,file);assertTrue(file.exists())
    }
}
