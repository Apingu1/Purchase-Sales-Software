package com.apingu.purchasesales.util

import com.apingu.purchasesales.data.PurchaseOrderEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryAccountingTest {
    private fun summary(gross: Long, vatType: String): FinanceSummary =
        buildFinanceSummary(
            start = 20000,
            end = 21000,
            purchases = emptyList(),
            sales = emptyList(),
            saleLines = emptyList(),
            allocations = emptyList(),
            returns = emptyList(),
            returnAllocations = emptyList(),
            expenses = emptyList(),
            purchaseOrders = listOf(
                PurchaseOrderEntity(
                    id = 1,
                    purchaseDateEpochDay = 20740,
                    supplier = "Supplier Ltd",
                    vatType = vatType,
                    deliveryGrossPence = gross
                )
            )
        )

    @Test fun standardVatDeliveryIsAnExpenseAndInputVatButNeverInventory() {
        val actual = summary(1200, VatTypes.STANDARD)
        assertEquals(1200L, actual.deliveryGross)
        assertEquals(1000L, actual.deliveryNet)
        assertEquals(200L, actual.inputVat)
        assertEquals(-200L, actual.vatPosition)
        assertEquals(-1000L, actual.netProfit)
        assertEquals(0L, actual.inventoryValue)
    }

    @Test fun zeroDeliveryChangesNothing() {
        val actual = summary(0, VatTypes.STANDARD)
        assertEquals(0L, actual.deliveryGross)
        assertEquals(0L, actual.deliveryNet)
        assertEquals(0L, actual.inputVat)
        assertEquals(0L, actual.vatPosition)
    }

    @Test fun reverseChargeDeliveryBalancesInputAndOutputVat() {
        val actual = summary(1200, VatTypes.REVERSE)
        assertEquals(0L, actual.inputVat)
        assertEquals(240L, actual.reverseInputVat)
        assertEquals(240L, actual.reverseOutputVat)
        assertEquals(0L, actual.vatPosition)
    }

    @Test fun noVatDeliveryStillReducesProfitWithoutInputVat() {
        val actual = summary(1000, VatTypes.NO_VAT)
        assertEquals(1000L, actual.deliveryNet)
        assertEquals(0L, actual.inputVat)
        assertEquals(-1000L, actual.netProfit)
    }
}
