package com.apingu.purchasesales.util

import com.apingu.purchasesales.data.EmailAccountEntity
import com.apingu.purchasesales.data.PurchaseInvoiceEntity
import com.apingu.purchasesales.data.PurchaseOrderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvoiceFinderRulesTest {
    private fun order(mail: String = "orders@gmail.com", number: String = "ORDER-123") =
        PurchaseOrderEntity(
            id = 42, purchaseDateEpochDay = 20740, supplier = "Costco",
            orderNumber = number, accountUsername = mail, vatType = "STANDARD"
        )

    private fun account(mail: String = "orders@gmail.com") =
        EmailAccountEntity(emailAddress = mail, provider = "GMAIL", connectionState = "CONNECTED")

    @Test fun connectedExactMailboxAndOrderNumberAreRequired() {
        assertNull(InvoiceFinderRules.blockReason(order(), emptyList(), listOf(account())))
        assertEquals("Order number required",
            InvoiceFinderRules.blockReason(order(number = ""), emptyList(), listOf(account())))
        assertEquals("Purchasing email required",
            InvoiceFinderRules.blockReason(order(mail = ""), emptyList(), listOf(account())))
        assertEquals("Valid purchasing email required",
            InvoiceFinderRules.blockReason(order(mail = "costco account"), emptyList(), listOf(account())))
    }

    @Test fun differentPurchasingMailboxMustNeverBeSearched() {
        assertEquals("Purchasing email account not connected",
            InvoiceFinderRules.blockReason(order(mail = "second@gmail.com"),
                emptyList(), listOf(account())))
    }

    @Test fun approvedInvoicePreventsGeneralSearch() {
        val invoice = PurchaseInvoiceEntity(
            purchaseOrderId = 42, invoiceOrdinal = 1, filePath = "/tmp/invoice.pdf"
        )
        assertEquals("Already has an approved invoice",
            InvoiceFinderRules.blockReason(order(), listOf(invoice), listOf(account())))
    }

    @Test fun disabledAccountIsNotEligible() {
        assertEquals("Purchasing email account not connected",
            InvoiceFinderRules.blockReason(order(), emptyList(),
                listOf(account().copy(isEnabled = false))))
    }
}
