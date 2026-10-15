package com.apingu.purchasesales.util

import com.apingu.purchasesales.data.EmailAccountEntity
import com.apingu.purchasesales.data.PurchaseInvoiceEntity
import com.apingu.purchasesales.data.PurchaseOrderEntity

/**
 * Search is purchase-specific, never cross-account. These checks will gate
 * future mailbox retrieval, not just the visible Invoice Finder button.
 */
object InvoiceFinderRules {
    private val emailPattern = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

    fun blockReason(
        order: PurchaseOrderEntity,
        approvedInvoices: List<PurchaseInvoiceEntity>,
        emailAccounts: List<EmailAccountEntity>
    ): String? {
        if (approvedInvoices.any { it.purchaseOrderId == order.id }) return "Already has an approved invoice"
        if (order.orderNumber.isBlank()) return "Order number required"
        val address = order.accountUsername.trim()
        if (address.isBlank()) return "Purchasing email required"
        if (!emailPattern.matches(address)) return "Valid purchasing email required"
        val account = emailAccounts.firstOrNull {
            it.emailAddress.trim().equals(address, ignoreCase = true) &&
                it.isEnabled && it.connectionState == "CONNECTED"
        }
        if (account == null) return "Purchasing email account not connected"
        return null
    }
}
