package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.CustomRuleEntity
import com.example.ui.theme.AppBackground
import com.example.ui.theme.CardBackground
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DividerColor
import com.example.ui.theme.PrimaryAccent
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.NetShieldViewModel

@Composable
fun FiltersScreen(
    viewModel: NetShieldViewModel,
    modifier: Modifier = Modifier
) {
    val customRules by viewModel.customRules.collectAsState(initial = emptyList())
    val filters by viewModel.filterOptions.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val isUpdatingDb by viewModel.isUpdatingDb.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }

    val tabs = listOf("Custom Rules", "Presets", "Threat DB")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(24.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = "Filters & Custom Rules",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Manage whitelist, blacklist & security presets",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            if (selectedTab == 0) {
                IconButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .background(PrimaryAccent, CircleShape)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add Rule",
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Navigation Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = CardBackground,
            contentColor = PrimaryAccent,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = PrimaryAccent
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == index) PrimaryAccent else TextSecondary
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Tab Content Views
        when (selectedTab) {
            0 -> CustomRulesTab(
                rules = customRules,
                onDelete = { viewModel.removeCustomRule(it) }
            )
            1 -> PresetFiltersTab(
                filters = filters,
                onToggleFilter = { id, enabled -> viewModel.toggleFilter(id, enabled) }
            )
            2 -> ThreatDatabaseTab(
                stats = stats,
                isUpdating = isUpdatingDb,
                onUpdate = { viewModel.updateThreatDatabase() }
            )
        }
    }

    // Add Custom Rule Dialog Modal
    if (showAddDialog) {
        var domainInput by remember { mutableStateOf("") }
        var isBlockedMode by remember { mutableStateOf(true) }
        var categoryInput by remember { mutableStateOf("Custom Domain") }
        var noteInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = "Add Custom Rule",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text("Domain (e.g. ads.example.com)") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryAccent,
                            unfocusedBorderColor = DividerColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Rule Action Mode:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = isBlockedMode,
                            onClick = { isBlockedMode = true },
                            colors = RadioButtonDefaults.colors(selectedColor = DangerRed)
                        )
                        Text("Blacklist (Block)", fontSize = 13.sp, color = DangerRed)

                        Spacer(modifier = Modifier.width(16.dp))

                        RadioButton(
                            selected = !isBlockedMode,
                            onClick = { isBlockedMode = false },
                            colors = RadioButtonDefaults.colors(selectedColor = SuccessGreen)
                        )
                        Text("Whitelist (Allow)", fontSize = 13.sp, color = SuccessGreen)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text("Note / Description (Optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryAccent,
                            unfocusedBorderColor = DividerColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (domainInput.isNotBlank()) {
                            viewModel.addCustomRule(
                                domain = domainInput,
                                isBlocked = isBlockedMode,
                                category = categoryInput,
                                note = noteInput
                            )
                            showAddDialog = false
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent, contentColor = Color.White)
                ) {
                    Text("Save Rule")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CardBackground,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }
}

@Composable
fun CustomRulesTab(
    rules: List<CustomRuleEntity>,
    onDelete: (CustomRuleEntity) -> Unit
) {
    if (rules.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.ListAlt,
                    contentDescription = "Empty",
                    tint = TextSecondary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("No custom domain rules configured yet", color = TextSecondary)
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rules, key = { it.id }) { rule ->
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
                                    .background(
                                        if (rule.isBlocked) DangerRed.copy(alpha = 0.12f) else SuccessGreen.copy(alpha = 0.12f),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = if (rule.isBlocked) Icons.Filled.Block else Icons.Filled.CheckCircle,
                                    contentDescription = "Mode",
                                    tint = if (rule.isBlocked) DangerRed else SuccessGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column {
                                Text(
                                    text = rule.domain,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${if (rule.isBlocked) "BLACKLIST (Block)" else "WHITELIST (Allow)"} • ${rule.category}",
                                    fontSize = 11.sp,
                                    color = if (rule.isBlocked) DangerRed else SuccessGreen
                                )
                                if (rule.note.isNotEmpty()) {
                                    Text(
                                        text = rule.note,
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { onDelete(rule) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = DangerRed.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PresetFiltersTab(
    filters: List<com.example.model.FilterOption>,
    onToggleFilter: (String, Boolean) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(filters) { filter ->
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = filter.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(PrimaryAccent.copy(alpha = 0.12f), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${String.format("%,d", filter.ruleCount)} rules",
                                    fontSize = 10.sp,
                                    color = PrimaryAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = filter.description,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    Switch(
                        checked = filter.isEnabled,
                        onCheckedChange = { onToggleFilter(filter.id, it) },
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
    }
}

@Composable
fun ThreatDatabaseTab(
    stats: com.example.model.ProtectionStats,
    isUpdating: Boolean,
    onUpdate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color(0x0A000000),
                spotColor = Color(0x06000000)
            )
            .background(CardBackground, RoundedCornerShape(24.dp))
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .background(PrimaryAccent.copy(alpha = 0.12f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = "Threat DB",
                    tint = PrimaryAccent,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = "Threat Intelligence Network",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Real-time updates synced with global hosts",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Active DB Version:", fontSize = 13.sp, color = TextSecondary)
            Text(stats.dbVersion, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PrimaryAccent)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Total Protection Rules:", fontSize = 13.sp, color = TextSecondary)
            Text("${String.format("%,d", stats.activeRulesCount)} Active", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SuccessGreen)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Guarded Categories:", fontSize = 13.sp, color = TextSecondary)
            Text("Ads, Trackers, Malware, Phishing", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onUpdate,
            enabled = !isUpdating,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent, contentColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isUpdating) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Syncing Threat Definitions...", fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Filled.Cached, contentDescription = "Update")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Update Definitions Now", fontWeight = FontWeight.Bold)
            }
        }
    }
}

