package app.absentmotion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.absentmotion.editor.EditorState
import app.absentmotion.ui.editor.panels.askNumber
import app.absentmotion.ui.editor.panels.formatFixed
import app.absentmotion.ui.editor.panels.formatPlain
import app.absentmotion.ui.theme.Am

internal val InspectorShape = RoundedCornerShape(8.dp)

@Composable
internal fun InspectorIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier.size(44.dp),
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier.clip(InspectorShape)
            .background(if (active) Am.Accent.copy(alpha = .16f) else Am.Field)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DimAlpha),
        Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(20.dp), tint = if (active) Am.Accent else Am.OnDark)
    }
}

@Composable
internal fun InspectorSegment(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.clip(InspectorShape)
            .background(if (selected) Am.FieldHi else Am.PanelInset)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 4.dp),
        Alignment.Center,
    ) {
        Text(label, color = if (selected) Am.Accent else Am.OnDark, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun InspectorAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.heightIn(min = 44.dp).clip(InspectorShape)
            .background(Am.Button)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), tint = Am.OnDark)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Am.OnDark, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Uses the editor's numeric keypad and only changes a value when the user confirms it. */
@Composable
internal fun InspectorNumber(
    state: EditorState,
    label: String,
    value: Float,
    modifier: Modifier = Modifier,
    unit: String = "",
    min: Float = -Float.MAX_VALUE,
    max: Float = Float.MAX_VALUE,
    decimals: Int? = null,
    onChange: (Float) -> Unit,
) {
    Column(
        modifier.heightIn(min = 50.dp).clip(InspectorShape).background(Am.Field)
            .clickable(role = Role.Button) {
                state.endGesture()
                state.askNumber(label, value, unit, min, max, onChange)
            }.padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = Am.OnDarkDim, fontSize = 10.sp, maxLines = 2, lineHeight = 12.sp)
        val text = if (decimals == null) formatPlain(value) else formatFixed(value, decimals)
        Text(text + unit, color = Am.OnDark, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
