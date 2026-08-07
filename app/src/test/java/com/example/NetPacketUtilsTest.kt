package com.example

import com.example.vpn.NetPacketUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * === CHANGELOG ===
 * [Fase 7 - 2026-08-07] Unit test untuk [NetPacketUtils].
 * Memastikan parsing header IPv4, UDP, dan kalkulasi checksum Internet.
 */
class NetPacketUtilsTest {

    @Test
    fun parseIpv4Header_validPacket_returnsHeaderInfo() {
        val ipv4Packet = byteArrayOf(
            0x45.toByte(), 0x00.toByte(), // Version 4, IHL 5 (20 bytes)
            0x00.toByte(), 0x14.toByte(), // Total length: 20
            0x12.toByte(), 0x34.toByte(), // Identification
            0x00.toByte(), 0x00.toByte(), // Flags & Fragment offset
            0x40.toByte(), 0x11.toByte(), // TTL 64, Protocol 17 (UDP)
            0x00.toByte(), 0x00.toByte(), // Checksum placeholder
            192.toByte(), 168.toByte(), 1.toByte(), 10.toByte(), // Source IP: 192.168.1.10
            1.toByte(), 1.toByte(), 1.toByte(), 1.toByte()        // Dest IP: 1.1.1.1
        )

        val header = NetPacketUtils.parseIpv4Header(ipv4Packet, ipv4Packet.size)

        assertNotNull(header)
        assertEquals(4, header!!.version)
        assertEquals(20, header.headerLength)
        assertEquals(20, header.totalLength)
        assertEquals(17, header.protocol)
        assertEquals("192.168.1.10", NetPacketUtils.ipToString(header.srcIp))
        assertEquals("1.1.1.1", NetPacketUtils.ipToString(header.dstIp))
    }

    @Test
    fun parseIpv4Header_nonIpv4Version_returnsNull() {
        val ipv6Header = byteArrayOf(
            0x60.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        )

        val header = NetPacketUtils.parseIpv4Header(ipv6Header, ipv6Header.size)
        assertNull(header)
    }

    @Test
    fun parseUdpHeader_validUdpPacket_returnsPortsAndLength() {
        val fullPacket = ByteArray(28)
        // Offset 20 is UDP header
        val udpHeaderBytes = byteArrayOf(
            0xD0.toByte(), 0x00.toByte(), // Source Port: 53248
            0x00.toByte(), 0x35.toByte(), // Dest Port: 53 (DNS)
            0x00.toByte(), 0x1C.toByte(), // Length: 28
            0x12.toByte(), 0x34.toByte()  // Checksum
        )
        System.arraycopy(udpHeaderBytes, 0, fullPacket, 20, 8)

        val udpHeader = NetPacketUtils.parseUdpHeader(fullPacket, 20, fullPacket.size)

        assertNotNull(udpHeader)
        assertEquals(53248, udpHeader!!.srcPort)
        assertEquals(53, udpHeader.dstPort)
        assertEquals(28, udpHeader.length)
    }

    @Test
    fun calculateChecksum_computesCorrectChecksum() {
        val data = byteArrayOf(0x45.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3C.toByte())
        val checksum = NetPacketUtils.internetChecksum(data, 0, data.size)
        assertEquals(0xBAC3, checksum and 0xFFFF)
    }
}
