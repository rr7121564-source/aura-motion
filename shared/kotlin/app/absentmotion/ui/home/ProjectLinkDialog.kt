package app.absentmotion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.absentmotion.R
import app.absentmotion.platform.LocalContext
import app.absentmotion.platform.Uri
import app.absentmotion.library.ImportFailed
import app.absentmotion.library.ProjectLinks
import app.absentmotion.export.formatBytes
import app.absentmotion.ui.theme.Am
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Suppress("DEPRECATION")
@Composable
internal fun ProjectLinkDialog(onImport: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(-1L) }
    val genericError = stringResource(R.string.project_link_failed)
    fun close() { job?.cancel(); onDismiss() }
    fun start() {
        if (busy || value.isBlank()) return
        error = null; done = 0; total = -1; busy = true
        val link = value
        job = scope.launch {
            var download: java.io.File? = null
            var handedOff = false
            try {
                val owner = coroutineContext
                val file = withContext(Dispatchers.IO) {
                    ProjectLinks.download(context.cacheDir,link,{ bytes,length -> done=bytes;total=length },{ !owner.isActive }).also { download=it }
                }
                ensureActive()
                onImport(Uri.fromFile(file))
                handedOff = true
                onDismiss()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = (failure as? ImportFailed)?.text?.resolve(context) ?: genericError
            } finally {
                if (!handedOff) download?.let { ProjectLinks.removeDownload(context.cacheDir,it) }
                busy = false
            }
        }
    }
    Dialog(::close,DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.widthIn(max=480.dp).fillMaxWidth(.92f).clip(RoundedCornerShape(18.dp)).background(Am.Panel).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.project_link_title),color=Am.OnDark,fontSize=20.sp)
            Text(stringResource(R.string.project_link_hint),color=Am.Caption,fontSize=13.sp,lineHeight=18.sp)
            BasicTextField(value,{value=it;error=null},Modifier.fillMaxWidth().heightIn(min=56.dp).clip(RoundedCornerShape(10.dp))
                .background(Am.Field).padding(12.dp),enabled=!busy,textStyle=TextStyle(color=Am.OnDark,fontSize=14.sp),
                cursorBrush=SolidColor(Am.Accent),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),maxLines=4,
                decorationBox={field -> Box {if(value.isBlank()) Text("https://alightcreative.com/am/share/…",color=Am.Caption,fontSize=14.sp);field()} })
            if (busy) {
                if(total>0) LinearProgressIndicator(progress={ (done.toDouble()/total).toFloat().coerceIn(0f,1f) },Modifier.fillMaxWidth(),color=Am.Accent,trackColor=Am.Field)
                else LinearProgressIndicator(Modifier.fillMaxWidth(),color=Am.Accent,trackColor=Am.Field)
                Text(if(total>0) "${formatBytes(done)} / ${formatBytes(total)}" else formatBytes(done),color=Am.Caption,fontSize=12.sp)
            }
            error?.let { Text(it,color=Am.Accent,fontSize=13.sp,lineHeight=18.sp) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({ clipboard.getText()?.text?.let { value=it.trim();error=null } },enabled=!busy) { Text(stringResource(R.string.project_link_paste),color=Am.Accent) }
                Spacer(Modifier.weight(1f))
                TextButton(::close) { Text(stringResource(R.string.cancel),color=Am.Caption) }
                TextButton(::start,enabled=!busy && value.isNotBlank()) { Text(stringResource(R.string.project_link_import),color=if(busy || value.isBlank())Am.Caption else Am.Accent) }
            }
        }
    }
}
