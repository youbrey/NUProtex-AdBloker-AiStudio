package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CardBackground
import com.example.ui.theme.PrimaryAccent
import com.example.ui.theme.TextSecondary

sealed class NavItem(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Dashboard : NavItem("dashboard", "Home", Icons.Filled.Shield, Icons.Outlined.Shield)
    object Logs : NavItem("logs", "Alerts", Icons.Filled.Notifications, Icons.Outlined.Notifications)
    object Filters : NavItem("filters", "Filters", Icons.Filled.FilterList, Icons.Outlined.FilterList)
    object DnsSettings : NavItem("dns", "DNS", Icons.Filled.Dns, Icons.Outlined.Dns)
    object Threats : NavItem("threats", "Threats", Icons.Filled.Security, Icons.Outlined.Security)
}

@Composable
fun BottomNavBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        NavItem.Dashboard,
        NavItem.Logs,
        NavItem.Filters,
        NavItem.DnsSettings,
        NavItem.Threats
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(36.dp),
                    ambientColor = Color(0x10000000),
                    spotColor = Color(0x1A000000)
                )
                .background(
                    color = CardBackground,
                    shape = RoundedCornerShape(36.dp)
                )
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            items.forEach { item ->
                val isSelected = currentRoute == item.route

                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) PrimaryAccent else Color.Transparent,
                    animationSpec = tween(200),
                    label = "bgColor"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .background(
                            color = bgColor,
                            shape = CircleShape
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onNavigate(item.route)
                        }
                        .padding(horizontal = if (isSelected) 14.dp else 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.title,
                            tint = if (isSelected) Color.White else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )

                        AnimatedVisibility(
                            visible = isSelected,
                            enter = fadeIn(tween(150)) + expandHorizontally(tween(150)),
                            exit = fadeOut(tween(150)) + shrinkHorizontally(tween(150))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = item.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
