package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CardBackground
import com.example.ui.theme.DividerColor
import com.example.ui.theme.PrimaryAccent
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SecurityScoreDial(
    score: Int = 70,
    onRunSecurityCheck: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color(0x0C000000),
                spotColor = Color(0x08000000)
            )
            .background(CardBackground, RoundedCornerShape(28.dp))
            .padding(vertical = 24.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(220.dp)
            ) {
                Canvas(modifier = Modifier.size(200.dp)) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    val center = Offset(canvasWidth / 2, canvasHeight / 2)
                    val radius = (canvasWidth / 2) - 16.dp.toPx()

                    val startAngle = 140f
                    val sweepAngle = 260f
                    val strokeWidth = 10.dp.toPx()

                    val scoreColor = when {
                        score >= 80 -> SuccessGreen
                        score >= 50 -> PrimaryAccent
                        else -> com.example.ui.theme.DangerRed
                    }

                    // Background Track Arc
                    drawArc(
                        color = Color(0xFFE5E7EB),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        size = Size(radius * 2, radius * 2),
                        topLeft = Offset(center.x - radius, center.y - radius)
                    )

                    // Active Score Arc
                    val activeSweep = sweepAngle * (score / 100f)
                    drawArc(
                        color = scoreColor,
                        startAngle = startAngle,
                        sweepAngle = activeSweep,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        size = Size(radius * 2, radius * 2),
                        topLeft = Offset(center.x - radius, center.y - radius)
                    )

                    // Draw radial tick marks
                    val totalTicks = 28
                    val tickStartRadius = radius + 8.dp.toPx()
                    val tickEndRadius = radius + 14.dp.toPx()

                    for (i in 0..totalTicks) {
                        val angleDeg = startAngle + (sweepAngle / totalTicks) * i
                        val angleRad = Math.toRadians(angleDeg.toDouble())

                        val startX = center.x + (tickStartRadius * cos(angleRad)).toFloat()
                        val startY = center.y + (tickStartRadius * sin(angleRad)).toFloat()
                        val endX = center.x + (tickEndRadius * cos(angleRad)).toFloat()
                        val endY = center.y + (tickEndRadius * sin(angleRad)).toFloat()

                        val isHighlighted = (i.toFloat() / totalTicks) <= (score / 100f)
                        drawLine(
                            color = if (isHighlighted) scoreColor.copy(alpha = 0.6f) else Color(0xFFD1D5DB),
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // Dot handle on the tip of the score arc
                    val endAngleDeg = startAngle + activeSweep
                    val endAngleRad = Math.toRadians(endAngleDeg.toDouble())
                    val handleX = center.x + (radius * cos(endAngleRad)).toFloat()
                    val handleY = center.y + (radius * sin(endAngleRad)).toFloat()

                    drawCircle(
                        color = Color.White,
                        radius = 8.dp.toPx(),
                        center = Offset(handleX, handleY)
                    )
                    drawCircle(
                        color = scoreColor,
                        radius = 5.dp.toPx(),
                        center = Offset(handleX, handleY)
                    )
                }

                // Center Content: score%/100, "Security Score"
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val scoreTextColor = when {
                        score >= 80 -> SuccessGreen
                        score >= 50 -> PrimaryAccent
                        else -> com.example.ui.theme.DangerRed
                    }

                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                style = SpanStyle(
                                    color = scoreTextColor,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append("$score%")
                            }
                            withStyle(
                                style = SpanStyle(
                                    color = TextSecondary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Normal
                                )
                            ) {
                                append("/100")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "Security Score",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // "Security Check" pill button
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFF3F4F6), RoundedCornerShape(20.dp))
                            .border(1.dp, DividerColor, RoundedCornerShape(20.dp))
                            .clickable { onRunSecurityCheck() }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Security Check",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
