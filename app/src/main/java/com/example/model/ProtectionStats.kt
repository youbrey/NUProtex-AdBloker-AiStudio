package com.example.model

data class ProtectionStats(
    val totalRequests: Long = 0L,
    val totalBlocked: Long = 0L,
    val threatsPrevented: Long = 0L,
    val dataSavedMb: Float = 0f,
    val avgLatencyMs: Int = 0,
    val uptimeSeconds: Long = 0L,
    val activeRulesCount: Int = 0,
    val dbVersion: String = "Ready"
) {
    val blockPercentage: Int
        get() = if (totalRequests > 0) ((totalBlocked.toDouble() / totalRequests) * 100).toInt() else 0

    val dataSavedFormatted: String
        get() = if (dataSavedMb >= 1024) String.format("%.2f GB", dataSavedMb / 1024f)
        else String.format("%.1f MB", dataSavedMb)

    val uptimeFormatted: String
        get() {
            val hours = uptimeSeconds / 3600
            val minutes = (uptimeSeconds % 3600) / 60
            return if (hours > 0) "${hours}j ${minutes}m" else "${minutes}m"
        }
}
