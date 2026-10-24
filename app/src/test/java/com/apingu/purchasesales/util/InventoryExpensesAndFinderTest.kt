package com.apingu.purchasesales.util

import com.apingu.purchasesales.data.ExpenseEntity
import com.apingu.purchasesales.data.PurchaseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryExpensesAndFinderTest {
    private fun purchase(
        id: Long, quantity: Int = 1, received: Int = 0, cancelled: Int = 0,
        status: String = "RECEIPT_PENDING", refundExpected: Long = 0,
        refundReceived: Long = 0
    ) = PurchaseEntity(
        id = id, purchaseDateEpochDay = 20740, supplier = "Supplier",
        item = "iPhone 15", quantity = quantity, grossPence = quantity * 12000L,
        netPence = quantity * 10000L, vatPence = quantity * 2000L, reverseVatPence = 0,
        vatType = VatTypes.STANDARD, status = status, receivedQty = received,
        cancelledQty = cancelled, refundExpectedPence = refundExpected,
        refundReceivedPence = refundReceived
    )

    @Test fun stockValuationShowsGrossAndNetOfOnlyAvailableUnits() {
        val rows = buildInventory(listOf(purchase(1, quantity = 3, received = 2)),
            emptyList(), emptyList())
        assertEquals(2, rows.single().available)
        assertEquals(20000L, rows.single().inventoryNetCostPence)
        assertEquals(24000L, rows.single().inventoryGrossCostPence)
    }

    @Test fun pendingInventoryConsolidatesAndExcludesFullyCancelledAndRefundedItems() {
        val rows = buildPendingInventory(listOf(
            purchase(1, quantity = 3, received = 2),
            purchase(2, quantity = 1),
            purchase(3, cancelled = 1, status = "CANCELLED"),
            purchase(4, status = "REFUND_RECEIVED", refundExpected = 12000,
                refundReceived = 12000)
        ))
        assertEquals(1, rows.size)
        assertEquals(2, rows.single().quantity)
        assertEquals(20000L, rows.single().netCostPence)
        assertEquals(24000L, rows.single().grossCostPence)
    }

    @Test fun refundedPurchaseIsInvoiceEligibleButCancelledIsNot() {
        assertTrue(isCancelledOrderForInvoiceFinder(listOf(
            purchase(1, cancelled = 1, status = "CANCELLED")
        )))
        assertFalse(isCancelledOrderForInvoiceFinder(listOf(
            purchase(2, cancelled = 1, status = "REFUND_RECEIVED",
                refundExpected = 12000, refundReceived = 12000)
        )))
    }

    @Test fun expensesLowerProfitAndClaimInputVatWithoutCreatingInventory() {
        val summary = buildFinanceSummary(
            20000, 21000, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(),
            listOf(ExpenseEntity(expenseDateEpochDay = 20740, supplier = "Broadband",
                details = "Internet", account = "Utilities", grossPence = 12000,
                netPence = 10000, vatPence = 2000, reverseVatPence = 0,
                vatType = VatTypes.STANDARD))
        )
        assertEquals(10000L, summary.expensesNet)
        assertEquals(-10000L, summary.netProfit)
        assertEquals(2000L, summary.inputVat)
        assertEquals(0L, summary.inventoryValue)
    }
}
