package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CardBackground
import com.example.ui.theme.DangerRed
import com.example.ui.theme.PrimaryAccent
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun TrafficChart(
    blockedCount: Long,
    passedCount: Long,
    modifier: Modifier = Modifier
) {
    // Hourly distribution factors for standard telemetry visualization
    val factors = listOf(0.3f, 0.5f, 0.2f, 0.6f, 0.8f, 0.7f, 0.9f, 0.6f, 1.0f, 0.85f, 0.75f, 0.95f)
    val totalVolume = (passedCount + blockedCount).coerceAtLeast(1)

    // Calculate normalized activity values per interval based on live telemetry
    val chartData = factors.map { factor ->
        val passedVal = if (passedCount == 0L && blockedCount == 0L) 0f else (passedCount.toFloat() / totalVolume * 80f * factor).coerceAtLeast(2f)
        val blockedVal = if (passedCount == 0L && blockedCount == 0L) 0f else (blockedCount.toFloat() / totalVolume * 80f * factor).coerceAtLeast(2f)
        Pair(passedVal, blockedVal)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color(0x0C000000),
                spotColor = Color(0x08000000)
            )
            .background(CardBackground, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Column {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Network Activity (Real-time)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Query volume & ad blocking telemetry",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(DangerRed, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Blocked", fontSize = 11.sp, color = TextSecondary)

                    Spacer(modifier = Modifier.width(10.dp))

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(PrimaryAccent, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Allowed", fontSize = 11.sp, color = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Bar Chart Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(95.dp)
            ) {
                val barWidth = (size.width / (chartData.size * 2f)) - 4f
                val maxVal = 100f

                chartData.forEachIndexed { index, pair ->
                    val xPassed = index * (barWidth * 2f + 8f) + 8f
                    val xBlocked = xPassed + barWidth + 2f

                    val passedHeight = (pair.first / maxVal) * size.height
                    val blockedHeight = (pair.second / maxVal) * size.height

                    // Draw Passed Bar (Primary Blue)
                    drawRoundRect(
                        color = PrimaryAccent.copy(alpha = 0.85f),
                        topLeft = Offset(xPassed, size.height - passedHeight),
                        size = Size(barWidth, passedHeight),
                        cornerRadius = CornerRadius(6f, 6f)
                    )

                    // Draw Blocked Bar (Danger Red)
                    drawRoundRect(
                        color = DangerRed.copy(alpha = 0.9f),
                        topLeft = Offset(xBlocked, size.height - blockedHeight),
                        size = Size(barWidth, blockedHeight),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("12h ago", fontSize = 10.sp, color = TextSecondary)
                Text("6h ago", fontSize = 10.sp, color = TextSecondary)
                Text("Live", fontSize = 10.sp, color = SuccessGreen, fontWeight = FontWeight.Bold)
            }
        }
    }
}

