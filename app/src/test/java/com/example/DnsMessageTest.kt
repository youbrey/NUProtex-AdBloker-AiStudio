package com.example

import com.example.vpn.DnsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * === CHANGELOG ===
 * [Fase 7 - 2026-08-07] Unit test untuk DNS parser & generator (DnsMessage).
 * Memastikan parsing query byte-level & pembuatan response NXDOMAIN/0.0.0.0 berjalan akurat.
 */
class DnsMessageTest {

    @Test
    fun parseQuery_validDnsPacket_returnsCorrectDomainAndId() {
        // DNS Query packet untuk "example.com" A-record (Type 1, Class 1)
        // Transaction ID: 0x1234, Flags: 0x0100 (Standard query)
        val sampleQueryBytes = byteArrayOf(
            0x12.toByte(), 0x34.toByte(), // Transaction ID: 0x1234
            0x01.toByte(), 0x00.toByte(), // Flags: Standard query
            0x00.toByte(), 0x01.toByte(), // Questions: 1
            0x00.toByte(), 0x00.toByte(), // Answer RRs: 0
            0x00.toByte(), 0x00.toByte(), // Authority RRs: 0
            0x00.toByte(), 0x00.toByte(), // Additional RRs: 0
            // QNAME: 7 "example" 3 "com" 0
            0x07.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            0x03.toByte(), 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00.toByte(), // End of QNAME
            0x00.toByte(), 0x01.toByte(), // QTYPE: A (1)
            0x00.toByte(), 0x01.toByte()  // QCLASS: IN (1)
        )

        val query = DnsMessage.parseQuery(sampleQueryBytes)

        assertNotNull(query)
        assertEquals(0x1234, query!!.id)
        assertEquals("example.com", query.domain)
        assertEquals(1, query.qType)
    }

    @Test
    fun parseQuery_invalidHeader_returnsNull() {
        val shortBytes = byteArrayOf(0x00, 0x01, 0x02)
        val query = DnsMessage.parseQuery(shortBytes)
        assertNull(query)
    }

    @Test
    fun buildBlockedResponse_nxDomain_generatesValidResponsePacket() {
        val sampleQueryBytes = byteArrayOf(
            0xAB.toByte(), 0xCD.toByte(), // Transaction ID: 0xABCD
            0x01.toByte(), 0x00.toByte(), // Flags
            0x00.toByte(), 0x01.toByte(), // Questions: 1
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x03.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 's'.code.toByte(),
            0x00.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x01.toByte()
        )

        val query = DnsMessage.parseQuery(sampleQueryBytes)
        assertNotNull(query)

        val responseBytes = DnsMessage.buildBlockedResponse(sampleQueryBytes, query!!, nxDomain = true)
        assertNotNull(responseBytes)
        assertEquals(sampleQueryBytes.size, responseBytes.size)

        // Verifikasi Transaction ID di response cocok dengan query (0xABCD)
        assertEquals(0xAB.toByte(), responseBytes[0])
        assertEquals(0xCD.toByte(), responseBytes[1])
    }

    @Test
    fun buildBlockedResponse_zeroIp_generatesResponseWithAAnswer() {
        val sampleQueryBytes = byteArrayOf(
            0xAB.toByte(), 0xCD.toByte(),
            0x01.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x03.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 's'.code.toByte(),
            0x00.toByte(),
            0x00.toByte(), 0x01.toByte(), // QTYPE A
            0x00.toByte(), 0x01.toByte()
        )

        val query = DnsMessage.parseQuery(sampleQueryBytes)
        assertNotNull(query)

        val responseBytes = DnsMessage.buildBlockedResponse(sampleQueryBytes, query!!, nxDomain = false)
        assertNotNull(responseBytes)
        assertTrue(responseBytes.size > sampleQueryBytes.size)
    }
}
