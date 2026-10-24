package com.apingu.purchasesales.ui

/**
 * Rules kept separate from the camera UI so that IMEIs cannot be confused with
 * retail product barcodes (such as the EAN-13 codes on the example packaging).
 */
object ImeiBarcodeRules {
    private val imeiPattern = Regex("(?<![0-9])[0-9]{15}(?![0-9])")
    private val serialPattern = Regex("[A-Za-z0-9_-]{6,40}")

    fun isValidImei(value: String): Boolean {
        if (value.length != 15 || value.any { !it.isDigit() }) return false
        // The final digit is the IMEI Luhn check digit.
        return value.reversed().mapIndexed { index, character ->
            val digit = character.digitToInt()
            if (index % 2 == 1) {
                val doubled = digit * 2
                doubled / 10 + doubled % 10
            } else digit
        }.sum() % 10 == 0
    }

    fun imeisFromBarcode(raw: String): List<String> =
        imeiPattern.findAll(raw)
            .map { it.value }
            .filter(::isValidImei)
            .distinct()
            .toList()

    /**
     * When serial mode is enabled, accept conventional serials, but continue
     * rejecting EAN-8/EAN-13/UPC-A numeric retail product barcodes.
     */
    fun fromBarcode(raw: String, allowSerial: Boolean): List<String> {
        val imeis = imeisFromBarcode(raw)
        if (imeis.isNotEmpty()) return imeis
        if (!allowSerial) return emptyList()
        val candidate = raw.trim().replace(Regex("(?i)^(IMEI|S/N|SN|SERIAL)\\s*[:#-]?\\s*"), "").trim()
        if (!serialPattern.matches(candidate)) return emptyList()
        if (candidate.all { it.isDigit() } && candidate.length in setOf(8, 12, 13)) return emptyList()
        return listOf(candidate.uppercase())
    }

    fun existingImeis(notes: String): List<String> =
        notes.lineSequence().flatMap { imeisFromBarcode(it).asSequence() }.distinct().toList()

    /** Only replace recognised IMEIs. Preserve free-text item notes and legacy serials. */
    fun replaceImeisInNotes(originalNotes: String, scanned: List<String>): String {
        val retained = originalNotes.lines().map { it.trim() }
            .filter { it.isNotBlank() && imeisFromBarcode(it).isEmpty() }
        return (retained + scanned.distinct()).joinToString("\n")
    }
}
