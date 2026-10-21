package com.riyadm.wakenudge.missions.nfc

/** Helpers for the hardware identifier (UID) of an NFC tag, which is what the NFC mission registers and compares. */
object NfcTagIds {
    /** Upper-case hex without separators, e.g. `04A1B2C3D4E580`. */
    fun toHex(id: ByteArray): String = id.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    /** Tolerates case and `:`/`-`/space separators, so ids typed or exported by other tools still compare equal. */
    fun matches(registered: String, scanned: String): Boolean = canonical(registered).let { it.isNotEmpty() && it == canonical(scanned) }

    private fun canonical(id: String): String = id.filter { it.isLetterOrDigit() }.uppercase()

    /** "04:A1:B2:C3" style for display. */
    fun pretty(id: String): String = canonical(id).chunked(2).joinToString(":")

    /**
     * Phones emulating a card, and some privacy-focused tags, send a random 4-byte UID starting with 0x08 that changes
     * on every tap. Such a tag can never be recognised again, so setup rejects it.
     */
    fun isRandomUid(id: ByteArray): Boolean = id.size == 4 && id[0] == 0x08.toByte()
}
