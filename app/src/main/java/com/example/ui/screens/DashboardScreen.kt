package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.example.ui.components.SecurityScoreDial
import com.example.ui.components.ShieldButton
import com.example.ui.components.StatCard
import com.example.ui.components.TrafficChart
import com.example.ui.theme.AppBackground
import com.example.ui.theme.CardBackground
import com.example.ui.theme.DangerLightBg
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DividerColor
import com.example.ui.theme.PrimaryAccent
import com.example.ui.theme.PrimaryLightBg
import com.example.ui.theme.SecondaryAccent
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessLightBg
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningYellow
import com.example.viewmodel.NetShieldViewModel

@Composable
fun DashboardScreen(
    viewModel: NetShieldViewModel,
    onOpenSettings: () -> Unit,
    onViewAllAlerts: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isActive by viewModel.isProtectionActive.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val provider by viewModel.selectedProvider.collectAsState()
    val filters by viewModel.filterOptions.collectAsState()
    val isUpdatingDb by viewModel.isUpdatingDb.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Top Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = "Dashboard",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(if (isActive) SuccessGreen else DangerRed, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isActive) "Encrypted • ${provider.name}" else "Protection Paused",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            // Notification Bell Card with Red Badge Dot
            Box(
                contentAlignment = Alignment.TopEnd,
                modifier = Modifier
                    .shadow(
                        elevation = 3.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x0C000000),
                        spotColor = Color(0x08000000)
                    )
                    .background(CardBackground, CircleShape)
                    .clickable { onOpenSettings() }
                    .padding(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = "Notifications",
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(DangerRed, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        val securityScore by viewModel.securityScore.collectAsState()
        val threatEvents by viewModel.threatEvents.collectAsState(initial = emptyList())
        val filteredLogs by viewModel.filteredLogs.collectAsState()

        // Security Score Circular Dial Arc (Matching Reference Image)
        SecurityScoreDial(
            score = securityScore,
            // "Security Check" memicu pemindaian/pembaruan database ancaman
            onRunSecurityCheck = { viewModel.refreshThreatIntelligence() }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Recent Alerts Section
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Recent Alerts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Text(
                text = "View All",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary,
                modifier = Modifier.clickable { onViewAllAlerts() }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (threatEvents.isNotEmpty()) {
            threatEvents.take(3).forEach { threat ->
                RecentAlertCard(
                    icon = Icons.Filled.Security,
                    iconBgColor = DangerLightBg,
                    iconTint = DangerRed,
                    title = threat.domain,
                    subtitle = "${threat.threatType} • ${threat.actionTaken}",
                    badgeText = threat.severity,
                    badgeBgColor = DangerLightBg,
                    badgeTextColor = DangerRed
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        } else {
            val recentBlocked = filteredLogs.filter { it.isBlocked }.take(3)
            if (recentBlocked.isNotEmpty()) {
                recentBlocked.forEach { log ->
                    RecentAlertCard(
                        icon = Icons.Filled.CloudDone,
                        iconBgColor = PrimaryLightBg,
                        iconTint = PrimaryAccent,
                        title = log.domain,
                        subtitle = "${log.clientApp} • Diblokir (${log.category})",
                        badgeText = "DIBLOKIR",
                        badgeBgColor = PrimaryLightBg,
                        badgeTextColor = PrimaryAccent
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            } else {
                RecentAlertCard(
                    icon = Icons.Filled.CheckCircle,
                    iconBgColor = SuccessLightBg,
                    iconTint = SuccessGreen,
                    title = "Perangkat Aman",
                    subtitle = "Tidak ada ancaman atau domain mencurigakan terdeteksi",
                    badgeText = "NORMAL",
                    badgeBgColor = SuccessLightBg,
                    badgeTextColor = SuccessGreen
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Master Shield Action Toggle Button
        ShieldButton(
            isActive = isActive,
            onToggle = { viewModel.toggleProtection(it) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Statistics Overview Grid
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatCard(
                title = "Total Queries",
                value = String.format("%,d", stats.totalRequests),
                subtext = "DNS Queries",
                icon = Icons.Filled.Public,
                accentColor = PrimaryAccent,
                modifier = Modifier.weight(1f)
            )

            StatCard(
                title = "Ads Blocked",
                value = String.format("%,d", stats.totalBlocked),
                subtext = "${stats.blockPercentage}% Rate",
                icon = Icons.Filled.CloudDone,
                accentColor = SuccessGreen,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatCard(
                title = "Threats Guarded",
                value = "${stats.threatsPrevented}",
                subtext = "Malware & Phishing",
                icon = Icons.Filled.Security,
                accentColor = DangerRed,
                modifier = Modifier.weight(1f)
            )

            StatCard(
                title = "Data Saved",
                value = stats.dataSavedFormatted,
                subtext = "Ping: ${stats.avgLatencyMs} ms",
                icon = Icons.Filled.DataUsage,
                accentColor = SecondaryAccent,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Traffic Chart
        TrafficChart(
            blockedCount = stats.totalBlocked,
            passedCount = stats.totalRequests - stats.totalBlocked
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Active Rules Section
        Text(
            text = "Active Protection Rules",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        filters.forEach { filter ->
            val iconVec = when (filter.id) {
                "game_ads" -> Icons.Filled.SportsEsports
                "marketplace_ads" -> Icons.Filled.ShoppingBag
                "trackers" -> Icons.Filled.Radar
                "fingerprint_guard" -> Icons.Filled.Security
                "malware_guard" -> Icons.Filled.Security
                else -> Icons.Filled.CheckCircle
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .shadow(
                        elevation = 2.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = Color(0x0C000000),
                        spotColor = Color(0x08000000)
                    )
                    .background(CardBackground, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .background(PrimaryAccent.copy(alpha = 0.1f), CircleShape)
                        ) {
                            Icon(
                                imageVector = iconVec,
                                contentDescription = filter.title,
                                tint = PrimaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = filter.title,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = filter.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Switch(
                        checked = filter.isEnabled,
                        onCheckedChange = { viewModel.toggleFilter(filter.id, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = PrimaryAccent,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = DividerColor
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Threat Database Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 2.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color(0x0C000000),
                    spotColor = Color(0x08000000)
                )
                .background(CardBackground, RoundedCornerShape(20.dp))
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Threat Intelligence DB",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Rules: ${String.format("%,d", stats.activeRulesCount)} | Ver: ${stats.dbVersion}",
                        fontSize = 11.sp,
                        color = PrimaryAccent
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .background(
                            if (isUpdatingDb) WarningYellow.copy(alpha = 0.15f) else PrimaryAccent.copy(alpha = 0.12f),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !isUpdatingDb
                        ) { viewModel.updateThreatDatabase() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (isUpdatingDb) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = WarningYellow,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Updating...", fontSize = 12.sp, color = WarningYellow, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Cached,
                                contentDescription = "Update",
                                tint = PrimaryAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Update DB", fontSize = 12.sp, color = PrimaryAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp)) // Padding for floating bottom bar
    }
}

@Composable
fun RecentAlertCard(
    icon: ImageVector,
    iconBgColor: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    badgeText: String,
    badgeBgColor: Color,
    badgeTextColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color(0x0C000000),
                spotColor = Color(0x08000000)
            )
            .background(CardBackground, RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .background(iconBgColor, CircleShape)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .background(badgeBgColor, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = badgeTextColor
                )
            }
        }
    }
}
