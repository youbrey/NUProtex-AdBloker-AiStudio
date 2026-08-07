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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppBackground
import com.example.ui.theme.CardBackground
import com.example.ui.theme.DividerColor
import com.example.ui.theme.PrimaryAccent
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.NetShieldViewModel

@Composable
fun SettingsScreen(
    viewModel: NetShieldViewModel,
    onBack: () -> Unit,
    isBatteryOptimizationExempt: Boolean = false,
    onRequestBatteryOptimizationExemption: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lowBatteryMode by viewModel.lowBatteryMode.collectAsState()
    var dataCollectionEnabled by remember { mutableStateOf(false) }
    var cloudBackupEnabled by remember { mutableStateOf(false) }
    var shareThreatDataEnabled by remember { mutableStateOf(true) }
    var startOnBoot by remember { mutableStateOf(true) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header matching Screen 3 in image
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Privacy Controls",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hero Privacy Card matching Screen 3 in image
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 2.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Color(0x0C000000),
                    spotColor = Color(0x08000000)
                )
                .background(CardBackground, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFFF3F4F6), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Lock",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Your Privacy Matters",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "We're committed to protecting your privacy. All threat detection happens on your device, and we never sell your data to third parties.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Privacy Settings Section
        Text(
            text = "Privacy Settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        PrivacyToggleRow(
            icon = Icons.Filled.Visibility,
            title = "Data Collection",
            subtitle = "Anonymous usage statistics",
            checked = dataCollectionEnabled,
            onCheckedChange = { dataCollectionEnabled = it }
        )

        Spacer(modifier = Modifier.height(10.dp))

        PrivacyToggleRow(
            icon = Icons.Filled.Cloud,
            title = "Cloud Backup",
            subtitle = "Encrypted backups",
            checked = cloudBackupEnabled,
            onCheckedChange = { cloudBackupEnabled = it }
        )

        Spacer(modifier = Modifier.height(10.dp))

        PrivacyToggleRow(
            icon = Icons.Filled.Share,
            title = "Share Threat Data",
            subtitle = "Help improve protection",
            checked = shareThreatDataEnabled,
            onCheckedChange = { shareThreatDataEnabled = it }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // System Performance & Battery Section
        Text(
            text = "Device Optimization",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        PrivacyToggleRow(
            icon = Icons.Filled.BatterySaver,
            title = "Ultra Low-Power Engine",
            subtitle = "Minimal CPU footprint when backgrounded",
            checked = lowBatteryMode,
            onCheckedChange = { viewModel.toggleLowBatteryMode(it) }
        )

        Spacer(modifier = Modifier.height(10.dp))

        PrivacyToggleRow(
            icon = Icons.Filled.PowerSettingsNew,
            title = "Auto-Start on Boot",
            subtitle = "Resume DNS shield upon reboot",
            checked = startOnBoot,
            onCheckedChange = { startOnBoot = it }
        )

        Spacer(modifier = Modifier.height(10.dp))

        BatteryExemptionCard(
            isExempt = isBatteryOptimizationExempt,
            onRequestExemption = onRequestBatteryOptimizationExemption
        )

        Spacer(modifier = Modifier.height(60.dp))
    }
}

/**
 * Fase 6.3: kartu penjelasan + tombol untuk meminta battery optimization
 * exemption. Berbeda dari [PrivacyToggleRow] lain di layar ini (yang murni
 * preferensi lokal), status di sini datang dari OS (PowerManager), dan
 * mengaktifkannya WAJIB melalui dialog sistem — bukan Switch biasa — karena
 * app tidak bisa memberi izin ini ke dirinya sendiri. Penjelasan alasan
 * ditampilkan transparan agar user paham kenapa app memintanya.
 */
@Composable
fun BatteryExemptionCard(
    isExempt: Boolean,
    onRequestExemption: () -> Unit
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
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFFF3F4F6), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isExempt) Icons.Filled.CheckCircle else Icons.Filled.BatteryChargingFull,
                        contentDescription = "Battery Optimization",
                        tint = if (isExempt) SuccessGreen else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Battery Optimization Exemption",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = if (isExempt) {
                            "Dikecualikan — proteksi lebih aman dari dibunuh sistem saat idle"
                        } else {
                            "Belum dikecualikan — beberapa merek HP (mis. Xiaomi/Oppo) bisa mematikan proteksi di background"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            if (!isExempt) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Android bisa menghentikan layanan VPN di background untuk menghemat baterai, sehingga proteksi DNS berhenti tanpa Anda sadari. Mengecualikan NetShield dari battery optimization membantu menjaga proteksi tetap berjalan. Ini bersifat opsional — Anda tetap bisa menolaknya di dialog sistem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onRequestExemption,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Kecualikan dari Battery Optimization")
                }
            }
        }
    }
}

@Composable
fun PrivacyToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
                        .size(40.dp)
                        .background(Color(0xFFF3F4F6), CircleShape)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
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
