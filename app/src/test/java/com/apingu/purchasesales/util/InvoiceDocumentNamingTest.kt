package com.apingu.purchasesales.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class InvoiceDocumentNamingTest {
    private val day = LocalDate.of(2026, 10, 14).toEpochDay()

    @Test fun sameSupplierDifferentPurchasesHaveStableDailySequences() {
        assertEquals("14 Oct 2026 - Costco #1.pdf",
            InvoiceDocumentNaming.invoiceFileName(day, "Costco", 1, 1))
        assertEquals("14 Oct 2026 - Costco #2.pdf",
            InvoiceDocumentNaming.invoiceFileName(day, "Costco", 2, 1))
        assertEquals("14 Oct 2026 - Costco #2 - Invoice 2.pdf",
            InvoiceDocumentNaming.invoiceFileName(day, "Costco", 2, 2))
        assertEquals("15 Oct 2026 - Costco #1.pdf",
            InvoiceDocumentNaming.invoiceFileName(day + 1, "Costco", 1, 1))
    }

    @Test fun supplierGroupingIgnoresCaseAndExcessSpaces() {
        assertEquals("costco wholesale",
            InvoiceDocumentNaming.supplierKey("  COSTCO   Wholesale  "))
    }

    @Test fun filenamesDoNotAllowPathSeparatorsOrTraversal() {
        val name = InvoiceDocumentNaming.invoiceFileName(day, "../Costco\\VIP", 1, 2, "pdf")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains(".."))
        assertEquals("14 Oct 2026 - Costco VIP #1 - Invoice 2.pdf", name)
    }

    @Test fun invalidOrdinalsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            InvoiceDocumentNaming.invoiceFileName(day, "Costco", 1, 0)
        }
    }
}
