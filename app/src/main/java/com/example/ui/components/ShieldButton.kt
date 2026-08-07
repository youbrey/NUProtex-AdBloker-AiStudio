package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
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
fun ShieldButton(
    isActive: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusBgColor by animateColorAsState(
        targetValue = if (isActive) SuccessGreen.copy(alpha = 0.1f) else DangerRed.copy(alpha = 0.1f),
        animationSpec = tween(250),
        label = "statusBgColor"
    )

    val statusTextColor by animateColorAsState(
        targetValue = if (isActive) SuccessGreen else DangerRed,
        animationSpec = tween(250),
        label = "statusTextColor"
    )

    val buttonBgColor by animateColorAsState(
        targetValue = if (isActive) PrimaryAccent else Color(0xFFE2E8F0),
        animationSpec = tween(250),
        label = "buttonBgColor"
    )

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
            .padding(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Protection Circle & Icon
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        color = if (isActive) PrimaryAccent.copy(alpha = 0.12f) else Color(0xFFF1F5F9),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (isActive) Icons.Filled.Shield else Icons.Outlined.Shield,
                    contentDescription = "Shield Protection Status",
                    tint = if (isActive) PrimaryAccent else TextSecondary,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Title & Status
            Text(
                text = if (isActive) "System Protection Active" else "Protection Paused",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isActive) "Encrypted DNS sinkhole running smoothly" else "Tap below to resume system-wide ad blocking",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(20.dp))

            // iOS-Inspired Toggle Action Button (Radius 18dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(buttonBgColor, RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onToggle(!isActive)
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = "Toggle Power",
                        tint = if (isActive) Color.White else TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isActive) "Turn Off Protection" else "Turn On Protection",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = if (isActive) Color.White else TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status Badge
            Box(
                modifier = Modifier
                    .background(statusBgColor, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isActive) "• Safe & Protected" else "• Unprotected",
                    style = MaterialTheme.typography.labelMedium,
                    color = statusTextColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

