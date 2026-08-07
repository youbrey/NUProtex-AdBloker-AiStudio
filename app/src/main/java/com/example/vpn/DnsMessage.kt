package com.example.vpn

/**
 * Parsing & pembuatan pesan DNS mentah (format RFC 1035) di atas payload UDP.
 * Cukup untuk kebutuhan NetShield: baca query pertama (QNAME + QTYPE + QCLASS),
 * dan membangun balasan sintetis (NXDOMAIN atau A-record 0.0.0.0) untuk domain
 * yang diblokir.
 *
 * === CHANGELOG ===
 * [Fase 1 - 2026-08-07] Baru dibuat (Fase 1.3 & 1.4 RENCANA_PRODUKSI_NETSHIELD.md).
 */
object DnsMessage {

    data class ParsedQuery(
        val id: Int,
        val domain: String,
        val qType: Int,
        val qClass: Int,
        /** offset akhir pertanyaan (setelah QCLASS) di dalam payload asli — dipakai saat forward mentah. */
        val questionEndOffset: Int
    )

    /** Parsing minimal: hanya mengambil pertanyaan (question) pertama dari pesan DNS. */
    fun parseQuery(payload: ByteArray): ParsedQuery? {
        if (payload.size < 12) return null // header DNS = 12 byte

        val id = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val qdCount = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        val nameBuilder = StringBuilder()
        var pos = 12
        while (pos < payload.size) {
            val len = payload[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                break
            }
            // Kompresi pointer (0xC0) tidak diharapkan pada QNAME query asli dari klien,
            // tapi dijaga agar parser tidak infinite-loop bila ditemui.
            if (len and 0xC0 == 0xC0) return null
            if (pos + 1 + len > payload.size) return null
            if (nameBuilder.isNotEmpty()) nameBuilder.append('.')
            nameBuilder.append(String(payload, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        if (pos + 4 > payload.size) return null

        val qType = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
        val qClass = ((payload[pos + 2].toInt() and 0xFF) shl 8) or (payload[pos + 3].toInt() and 0xFF)
        val questionEnd = pos + 4

        return ParsedQuery(id, nameBuilder.toString().lowercase(), qType, qClass, questionEnd)
    }

    /**
     * Membangun balasan DNS sintetis untuk domain yang diblokir.
     * - Jika [nxDomain] true: RCODE=3 (NXDOMAIN), tanpa answer record.
     * - Jika false: RCODE=0 (NOERROR) dengan satu A-record menunjuk ke 0.0.0.0
     *   (dipakai untuk QTYPE A supaya aplikasi klien langsung gagal konek,
     *   bukan menunggu error yang kadang di-retry berkali-kali oleh resolver OS).
     */
    fun buildBlockedResponse(originalQuery: ByteArray, query: ParsedQuery, nxDomain: Boolean): ByteArray {
        val header = ByteArray(12)
        header[0] = ((query.id shr 8) and 0xFF).toByte()
        header[1] = (query.id and 0xFF).toByte()

        val useAnswer = !nxDomain && query.qType == 1 // QTYPE A
        val rcode = if (nxDomain || query.qType != 1) 3 else 0 // NXDOMAIN kalau bukan tipe A yang kita jawab

        // flags: QR=1 (response), Opcode=0, AA=0, TC=0, RD=1 (disalin niat client), RA=1, RCODE
        val rd = if (originalQuery.size > 2) (originalQuery[2].toInt() and 0x01) else 1
        header[2] = ((0x80) or (rd shl 0)).toByte() // QR=1 + RD passthrough sederhana
        header[3] = ((0x80) or rcode).toByte() // RA=1 + RCODE

        header[4] = 0; header[5] = 1 // QDCOUNT = 1
        val ancount = if (useAnswer) 1 else 0
        header[6] = 0; header[7] = ancount.toByte() // ANCOUNT
        header[8] = 0; header[9] = 0 // NSCOUNT
        header[10] = 0; header[11] = 0 // ARCOUNT

        // Salin kembali bagian "question" apa adanya dari query asli (wajib ada di response DNS yang valid).
        val question = originalQuery.copyOfRange(12, query.questionEndOffset)

        val answer = if (useAnswer) buildAAnswerPointingToRoot() else ByteArray(0)

        return header + question + answer
    }

    /** Answer record A menunjuk ke domain (via pointer 0xC00C ke QNAME di offset 12) dengan IP 0.0.0.0. */
    private fun buildAAnswerPointingToRoot(): ByteArray {
        val answer = ByteArray(16)
        answer[0] = 0xC0.toByte(); answer[1] = 0x0C // pointer ke offset 12 (QNAME)
        answer[2] = 0; answer[3] = 1 // TYPE = A
        answer[4] = 0; answer[5] = 1 // CLASS = IN
        // TTL = 60 detik agar klien tidak cache lama-lama seandainya rule di-toggle user
        answer[6] = 0; answer[7] = 0; answer[8] = 0; answer[9] = 60
        answer[10] = 0; answer[11] = 4 // RDLENGTH = 4
        answer[12] = 0; answer[13] = 0; answer[14] = 0; answer[15] = 0 // 0.0.0.0
        return answer
    }
}
