package com.xlink.android.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlink.android.engine.LogEntry
import com.xlink.android.ui.theme.XlinkColors

fun LogEntry.toAnnotatedString(baseColor: Color): AnnotatedString = buildAnnotatedString {
    if (timestamp.isNotEmpty()) {
        withStyle(SpanStyle(color = XlinkColors.LogSystem, fontSize = 11.sp)) {
            append("[$timestamp]")
        }
        append(' ')
    }
    if (nodeName.isNotEmpty()) {
        withStyle(SpanStyle(color = XlinkColors.Primary, fontWeight = FontWeight.Medium, fontSize = 11.sp)) {
            append("[$nodeName]")
        }
        append(' ')
    }
    withStyle(SpanStyle(color = level.color, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)) {
        append(level.tag)
    }
    append(' ')
    withStyle(SpanStyle(color = baseColor, fontSize = 11.sp)) {
        append(message)
    }
}

@Composable
fun LogLine(
    entry: LogEntry,
    modifier: Modifier = Modifier
) {
    val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    val annotated = remember(entry.timestamp, entry.nodeName, entry.level, entry.message) {
        entry.toAnnotatedString(baseColor)
    }

    Text(
        text = annotated,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        softWrap = false
    )
}
