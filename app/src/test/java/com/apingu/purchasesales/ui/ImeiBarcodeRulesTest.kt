package com.apingu.purchasesales.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeiBarcodeRulesTest {
    private val valid = "490154203237518"
    private val second = "356938035643809"

    @Test fun acceptsValidImeiAndRejectsWrongCheckDigitAndProductBarcode() {
        assertTrue(ImeiBarcodeRules.isValidImei(valid))
        assertFalse(ImeiBarcodeRules.isValidImei("490154203237519"))
        assertFalse(ImeiBarcodeRules.isValidImei("5000382122296"))
        assertEquals(emptyList<String>(), ImeiBarcodeRules.fromBarcode("5061045901160", false))
    }

    @Test fun collectsMultipleImeisInOneQrAndDeduplicates() {
        assertEquals(listOf(valid, second),
            ImeiBarcodeRules.fromBarcode("IMEI1: $valid IMEI2: $second IMEI1: $valid", false))
    }

    @Test fun serialModeCannotTreatCommonEanAsSerial() {
        assertEquals(listOf("AB12CD345"), ImeiBarcodeRules.fromBarcode("SN: AB12CD345", true))
        assertEquals(emptyList<String>(), ImeiBarcodeRules.fromBarcode("5000382122296", true))
        assertEquals(emptyList<String>(), ImeiBarcodeRules.fromBarcode("12345678", true))
    }

    @Test fun replacingScannedImeisPreservesFreeTextAndAllowsDeletion() {
        val current = "Colour: black\n$valid\nOriginal packaging"
        assertEquals("Colour: black\nOriginal packaging\n$second",
            ImeiBarcodeRules.replaceImeisInNotes(current, listOf(second)))
        assertEquals("Colour: black\nOriginal packaging",
            ImeiBarcodeRules.replaceImeisInNotes(current, emptyList()))
    }

    @Test fun existingImeisFromNotesAreUnique() {
        assertEquals(listOf(valid), ImeiBarcodeRules.existingImeis("$valid\nIMEI: $valid"))
    }
}
