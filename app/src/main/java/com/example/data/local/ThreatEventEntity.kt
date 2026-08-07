package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "threat_events")
data class ThreatEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val threatType: String, // e.g., "Malware", "Phishing", "Ransomware", "Scam Ads", "Game Exploit"
    val severity: String,   // "HIGH", "MEDIUM", "LOW"
    val timestamp: Long = System.currentTimeMillis(),
    val description: String,
    val actionTaken: String = "DIBLOKIR OTOMATIS"
)
