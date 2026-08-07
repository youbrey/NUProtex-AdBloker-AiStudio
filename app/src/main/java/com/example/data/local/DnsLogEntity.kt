package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dns_logs")
data class DnsLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val clientApp: String,
    val timestamp: Long,
    val isBlocked: Boolean,
    val category: String, // e.g. "Iklan Umum", "Iklan Game", "Marketplace", "Tracker", "Ancaman", "Normal"
    val latencyMs: Int,
    val threatLevel: String = "NONE" // "NONE", "LOW", "MEDIUM", "HIGH"
)
