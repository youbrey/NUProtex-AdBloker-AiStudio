package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.data.local.DnsLogEntity
import com.example.ui.theme.AppBackground
import com.example.ui.theme.CardBackground
import com.example.ui.theme.DangerLightBg
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DividerColor
import com.example.ui.theme.PrimaryAccent
import com.example.ui.theme.PrimaryLightBg
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessLightBg
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningYellow
import com.example.viewmodel.NetShieldViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityLogScreen(
    viewModel: NetShieldViewModel,
    modifier: Modifier = Modifier
) {
    val logs by viewModel.filteredLogs.collectAsState()
    val searchQuery by viewModel.logSearchQuery.collectAsState()
    val activeFilter by viewModel.logFilterCategory.collectAsState()

    var selectedLogForDetail by remember { mutableStateOf<DnsLogEntity?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    val filterCategories = listOf("Semua", "Diblokir", "Diizinkan", "Ancaman")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Header matching Screen 2 in image
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Alerts",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Clear Logs",
                        tint = TextSecondary
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .shadow(
                            elevation = 3.dp,
                            shape = CircleShape,
                            ambientColor = Color(0x0C000000),
                            spotColor = Color(0x08000000)
                        )
                        .background(CardBackground, CircleShape)
                        .padding(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = "Notifications",
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Category Pills (Screen 2 in Image)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(filterCategories) { category ->
                val isSelected = (activeFilter == category) || (activeFilter == "Semua" && category == "All")
                Box(
                    modifier = Modifier
                        .background(
                            color = if (isSelected) PrimaryAccent else CardBackground,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .border(
                            width = if (isSelected) 0.dp else 1.dp,
                            color = DividerColor,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clickable { viewModel.setLogFilterCategory(if (category == "All") "Semua" else category) }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = category,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search Input Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setLogSearchQuery(it) },
            placeholder = { Text("Search domain or application...", fontSize = 13.sp, color = TextSecondary) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = PrimaryAccent)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setLogSearchQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = TextSecondary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardBackground,
                unfocusedContainerColor = CardBackground,
                focusedBorderColor = PrimaryAccent,
                unfocusedBorderColor = DividerColor,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Logs Stream
        if (logs.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = "Clean",
                        tint = PrimaryAccent,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No threat alerts found in this filter",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(logs, key = { it.id }) { log ->
                    AlertItemCard(
                        log = log,
                        onClick = { selectedLogForDetail = log }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(60.dp)) // Nav bar offset
    }

    // Detail Inspector Dialog
    selectedLogForDetail?.let { log ->
        AlertDialog(
            onDismissRequest = { selectedLogForDetail = null },
            title = {
                Text(
                    text = "Alert Inspector",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text("Domain: ${log.domain}", fontWeight = FontWeight.SemiBold, color = PrimaryAccent)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("App: ${log.clientApp}", fontSize = 13.sp)
                    Text("Category: ${log.category}", fontSize = 13.sp)
                    Text("Status: ${if (log.isBlocked) "BLOCKED" else "ALLOWED"}", fontSize = 13.sp, color = if (log.isBlocked) DangerRed else SuccessGreen)
                    Text("Latency: ${log.latencyMs} ms", fontSize = 13.sp)
                    Text("Timestamp: ${SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date(log.timestamp))}", fontSize = 12.sp, color = TextSecondary)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addCustomRule(
                            domain = log.domain,
                            isBlocked = !log.isBlocked,
                            category = "Custom Rule",
                            note = "Added from Inspector"
                        )
                        selectedLogForDetail = null
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent, contentColor = Color.White)
                ) {
                    Text(if (log.isBlocked) "Add to Whitelist" else "Add to Blacklist")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedLogForDetail = null }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }

    if (showClearDialog) {
        // Fase 5.4: dialog clear log sekarang menawarkan opsi granular —
        // hanya kategori filter yang sedang aktif, log lama (>30 hari), atau
        // seluruh log — bukan cuma "hapus semua" seperti sebelumnya.
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Logs") },
            text = { Text("Choose what to clear. Current filter: \"$activeFilter\".") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Button(
                        onClick = {
                            viewModel.clearLogsByCurrentFilter()
                            showClearDialog = false
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear \"$activeFilter\" only")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.clearLogsOlderThan(30)
                            showClearDialog = false
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear logs older than 30 days")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.clearAllLogs()
                            showClearDialog = false
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TextSecondary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear everything")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }
}

@Composable
fun AlertItemCard(
    log: DnsLogEntity,
    onClick: () -> Unit
) {
    val isThreat = log.category == "malware_guard" || log.category == "phishing_guard" || log.category == "fingerprint_guard" || log.threatLevel != "NONE"

    val iconVec = when {
        isThreat -> Icons.Filled.Security
        log.isBlocked -> Icons.Filled.Security
        else -> Icons.Filled.Public
    }
    val iconBg = when {
        isThreat -> DangerLightBg
        log.isBlocked -> PrimaryLightBg
        else -> SuccessLightBg
    }
    val iconTint = when {
        isThreat -> DangerRed
        log.isBlocked -> PrimaryAccent
        else -> SuccessGreen
    }

    val badgeText = when {
        isThreat -> "ANCAMAN"
        log.isBlocked -> "DIBLOKIR"
        else -> "DIIZINKAN"
    }
    val badgeBg = when {
        isThreat -> DangerLightBg
        log.isBlocked -> PrimaryLightBg
        else -> SuccessLightBg
    }
    val badgeTextColor = when {
        isThreat -> DangerRed
        log.isBlocked -> PrimaryAccent
        else -> SuccessGreen
    }

    val timeAgo = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))

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
            .clickable { onClick() }
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
                        .size(42.dp)
                        .background(iconBg, CircleShape)
                ) {
                    Icon(
                        imageVector = iconVec,
                        contentDescription = log.category,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = log.domain,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "${log.clientApp} • $timeAgo • ${log.latencyMs} ms",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .background(badgeBg, CircleShape)
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
