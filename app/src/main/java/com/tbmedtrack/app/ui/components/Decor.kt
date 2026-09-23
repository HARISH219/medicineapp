package com.tbmedtrack.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tbmedtrack.app.ui.theme.AccentPurple
import com.tbmedtrack.app.ui.theme.DarkBackground
import com.tbmedtrack.app.ui.theme.DarkBackgroundTop
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen deep-navy vertical gradient used as the app background.
 * Content is layered on top.
 */
@Composable
fun ScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DarkBackgroundTop, DarkBackground)
                )
            )
    ) { content() }
}

/** A cheerful hand-drawn sun (morning). No animals — pure shapes. */
@Composable
fun SunDecor(modifier: Modifier = Modifier, color: Color = Color(0xFFFBBF24)) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) * 0.26f
        drawCircle(color = color, radius = r, center = c)
        val rayLen = r * 0.7f
        val rayStart = r * 1.25f
        for (i in 0 until 8) {
            val a = (i * 45f) * (Math.PI / 180f).toFloat()
            val sx = c.x + cos(a) * rayStart
            val sy = c.y + sin(a) * rayStart
            val ex = c.x + cos(a) * (rayStart + rayLen)
            val ey = c.y + sin(a) * (rayStart + rayLen)
            drawLine(color, Offset(sx, sy), Offset(ex, ey), strokeWidth = 6f, cap = StrokeCap.Round)
        }
    }
}

/** A calm crescent moon (night). */
@Composable
fun MoonDecor(modifier: Modifier = Modifier, color: Color = Color(0xFFC7D2FE)) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) * 0.34f
        drawCircle(color = color, radius = r, center = c)
        // Carve out with the background-toned circle to make a crescent.
        drawCircle(
            color = DarkBackground,
            radius = r * 0.92f,
            center = Offset(c.x + r * 0.5f, c.y - r * 0.28f)
        )
    }
}

/** A friendly two-tone capsule pill. */
@Composable
fun PillDecor(
    modifier: Modifier = Modifier,
    top: Color = Color(0xFFFB923C),
    bottom: Color = Color(0xFFFDE68A)
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = min(w, h) / 2f
        // Top half
        drawRoundRect(
            color = top,
            topLeft = Offset(0f, 0f),
            size = Size(w, h / 2f + r * 0.2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
        )
        // Bottom half
        drawRoundRect(
            color = bottom,
            topLeft = Offset(0f, h / 2f - r * 0.2f),
            size = Size(w, h / 2f + r * 0.2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
        )
    }
}

/** A tiny four-point sparkle / star. */
@Composable
fun SparkleDecor(modifier: Modifier = Modifier, color: Color = Color(0xFFA5B4FC)) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val long = min(size.width, size.height) * 0.5f
        val short = long * 0.18f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(c.x, c.y - long)
            lineTo(c.x + short, c.y - short)
            lineTo(c.x + long, c.y)
            lineTo(c.x + short, c.y + short)
            lineTo(c.x, c.y + long)
            lineTo(c.x - short, c.y + short)
            lineTo(c.x - long, c.y)
            lineTo(c.x - short, c.y - short)
            close()
        }
        drawPath(path, color)
    }
}

/** A small leaf (gentle, organic accent). */
@Composable
fun LeafDecor(modifier: Modifier = Modifier, color: Color = Color(0xFF34D399)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.05f)
            cubicTo(w * 0.95f, h * 0.25f, w * 0.85f, h * 0.9f, w * 0.5f, h * 0.98f)
            cubicTo(w * 0.15f, h * 0.9f, w * 0.05f, h * 0.25f, w * 0.5f, h * 0.05f)
            close()
        }
        drawPath(path, color)
        drawLine(
            Color(0x66000000),
            Offset(w * 0.5f, h * 0.1f),
            Offset(w * 0.5f, h * 0.92f),
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
    }
}

/** Circular progress ring with a track. Draw a % label yourself in the center. */
@Composable
fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = Color(0x33FFFFFF),
    progressColor: Color = AccentPurple,
    strokeDp: Float = 10f
) {
    Canvas(modifier) {
        val stroke = strokeDp * density
        val d = min(size.width, size.height) - stroke
        val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
        val arcSize = Size(d, d)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = progressColor,
            startAngle = -90f,
            sweepAngle = 360f * fraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}
