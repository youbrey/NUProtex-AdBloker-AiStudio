package com.example.vpn

import java.net.InetAddress

/**
 * Utilitas level-rendah untuk mem-parsing dan membangun ulang paket
 * IPv4 / UDP / TCP mentah yang dibaca dari `vpnInterface.fileDescriptor`.
 *
 * Implementasi ini sengaja minimal (bukan library networking lengkap):
 * hanya mendukung apa yang benar-benar dipakai NetShield untuk
 * mencegat query DNS dan mem-forward trafik non-DNS (Fase 1).
 * IPv6 belum didukung (lihat catatan RENCANA_PRODUKSI_NETSHIELD.md
 * §Catatan Penting) — paket IPv6 diabaikan/di-drop untuk saat ini
 * karena `Builder` VPN hanya mendaftarkan alamat IPv4.
 *
 * === CHANGELOG ===
 * [Fase 1 - 2026-08-07] Baru dibuat. Parsing IPv4 header, UDP header,
 * TCP header (flags & seq/ack saja — cukup untuk relay sederhana),
 * checksum Internet (RFC 1071) & checksum pseudo-header UDP/TCP.
 */
object NetPacketUtils {

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    // ---- Internet checksum (RFC 1071) ----------------------------------

    /** Menghitung checksum 16-bit standar Internet atas [data] pada rentang [offset, offset+length). */
    fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            // Byte ganjil terakhir, di-pad dengan 0 di low byte.
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Memverifikasi apakah checksum Internet atas [data] pada [offset, offset+length) bernilai 0. */
    fun verifyChecksum(data: ByteArray, offset: Int, length: Int): Boolean {
        return internetChecksum(data, offset, length) == 0
    }

    /** Checksum pseudo-header UDP/TCP di atas IPv4. */
    fun transportChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        transportSegment: ByteArray
    ): Int {
        val len = transportSegment.size
        // pseudo-header: srcIp(4) + dstIp(4) + zero(1) + protocol(1) + length(2)
        val pseudo = ByteArray(12 + len + (len % 2))
        System.arraycopy(srcIp, 0, pseudo, 0, 4)
        System.arraycopy(dstIp, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = protocol.toByte()
        pseudo[10] = ((len shr 8) and 0xFF).toByte()
        pseudo[11] = (len and 0xFF).toByte()
        System.arraycopy(transportSegment, 0, pseudo, 12, len)
        return internetChecksum(pseudo, 0, pseudo.size)
    }

    /** Checksum pseudo-header UDP/TCP di atas IPv6 (RFC 8200). */
    fun transportChecksumIpv6(
        srcIp: ByteArray,
        dstIp: ByteArray,
        nextHeader: Int,
        transportSegment: ByteArray
    ): Int {
        val len = transportSegment.size
        // pseudo-header IPv6: srcIp(16) + dstIp(16) + length(4) + zero(3) + nextHeader(1)
        val pseudo = ByteArray(40 + len + (len % 2))
        System.arraycopy(srcIp, 0, pseudo, 0, 16)
        System.arraycopy(dstIp, 0, pseudo, 16, 16)
        writeUInt32(pseudo, 32, len.toLong())
        pseudo[36] = 0
        pseudo[37] = 0
        pseudo[38] = 0
        pseudo[39] = nextHeader.toByte()
        System.arraycopy(transportSegment, 0, pseudo, 40, len)
        return internetChecksum(pseudo, 0, pseudo.size)
    }

    // ---- IPv4 -----------------------------------------------------------

    data class Ipv4Header(
        val version: Int,
        val ihl: Int,
        val totalLength: Int,
        val protocol: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val headerLength: Int,
        val isFragmented: Boolean = false,
        val fragmentOffset: Int = 0
    )

    /** Parsing minimal header IPv4. Return null jika bukan IPv4 valid / terlalu pendek / corrupt length. */
    fun parseIpv4Header(packet: ByteArray, length: Int): Ipv4Header? {
        if (length < 20) return null
        val versionIhl = packet[0].toInt() and 0xFF
        val version = versionIhl shr 4
        if (version != 4) return null
        val ihl = versionIhl and 0x0F
        val headerLength = ihl * 4
        if (headerLength < 20 || length < headerLength) return null

        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (totalLength < headerLength || length < totalLength) return null

        val flagsAndOffset = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        val moreFragments = (flagsAndOffset and 0x2000) != 0
        val fragOffset = (flagsAndOffset and 0x1FFF) * 8
        val isFragmented = moreFragments || fragOffset > 0

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)

        return Ipv4Header(
            version = version,
            ihl = ihl,
            totalLength = totalLength,
            protocol = protocol,
            srcIp = srcIp,
            dstIp = dstIp,
            headerLength = headerLength,
            isFragmented = isFragmented,
            fragmentOffset = fragOffset
        )
    }

    /** Membangun header IPv4 20-byte (tanpa opsi) dan menuliskannya ke [out] mulai [offset]. */
    fun writeIpv4Header(
        out: ByteArray,
        offset: Int,
        totalLength: Int,
        protocol: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        identification: Int
    ) {
        out[offset] = 0x45 // version=4, IHL=5 (20 byte, tanpa opsi)
        out[offset + 1] = 0 // DSCP/ECN
        out[offset + 2] = ((totalLength shr 8) and 0xFF).toByte()
        out[offset + 3] = (totalLength and 0xFF).toByte()
        out[offset + 4] = ((identification shr 8) and 0xFF).toByte()
        out[offset + 5] = (identification and 0xFF).toByte()
        out[offset + 6] = 0x40 // flags: Don't Fragment
        out[offset + 7] = 0
        out[offset + 8] = 64 // TTL
        out[offset + 9] = protocol.toByte()
        out[offset + 10] = 0 // checksum placeholder
        out[offset + 11] = 0
        System.arraycopy(srcIp, 0, out, offset + 12, 4)
        System.arraycopy(dstIp, 0, out, offset + 16, 4)

        val checksum = internetChecksum(out, offset, 20)
        out[offset + 10] = ((checksum shr 8) and 0xFF).toByte()
        out[offset + 11] = (checksum and 0xFF).toByte()
    }

    // ---- IPv6 -----------------------------------------------------------

    const val NEXT_HEADER_HOP_BY_HOP = 0
    const val NEXT_HEADER_ROUTING = 43
    const val NEXT_HEADER_FRAGMENT = 44
    const val NEXT_HEADER_ESP = 50
    const val NEXT_HEADER_AH = 51
    const val NEXT_HEADER_ICMPV6 = 58
    const val NEXT_HEADER_DEST_OPTIONS = 60

    data class Ipv6Header(
        val version: Int,
        val payloadLength: Int,
        val nextHeader: Int,
        val hopLimit: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val headerLength: Int,
        val isFragmented: Boolean = false
    )

    /** Parsing header IPv6 & melemwati Extension Headers hingga menemukan protocol transport (TCP/UDP/ICMPv6). */
    fun parseIpv6Header(packet: ByteArray, length: Int): Ipv6Header? {
        if (length < 40) return null
        val version = (packet[0].toInt() and 0xFF) shr 4
        if (version != 6) return null

        val payloadLength = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        var nextHeader = packet[6].toInt() and 0xFF
        val hopLimit = packet[7].toInt() and 0xFF
        val srcIp = packet.copyOfRange(8, 24)
        val dstIp = packet.copyOfRange(24, 40)

        var currOffset = 40
        var isFrag = false

        // Loop melintasi IPv6 Extension Headers
        while (currOffset < length) {
            when (nextHeader) {
                PROTOCOL_TCP, PROTOCOL_UDP, NEXT_HEADER_ICMPV6 -> break
                NEXT_HEADER_HOP_BY_HOP, NEXT_HEADER_ROUTING, NEXT_HEADER_DEST_OPTIONS -> {
                    if (length - currOffset < 2) return null
                    nextHeader = packet[currOffset].toInt() and 0xFF
                    val extLen = ((packet[currOffset + 1].toInt() and 0xFF) + 1) * 8
                    currOffset += extLen
                }
                NEXT_HEADER_FRAGMENT -> {
                    if (length - currOffset < 8) return null
                    nextHeader = packet[currOffset].toInt() and 0xFF
                    isFrag = true
                    currOffset += 8
                }
                NEXT_HEADER_AH -> {
                    if (length - currOffset < 2) return null
                    nextHeader = packet[currOffset].toInt() and 0xFF
                    val ahLen = ((packet[currOffset + 1].toInt() and 0xFF) + 2) * 4
                    currOffset += ahLen
                }
                else -> break // protokol lain atau tidak dikenal
            }
        }

        if (currOffset > length) return null

        return Ipv6Header(
            version = version,
            payloadLength = payloadLength,
            nextHeader = nextHeader,
            hopLimit = hopLimit,
            srcIp = srcIp,
            dstIp = dstIp,
            headerLength = currOffset,
            isFragmented = isFrag
        )
    }

    /** Membangun paket IPv6+UDP lengkap (untuk balasan DNS / relay IPv6). */
    fun buildIpv6UdpPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 40 + udpLength
        val out = ByteArray(totalLength)

        // Header IPv6
        out[0] = 0x60 // Version 6
        out[1] = 0
        out[2] = 0
        out[3] = 0
        out[4] = ((udpLength shr 8) and 0xFF).toByte()
        out[5] = (udpLength and 0xFF).toByte()
        out[6] = PROTOCOL_UDP.toByte() // Next Header
        out[7] = 64 // Hop Limit
        System.arraycopy(srcIp, 0, out, 8, 16)
        System.arraycopy(dstIp, 0, out, 24, 16)

        // UDP Segment
        val udpSegment = ByteArray(udpLength)
        udpSegment[0] = ((srcPort shr 8) and 0xFF).toByte()
        udpSegment[1] = (srcPort and 0xFF).toByte()
        udpSegment[2] = ((dstPort shr 8) and 0xFF).toByte()
        udpSegment[3] = (dstPort and 0xFF).toByte()
        udpSegment[4] = ((udpLength shr 8) and 0xFF).toByte()
        udpSegment[5] = (udpLength and 0xFF).toByte()
        udpSegment[6] = 0
        udpSegment[7] = 0
        System.arraycopy(payload, 0, udpSegment, 8, payload.size)

        val checksum = transportChecksumIpv6(srcIp, dstIp, PROTOCOL_UDP, udpSegment)
        udpSegment[6] = ((checksum shr 8) and 0xFF).toByte()
        udpSegment[7] = (checksum and 0xFF).toByte()

        System.arraycopy(udpSegment, 0, out, 40, udpLength)
        return out
    }

    fun ipToString(ip: ByteArray): String = InetAddress.getByAddress(ip).hostAddress ?: "?"

    /**
     * Fase Audit-5: bangun paket TCP RST+ACK sintetis di atas IPv6.
     *
     * Dipakai `PacketTunnel` untuk membalas SYN TCP-over-IPv6 non-DNS
     * SECEPATNYA (bukan cuma "diabaikan" seperti sebelumnya) — lihat
     * dokumentasi bug kritis di header `PacketTunnel.handlePacket`/
     * `NetShieldVpnService`. RST langsung membuat TCP stack klien tahu
     * koneksi ditolak dan (lewat mekanisme Happy Eyeballs/dual-stack milik
     * OS/browser sendiri) segera mencoba jalur IPv4 yang SUDAH benar-benar
     * di-NAT & difilter oleh `TcpNatManager` — alih-alih menunggu timeout
     * IPv6 selama puluhan detik yang terasa sebagai "internet sangat
     * lambat" oleh user.
     */
    fun buildIpv6TcpRst(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long
    ): ByteArray {
        val tcpLength = 20
        val totalLength = 40 + tcpLength
        val out = ByteArray(totalLength)

        out[0] = 0x60
        out[4] = ((tcpLength shr 8) and 0xFF).toByte()
        out[5] = (tcpLength and 0xFF).toByte()
        out[6] = PROTOCOL_TCP.toByte()
        out[7] = 64
        System.arraycopy(srcIp, 0, out, 8, 16)
        System.arraycopy(dstIp, 0, out, 24, 16)

        val tcpSegment = ByteArray(tcpLength)
        tcpSegment[0] = ((srcPort shr 8) and 0xFF).toByte()
        tcpSegment[1] = (srcPort and 0xFF).toByte()
        tcpSegment[2] = ((dstPort shr 8) and 0xFF).toByte()
        tcpSegment[3] = (dstPort and 0xFF).toByte()
        writeUInt32(tcpSegment, 4, seq)
        writeUInt32(tcpSegment, 8, ack)
        tcpSegment[12] = (5 shl 4).toByte() // dataOffset=5 words (20 byte), tanpa opsi
        tcpSegment[13] = (TCP_FLAG_RST or TCP_FLAG_ACK).toByte()
        tcpSegment[14] = 0
        tcpSegment[15] = 0

        val checksum = transportChecksumIpv6(srcIp, dstIp, PROTOCOL_TCP, tcpSegment)
        tcpSegment[16] = ((checksum shr 8) and 0xFF).toByte()
        tcpSegment[17] = (checksum and 0xFF).toByte()

        System.arraycopy(tcpSegment, 0, out, 40, tcpLength)
        return out
    }

    /**
     * Fase Audit-5: bangun paket ICMPv6 "Destination Unreachable — Port
     * Unreachable" (type 1, code 4 — RFC 4443 §3.1), dibungkus header IPv6.
     *
     * Dipakai untuk membalas paket UDP-over-IPv6 non-DNS (mis. QUIC/HTTP3
     * lewat port 443 IPv6, umum dipakai CDN Meta/Google) — memberi tahu
     * klien SEGERA bahwa jalur ini tertutup, supaya lapisan atas (QUIC
     * connection migration / Happy Eyeballs) langsung mencoba jalur IPv4
     * yang sudah dinat lewat `UdpNatManager`, alih-alih menunggu retry/
     * timeout QUIC yang bisa berlangsung lama & terasa sebagai buffering
     * video tanpa sebab jelas.
     *
     * [originalPacket] wajib payload ICMPv6 sesuai RFC: sebanyak mungkin
     * byte dari paket asli yang memicu error (di sini kita sertakan utuh,
     * dipotong ke maksimal supaya total paket balasan tidak melebihi MTU).
     */
    fun buildIpv6IcmpPortUnreachable(
        srcIp: ByteArray,
        dstIp: ByteArray,
        originalPacket: ByteArray
    ): ByteArray {
        // RFC 4443: sertakan sebanyak mungkin paket asli tanpa membuat balasan melebihi MTU minimum IPv6 (1280).
        val maxOriginalBytes = (1280 - 40 - 8).coerceAtMost(originalPacket.size)
        val truncatedOriginal = originalPacket.copyOf(maxOriginalBytes)

        val icmpLength = 8 + truncatedOriginal.size
        val totalLength = 40 + icmpLength
        val out = ByteArray(totalLength)

        out[0] = 0x60
        out[4] = ((icmpLength shr 8) and 0xFF).toByte()
        out[5] = (icmpLength and 0xFF).toByte()
        out[6] = NEXT_HEADER_ICMPV6.toByte()
        out[7] = 64
        System.arraycopy(srcIp, 0, out, 8, 16)
        System.arraycopy(dstIp, 0, out, 24, 16)

        val icmpSegment = ByteArray(icmpLength)
        icmpSegment[0] = 1 // Type: Destination Unreachable
        icmpSegment[1] = 4 // Code: Port Unreachable
        icmpSegment[2] = 0 // Checksum (diisi di bawah)
        icmpSegment[3] = 0
        // Byte 4-7: Unused (harus 0)
        System.arraycopy(truncatedOriginal, 0, icmpSegment, 8, truncatedOriginal.size)

        val checksum = transportChecksumIpv6(srcIp, dstIp, NEXT_HEADER_ICMPV6, icmpSegment)
        icmpSegment[2] = ((checksum shr 8) and 0xFF).toByte()
        icmpSegment[3] = (checksum and 0xFF).toByte()

        System.arraycopy(icmpSegment, 0, out, 40, icmpLength)
        return out
    }

    // ---- UDP --------------------------------------------------------------

    data class UdpHeader(val srcPort: Int, val dstPort: Int, val length: Int, val payloadOffset: Int)

    /** [offset] adalah posisi awal header UDP di dalam [packet] (setelah header IP). */
    fun parseUdpHeader(packet: ByteArray, offset: Int, length: Int): UdpHeader? {
        if (length - offset < 8) return null
        val srcPort = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
        val dstPort = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
        val udpLength = ((packet[offset + 4].toInt() and 0xFF) shl 8) or (packet[offset + 5].toInt() and 0xFF)
        return UdpHeader(srcPort, dstPort, udpLength, offset + 8)
    }

    /**
     * Membangun paket IPv4+UDP lengkap (untuk balasan sintetis DNS atau relay UDP)
     * dan mengembalikannya sebagai ByteArray siap ditulis ke tun.
     */
    fun buildIpv4UdpPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray,
        identification: Int
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val out = ByteArray(totalLength)

        // UDP header dulu (dibutuhkan untuk hitung checksum), lalu IP header.
        val udpSegment = ByteArray(udpLength)
        udpSegment[0] = ((srcPort shr 8) and 0xFF).toByte()
        udpSegment[1] = (srcPort and 0xFF).toByte()
        udpSegment[2] = ((dstPort shr 8) and 0xFF).toByte()
        udpSegment[3] = (dstPort and 0xFF).toByte()
        udpSegment[4] = ((udpLength shr 8) and 0xFF).toByte()
        udpSegment[5] = (udpLength and 0xFF).toByte()
        udpSegment[6] = 0 // checksum placeholder
        udpSegment[7] = 0
        System.arraycopy(payload, 0, udpSegment, 8, payload.size)

        val checksum = transportChecksum(srcIp, dstIp, PROTOCOL_UDP, udpSegment)
        udpSegment[6] = ((checksum shr 8) and 0xFF).toByte()
        udpSegment[7] = (checksum and 0xFF).toByte()

        writeIpv4Header(out, 0, totalLength, PROTOCOL_UDP, srcIp, dstIp, identification)
        System.arraycopy(udpSegment, 0, out, 20, udpLength)
        return out
    }

    // ---- TCP (subset minimal untuk relay Fase 1.7) ------------------------

    const val TCP_FLAG_FIN = 0x01
    const val TCP_FLAG_SYN = 0x02
    const val TCP_FLAG_RST = 0x04
    const val TCP_FLAG_PSH = 0x08
    const val TCP_FLAG_ACK = 0x10

    data class TcpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val seq: Long,
        val ack: Long,
        val dataOffset: Int,
        val flags: Int,
        val window: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
        /** MSS yang diminta klien lewat opsi TCP di SYN (null jika tidak ada opsi/bukan SYN). Fase Audit-2. */
        val clientMss: Int? = null,
        /** true jika klien menyertakan opsi Window Scale (kind=3) di SYN — WAJIB dicek sebelum kita ikut membalas opsi WS, sesuai RFC 1323 (WS hanya aktif jika ADA di SYN *dan* SYN-ACK). Fase Audit-2. */
        val clientSupportsWindowScale: Boolean = false
    )

    /**
     * Parse opsi TCP di belakang header 20-byte dasar (MSS, Window Scale)
     * — HANYA relevan untuk paket SYN. Diabaikan untuk paket lain.
     * (Fase Audit-2, lihat CHANGELOG.md "Bug Kritis: TCP MSS/Window Scale
     * tidak dinegosiasikan".)
     */
    private fun parseTcpOptions(packet: ByteArray, tcpOffset: Int, dataOffset: Int): Pair<Int?, Boolean> {
        var mss: Int? = null
        var hasWindowScale = false
        var pos = tcpOffset + 20
        val end = tcpOffset + dataOffset
        while (pos < end && pos < packet.size) {
            val kind = packet[pos].toInt() and 0xFF
            when (kind) {
                0 -> break // End of Options List
                1 -> pos += 1 // NOP, panjang 1 byte, tidak ada field length
                else -> {
                    if (pos + 1 >= packet.size) break
                    val len = packet[pos + 1].toInt() and 0xFF
                    if (len < 2 || pos + len > packet.size) break
                    when (kind) {
                        2 -> if (len == 4) { // MSS
                            mss = ((packet[pos + 2].toInt() and 0xFF) shl 8) or (packet[pos + 3].toInt() and 0xFF)
                        }
                        3 -> hasWindowScale = true // Window Scale
                    }
                    pos += len
                }
            }
        }
        return mss to hasWindowScale
    }

    fun parseTcpHeader(packet: ByteArray, offset: Int, totalLength: Int): TcpHeader? {
        if (totalLength - offset < 20) return null
        val srcPort = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
        val dstPort = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
        val seq = readUInt32(packet, offset + 4)
        val ack = readUInt32(packet, offset + 8)
        val dataOffsetWords = (packet[offset + 12].toInt() and 0xFF) shr 4
        val dataOffset = dataOffsetWords * 4
        val flags = packet[offset + 13].toInt() and 0x3F
        val window = ((packet[offset + 14].toInt() and 0xFF) shl 8) or (packet[offset + 15].toInt() and 0xFF)
        val payloadOffset = offset + dataOffset
        val payloadLength = (totalLength - payloadOffset).coerceAtLeast(0)

        var clientMss: Int? = null
        var clientSupportsWs = false
        if ((flags and 0x02) != 0 && dataOffset > 20) { // SYN_FLAG=0x02, hanya parse opsi kalau ada opsi (dataOffset>20)
            val (mss, ws) = parseTcpOptions(packet, offset, dataOffset)
            clientMss = mss
            clientSupportsWs = ws
        }

        return TcpHeader(srcPort, dstPort, seq, ack, dataOffset, flags, window, payloadOffset, payloadLength, clientMss, clientSupportsWs)
    }

    /**
     * Bangun blok opsi TCP untuk SYN-ACK sintetis: MSS (diclamp ke [mss])
     * + Window Scale (HANYA disertakan jika [includeWindowScale] true —
     * yaitu klien memang meminta opsi ini di SYN-nya, sesuai RFC 1323).
     * Total selalu kelipatan 4 byte (di-pad NOP bila perlu) — disyaratkan
     * agar dataOffset (dalam satuan word 32-bit) valid.
     */
    fun buildSynAckOptions(mss: Int, includeWindowScale: Boolean, windowScaleShift: Int): ByteArray {
        val mssOption = byteArrayOf(2, 4, ((mss shr 8) and 0xFF).toByte(), (mss and 0xFF).toByte())
        if (!includeWindowScale) {
            // 4 byte MSS saja, sudah kelipatan 4 -> tidak perlu padding.
            return mssOption
        }
        // MSS(4) + NOP(1) + WScale(kind=3,len=3,shift)(3) = 8 byte, kelipatan 4.
        return mssOption + byteArrayOf(1, 3, 3, windowScaleShift.toByte())
    }

    /** Membangun paket IPv4+TCP (dipakai TcpNatManager untuk balas SYN-ACK/ACK/data/FIN sintetis). */
    fun buildIpv4TcpPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
        identification: Int,
        options: ByteArray = EMPTY_OPTIONS
    ): ByteArray {
        val tcpHeaderLength = 20 + options.size
        val tcpLength = tcpHeaderLength + payload.size
        val totalLength = 20 + tcpLength
        val out = ByteArray(totalLength)

        val tcpSegment = ByteArray(tcpLength)
        tcpSegment[0] = ((srcPort shr 8) and 0xFF).toByte()
        tcpSegment[1] = (srcPort and 0xFF).toByte()
        tcpSegment[2] = ((dstPort shr 8) and 0xFF).toByte()
        tcpSegment[3] = (dstPort and 0xFF).toByte()
        writeUInt32(tcpSegment, 4, seq)
        writeUInt32(tcpSegment, 8, ack)
        tcpSegment[12] = ((tcpHeaderLength / 4) shl 4).toByte()
        tcpSegment[13] = (flags and 0x3F).toByte()
        tcpSegment[14] = ((window shr 8) and 0xFF).toByte()
        tcpSegment[15] = (window and 0xFF).toByte()
        tcpSegment[16] = 0 // checksum placeholder
        tcpSegment[17] = 0
        tcpSegment[18] = 0 // urgent pointer
        tcpSegment[19] = 0
        if (options.isNotEmpty()) {
            System.arraycopy(options, 0, tcpSegment, 20, options.size)
        }
        System.arraycopy(payload, 0, tcpSegment, tcpHeaderLength, payload.size)

        val checksum = transportChecksum(srcIp, dstIp, PROTOCOL_TCP, tcpSegment)
        tcpSegment[16] = ((checksum shr 8) and 0xFF).toByte()
        tcpSegment[17] = (checksum and 0xFF).toByte()

        writeIpv4Header(out, 0, totalLength, PROTOCOL_TCP, srcIp, dstIp, identification)
        System.arraycopy(tcpSegment, 0, out, 20, tcpLength)
        return out
    }

    private val EMPTY_OPTIONS = ByteArray(0)

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun writeUInt32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}
