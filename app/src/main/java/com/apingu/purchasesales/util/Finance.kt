package com.apingu.purchasesales.util

import com.apingu.purchasesales.data.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object VatTypes {
    const val STANDARD = "STANDARD"
    const val REVERSE = "REVERSE"
    const val NO_VAT = "NO_VAT"
    val all = listOf(STANDARD, REVERSE, NO_VAT)
    fun label(value: String) = when (value) {
        STANDARD -> "Standard VAT (20%)"
        REVERSE -> "Reverse VAT"
        else -> "No VAT"
    }
}

data class VatBreakdown(val netPence: Long, val vatPence: Long, val grossPence: Long, val reverseVatPence: Long)

fun breakdownFromGross(grossPence: Long, vatType: String): VatBreakdown = when (vatType) {
    VatTypes.STANDARD -> {
        val net = BigDecimal(grossPence).divide(BigDecimal("1.20"), 0, RoundingMode.HALF_UP).longValueExact()
        VatBreakdown(net, grossPence - net, grossPence, 0)
    }
    VatTypes.REVERSE -> {
        val reverse = BigDecimal(grossPence).multiply(BigDecimal("0.20")).setScale(0, RoundingMode.HALF_UP).longValueExact()
        VatBreakdown(grossPence, 0, grossPence, reverse)
    }
    else -> VatBreakdown(grossPence, 0, grossPence, 0)
}

fun moneyToPence(text: String): Long {
    val cleaned = text.trim().replace("£", "").replace(",", "")
    if (cleaned.isBlank()) return 0
    return BigDecimal(cleaned).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
}

fun formatMoney(pence: Long): String = NumberFormat.getCurrencyInstance(Locale.UK).format(BigDecimal(pence).divide(BigDecimal(100)))
fun formatMoneyPlain(pence: Long): String = BigDecimal(pence).divide(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()
fun epochDayToday(): Long = LocalDate.now().toEpochDay()
fun dateFromEpoch(day: Long): LocalDate = LocalDate.ofEpochDay(day)
fun displayDate(day: Long): String = dateFromEpoch(day).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
fun compactDate(day: Long): String = dateFromEpoch(day).format(DateTimeFormatter.ofPattern("ddMMyy"))
fun parseDateOrToday(text: String): Long = runCatching { LocalDate.parse(text, DateTimeFormatter.ofPattern("dd/MM/yyyy")).toEpochDay() }.getOrElse { epochDayToday() }
fun editDate(day: Long): String = dateFromEpoch(day).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))


data class InventoryRow(val item: String, val purchased: Int, val received: Int, val supplierReturned: Int, val sold: Int, val customerRestocked: Int, val available: Int, val inventoryNetCostPence: Long)

data class FinanceSummary(
    val salesNet: Long = 0,
    val cogsNet: Long = 0,
    val expensesNet: Long = 0,
    val grossProfit: Long = 0,
    val netProfit: Long = 0,
    val outputVat: Long = 0,
    val inputVat: Long = 0,
    val reverseOutputVat: Long = 0,
    val reverseInputVat: Long = 0,
    val vatPosition: Long = 0,
    val inventoryValue: Long = 0,
    val refundsPending: Long = 0
)

fun buildInventory(
    purchases: List<PurchaseEntity>,
    allocations: List<SaleAllocationEntity>,
    returnAllocations: List<SaleReturnAllocationEntity>
): List<InventoryRow> {
    val soldByPurchase = allocations.groupBy { it.purchaseId }.mapValues { it.value.sumOf { a -> a.quantity } }
    val allocationById = allocations.associateBy { it.id }
    val restoredByPurchase = mutableMapOf<Long, Int>()
    returnAllocations.forEach { ra ->
        val pId = allocationById[ra.saleAllocationId]?.purchaseId ?: return@forEach
        restoredByPurchase[pId] = (restoredByPurchase[pId] ?: 0) + ra.quantity
    }
    return purchases.groupBy { it.item.trim().lowercase() }.map { (_, ps) ->
        val name = ps.first().item
        val purchased = ps.sumOf { it.quantity }
        val received = ps.sumOf { it.receivedQty }
        val supplierReturned = ps.sumOf { it.returnedQty }
        val sold = ps.sumOf { soldByPurchase[it.id] ?: 0 }
        val restored = ps.sumOf { restoredByPurchase[it.id] ?: 0 }
        val available = received - supplierReturned - sold + restored
        val value = ps.sumOf { p ->
            val pAvail = p.receivedQty - p.returnedQty - (soldByPurchase[p.id] ?: 0) + (restoredByPurchase[p.id] ?: 0)
            val unitNet = if (p.quantity > 0) p.netPence / p.quantity else 0
            pAvail.coerceAtLeast(0) * unitNet
        }
        InventoryRow(name, purchased, received, supplierReturned, sold, restored, available, value)
    }.sortedBy { it.item.lowercase() }
}

fun buildFinanceSummary(
    start: Long,
    end: Long,
    purchases: List<PurchaseEntity>,
    sales: List<SaleEntity>,
    saleLines: List<SaleLineEntity>,
    allocations: List<SaleAllocationEntity>,
    returns: List<SaleReturnEntity>,
    returnAllocations: List<SaleReturnAllocationEntity>,
    expenses: List<ExpenseEntity>
): FinanceSummary {
    val s = sales.filter { it.saleDateEpochDay in start..end }
    val e = expenses.filter { it.expenseDateEpochDay in start..end }
    val p = purchases.filter { it.purchaseDateEpochDay in start..end }
    val saleIds = s.map { it.id }.toSet()
    val saleLineIdsInPeriod = saleLines.filter { it.saleId in saleIds }.map { it.id }.toSet()

    val salesReturns = returns.filter { it.returnDateEpochDay in start..end }
    val salesReturnNet = salesReturns.sumOf { it.refundNetPence }
    val salesReturnVat = salesReturns.sumOf { it.refundVatPence }
    val salesNet = s.sumOf { it.netPence } - salesReturnNet
    val expensesNet = e.sumOf { it.netPence }
    val outputVat = s.sumOf { it.vatPence } - salesReturnVat
    val supplierRefundVat = p.sumOf { purchase ->
        val creditGross = purchase.refundExpectedPence.takeIf { it > 0 } ?: 0
        if (creditGross > 0) breakdownFromGross(creditGross.coerceAtMost(purchase.grossPence), purchase.vatType).vatPence else 0
    }
    val supplierReverseCredit = p.sumOf { purchase ->
        val creditGross = purchase.refundExpectedPence.takeIf { it > 0 } ?: 0
        if (creditGross > 0) breakdownFromGross(creditGross.coerceAtMost(purchase.grossPence), purchase.vatType).reverseVatPence else 0
    }
    val inputVat = p.sumOf { it.vatPence } - supplierRefundVat + e.sumOf { it.vatPence }
    val reverseInput = p.sumOf { it.reverseVatPence } - supplierReverseCredit + e.sumOf { it.reverseVatPence }
    val reverseOutput = reverseInput
    val inventory = buildInventory(purchases, allocations, returnAllocations).sumOf { it.inventoryNetCostPence }
    val refundsPending = purchases.sumOf { (it.refundExpectedPence - it.refundReceivedPence).coerceAtLeast(0) }

    val allocationById = allocations.associateBy { it.id }
    val cogsOnPeriodSales = allocations.filter { it.saleLineId in saleLineIdsInPeriod }.sumOf { it.quantity * it.unitNetCostPence }
    val returnIdsInPeriod = salesReturns.map { it.id }.toSet()
    val cogsReversedOnPeriodReturns = returnAllocations.filter { it.saleReturnId in returnIdsInPeriod }.sumOf { ra ->
        val allocation = allocationById[ra.saleAllocationId]
        if (allocation == null) 0L else ra.quantity * allocation.unitNetCostPence
    }
    val cogs = cogsOnPeriodSales - cogsReversedOnPeriodReturns
    val grossProfit = salesNet - cogs
    val netProfit = grossProfit - expensesNet
    return FinanceSummary(
        salesNet = salesNet,
        cogsNet = cogs,
        expensesNet = expensesNet,
        grossProfit = grossProfit,
        netProfit = netProfit,
        outputVat = outputVat,
        inputVat = inputVat,
        reverseOutputVat = reverseOutput,
        reverseInputVat = reverseInput,
        vatPosition = outputVat + reverseOutput - inputVat - reverseInput,
        inventoryValue = inventory,
        refundsPending = refundsPending
    )
}
