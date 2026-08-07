package com.example.model

data class DnsProvider(
    val id: String,
    val name: String,
    val description: String,
    val dohUrl: String,
    val primaryIp: String,
    val secondaryIp: String,
    val latencyMs: Int = 12,
    val supportsDoH: Boolean = true,
    val supportsDoT: Boolean = true,
    val isCustom: Boolean = false,
    val iconName: String = "dns"
) {
    companion object {
        val PRESETS = listOf(
            DnsProvider(
                id = "cloudflare",
                name = "Cloudflare DNS (1.1.1.1)",
                description = "Kecepatan ultra tinggi & privasi tanpa enkripsi log",
                dohUrl = "https://security.cloudflare-dns.com/dns-query",
                primaryIp = "1.1.1.1",
                secondaryIp = "1.0.0.1",
                latencyMs = 8
            ),
            DnsProvider(
                id = "adguard",
                name = "AdGuard DNS (Family & Security)",
                description = "Pemblokir iklan bawaan, tracker & perlindungan keluarga",
                dohUrl = "https://dns.adguard-dns.com/dns-query",
                primaryIp = "94.140.14.14",
                secondaryIp = "94.140.15.15",
                latencyMs = 14
            ),
            DnsProvider(
                id = "nextdns",
                name = "NextDNS Secure DoH",
                description = "Enkripsi tingkat lanjut dengan kontrol filter kustom",
                dohUrl = "https://dns.nextdns.io/dns-query",
                primaryIp = "45.90.28.0",
                secondaryIp = "45.90.30.0",
                latencyMs = 18
            ),
            DnsProvider(
                id = "google",
                name = "Google Public DNS",
                description = "Performa global stabil & resolusi cepat",
                dohUrl = "https://dns.google/dns-query",
                primaryIp = "8.8.8.8",
                secondaryIp = "8.8.4.4",
                latencyMs = 10
            ),
            DnsProvider(
                id = "quad9",
                name = "Quad9 Security DNS",
                description = "Sistem perlindungan ancaman cyber & intelligence malware",
                dohUrl = "https://dns.quad9.net/dns-query",
                primaryIp = "9.9.9.9",
                secondaryIp = "149.112.112.112",
                latencyMs = 15
            )
        )
    }
}
