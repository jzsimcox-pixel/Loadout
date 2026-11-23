package app.loadout.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = Palette.Text,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A button that shows a clear outline when the controller has it focused. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    textColor: Color = Palette.Text,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val borderColor = when {
        focused -> Palette.Text
        primary -> Palette.Accent
        else -> Palette.BorderStrong
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (primary) Palette.Accent else Color.Transparent)
            .border(if (focused) 3.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text = text,
            size = 15.sp,
            color = if (primary) Palette.OnAccent else textColor,
            weight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** One row of "name on the left, value on the right". */
@Composable
fun InfoRow(name: String, value: String, divider: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(name, size = 13.sp, color = Palette.Muted)
        Spacer(Modifier.width(16.dp))
        Label(value, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
    }
    if (divider) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Palette.Border),
        )
    }
}

@Composable
fun CheckMark(color: Color = Palette.Accent, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.14f
        drawLine(color, Offset(s * 0.19f, s * 0.53f), Offset(s * 0.39f, s * 0.72f), stroke, StrokeCap.Round)
        drawLine(color, Offset(s * 0.39f, s * 0.72f), Offset(s * 0.81f, s * 0.28f), stroke, StrokeCap.Round)
    }
}

/** A plain icon for the kind of system: cart, disc, handheld or dual. */
@Composable
fun SystemIcon(kind: String, size: Dp = 22.dp, color: Color = Palette.Text) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val line = Stroke(width = s * 0.075f, cap = StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) = drawRoundRect(
            color = color,
            topLeft = Offset(s * x, s * y),
            size = Size(s * w, s * h),
            cornerRadius = CornerRadius(s * r, s * r),
            style = line,
        )
        when (kind) {
            "disc" -> {
                drawCircle(color, radius = s * 0.36f, style = line)
                drawCircle(color, radius = s * 0.10f, style = line)
            }
            "handheld" -> {
                rect(0.10f, 0.27f, 0.80f, 0.46f, 0.12f)
                rect(0.33f, 0.37f, 0.34f, 0.26f, 0.04f)
                drawCircle(color, radius = s * 0.035f, center = Offset(s * 0.78f, s * 0.50f))
                drawCircle(color, radius = s * 0.035f, center = Offset(s * 0.22f, s * 0.50f))
            }
            "dual" -> {
                rect(0.23f, 0.12f, 0.54f, 0.34f, 0.06f)
                rect(0.23f, 0.54f, 0.54f, 0.34f, 0.06f)
            }
            else -> {
                rect(0.21f, 0.16f, 0.58f, 0.68f, 0.05f)
                rect(0.35f, 0.30f, 0.30f, 0.18f, 0.02f)
            }
        }
    }
}

/** The small "press this button" hint shown along the bottom of a screen. */
@Composable
fun KeyHint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .border(1.dp, Palette.BorderStrong, RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Label(key, size = 12.sp, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(8.dp))
        Label(action, size = 13.sp, color = Palette.Muted)
    }
}
