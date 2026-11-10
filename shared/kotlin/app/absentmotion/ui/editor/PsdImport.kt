package app.absentmotion.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import app.absentmotion.R
import app.absentmotion.editor.EditorPanel
import app.absentmotion.editor.EditorState
import app.absentmotion.model.*
import app.absentmotion.platform.readAtMost
import app.truemotion.vector.PsdFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal suspend fun isPsd(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readAtMost(4).contentEquals(byteArrayOf(56, 66, 80, 83)) } }.getOrDefault(false) == true
}

internal suspend fun EditorState.importPsdFile(context: Context, uri: Uri) {
    val document = document
    val path = groupPath
    val host = scene
    val start = host.frameStart(frame)
    val end = host.endTimeFor(frame, settings.defaultLayerDuration * 1000)
    val id = Layer.newId()
    val created = mutableListOf<File>()
    val busy = Toast.makeText(context, R.string.psd_importing, Toast.LENGTH_LONG).also { it.show() }
    try {
        val (group, media, warnings) = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readAtMost((128 shl 20) + 1) }
                ?: throw java.io.IOException("unreadable")
            require(bytes.size <= 128 shl 20)
            val psd = PsdFile.read(bytes)
            coroutineContext.ensureActive()
            if (psd.layers.isEmpty() && psd.composite != null) {
                psd.layers.add(PsdFile.Layer().apply { name = context.getString(R.string.psd_image); width = psd.width; height = psd.height; pixels = psd.composite })
                psd.composite = null
            }
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }?.substringBeforeLast('.') ?: context.getString(R.string.psd_image)
            val images = linkedMapOf<PsdFile.Layer, MediaDeclaration>()
            val folder = store.mediaDir(projectId).apply { mkdirs() }
            for (layer in psd.layers) {
                coroutineContext.ensureActive()
                val pixels = layer.pixels ?: continue
                if (layer.section != 0) { layer.pixels = null; continue }
                val file = File(folder, "psd-${UUID.randomUUID()}.png").also(created::add)
                val bitmap = Bitmap.createBitmap(pixels, layer.width, layer.height, Bitmap.Config.ARGB_8888)
                try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle(); layer.pixels = null }
                images[layer] = MediaDeclaration(XmlNode("media", mapOf("uri" to Uri.fromFile(file).toString(), "filename" to file.name,
                    "title" to layer.name, "type" to "image/png", "width" to "${layer.width}", "height" to "${layer.height}", "size" to "${file.length()}")))
            }
            val layers = psdLayers(psd, images, end - start,host.fps)
            require(layers.isNotEmpty())
            val inner = XmlNode("scene", mapOf("width" to "${psd.width}", "height" to "${psd.height}", "fps" to "${host.fps}",
                "totalTime" to "${end - start}", "retime" to "off", "bgcolor" to "#00000000"), layers.map { it.node })
            val fit = minOf(host.width.toFloat() / psd.width, host.height.toFloat() / psd.height)
            val layer = Layer(XmlNode("embedScene", mapOf("id" to "$id", "label" to name, "startTime" to "$start", "endTime" to "$end"), listOf(inner)))
                .withTransform("location", host.width / 2f, host.height / 2f, 0f).withTransform("scale", fit, fit)
            Triple(layer, images.values.toList(), psd.warnings.size)
        }
        check(this.document == document && groupPath == path)
        edit { it.add(group, media) }
        selectedId = id
        panel = EditorPanel.Tools
        addOpen = false
        created.clear()
        val count=media.size
        val message=when {
            count==1 -> context.getString(R.string.psd_import_single)
            warnings>0 -> context.getString(R.string.psd_import_warnings,warnings)
            else -> context.getString(R.string.psd_import_layer_count,count)
        }
        Toast.makeText(context,message,Toast.LENGTH_LONG).show()
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        Log.w("AMPsd", "cannot import PSD", e)
        Toast.makeText(context, R.string.psd_import_failed, Toast.LENGTH_LONG).show()
    } catch (e: OutOfMemoryError) {
        Log.w("AMPsd", "PSD memory budget", e)
        Toast.makeText(context, R.string.psd_import_memory, Toast.LENGTH_LONG).show()
    } finally {
        busy.cancel()
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { created.forEach { it.delete() } }
    }
}
