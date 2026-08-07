package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ThreatEventEntity
import com.example.ui.theme.AppBackground
import com.example.ui.theme.CardBackground
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DividerColor
import com.example.ui.theme.PrimaryAccent
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningYellow
import com.example.viewmodel.NetShieldViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ThreatScreen(
    viewModel: NetShieldViewModel,
    modifier: Modifier = Modifier
) {
    val threats by viewModel.threatEvents.collectAsState(initial = emptyList())
    val stats by viewModel.stats.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(24.dp)
    ) {
        // Header
        Column {
            Text(
                text = "Protection & Threat Detection",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Real-time automated malware, phishing, and cyber threat mitigation",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Threat Shield Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Color(0x0A000000),
                    spotColor = Color(0x06000000)
                )
                .background(CardBackground, RoundedCornerShape(24.dp))
                .padding(20.dp)
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
                            .size(44.dp)
                            .background(DangerRed.copy(alpha = 0.12f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = "Threat Guard",
                            tint = DangerRed,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "NetShield Threat Guard: ${stats.threatsPrevented} Blocked",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Automatic push alerts are dispatched upon detecting high-severity risks.",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Fase 4.1: tombol ini TIDAK LAGI memicu ancaman palsu/acak.
        // Sekarang memicu pemindaian ulang database ancaman malware/phishing
        // yang nyata (unduh ulang blocklist StevenBlack fakenews + URLhaus).
        // Deteksi & notifikasi ancaman itu sendiri berjalan otomatis di
        // background setiap kali ada query DNS nyata yang cocok blocklist —
        // tombol ini bukan syarat untuk deteksi terjadi.
        Button(
            onClick = { viewModel.refreshThreatIntelligence() },
            colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.NotificationsActive, contentDescription = "Refresh Threat Database")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Rescan Threat Database Now", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Mitigated Threats Log",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (threats.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .background(SuccessGreen.copy(alpha = 0.12f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = "Safe",
                            tint = SuccessGreen,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Your Device is Safe!", fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("No active malware or phishing threats detected.", fontSize = 12.sp, color = TextSecondary)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(threats, key = { it.id }) { threat ->
                    ThreatItemCard(threat = threat)
                }
            }
        }
    }
}

@Composable
fun ThreatItemCard(threat: ThreatEventEntity) {
    val severityColor = when (threat.severity) {
        "HIGH" -> DangerRed
        "MEDIUM" -> WarningYellow
        else -> PrimaryAccent
    }

    val dateFormatted = SimpleDateFormat("HH:mm:ss - dd MMM yyyy", Locale.getDefault()).format(Date(threat.timestamp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color(0x0A000000),
                spotColor = Color(0x06000000)
            )
            .background(CardBackground, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(severityColor.copy(alpha = 0.12f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "THREAT ${threat.severity}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = severityColor
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = threat.threatType,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                }

                Text(
                    text = dateFormatted,
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Domain: ${threat.domain}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryAccent
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = threat.description,
                fontSize = 12.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Action: ${threat.actionTaken}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = SuccessGreen
            )
        }
    }
}

