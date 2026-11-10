package app.absentmotion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.ui.theme.Am

/** Free numeric entry and presets share one field, with no preset-only restriction. */
@Composable
internal fun EditablePresetNumber(value: Int, label: String, presets: List<Pair<Int,String>>, unit: String = "",
    modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var dirty by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val commit by rememberUpdatedState {
        if (dirty) {
            dirty=false
            val number=text.toIntOrNull()?.takeIf {it>0}
            text=(number ?: value).toString()
            if(number!=null && number!=value) onChange(number)
        }
    }
    DisposableEffect(Unit) {onDispose {commit()}}
    Row(modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(Am.Field),verticalAlignment=Alignment.CenterVertically) {
        BasicTextField(text,{text=it.filter(Char::isDigit).take(10);dirty=true},
            Modifier.weight(1f).fillMaxHeight().semantics {contentDescription=label}.onFocusChanged {if(!it.isFocused) commit()}.padding(start=12.dp),
            textStyle=TextStyle(color=Am.OnDark,fontSize=15.sp),cursorBrush=SolidColor(Am.Accent),singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Done),
            keyboardActions=KeyboardActions(onDone={focus.clearFocus()}),
            decorationBox={inner->Box(Modifier.fillMaxSize(),Alignment.CenterStart){inner()}})
        if(unit.isNotEmpty()) Text(unit,color=Am.OnDarkDim,fontSize=12.sp)
        Box(Modifier.width(40.dp).fillMaxHeight().clickable {focus.clearFocus();open=true},Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                val path=androidx.compose.ui.graphics.Path().apply {moveTo(0f,size.height*.3f);lineTo(size.width*.5f,size.height*.75f);lineTo(size.width,size.height*.3f)}
                drawPath(path,Am.OnDarkDim,style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            }
            DropdownMenu(open,{open=false},containerColor=Am.Field) {
                presets.forEach {(number,name)->DropdownMenuItem(text={Text(name,color=Am.OnDark)},onClick={dirty=false;text=number.toString();open=false;onChange(number)})}
            }
        }
    }
}
