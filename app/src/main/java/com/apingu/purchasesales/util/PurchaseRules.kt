package com.apingu.purchasesales.util

import com.apingu.purchasesales.data.PurchaseEntity

/**
 * A purchase is fully cancelled when no ordered units remain live. Finance uses this rule
 * independently of refund-field history: a supplier order that was cancelled before supply must
 * not leave purchase/input VAT sitting on the dashboard just because an older record has blank or
 * differently-entered refund fields.
 */
fun isFullyCancelledPurchase(purchase: PurchaseEntity): Boolean =
    !purchase.partialRefund &&
        purchase.quantity > 0 &&
        (
            purchase.cancelledQty >= purchase.quantity ||
                (purchase.status == "CANCELLED" && purchase.receivedQty <= 0)
            )

/**
 * Refund completion for old and new records. New records normally have refundExpectedPence.
 * Some older records may only have a received value, so a received credit covering the full
 * original gross value is also accepted as a complete refund.
 */
private fun isFullSupplierRefundComplete(purchase: PurchaseEntity): Boolean = when {
    purchase.refundExpectedPence > 0 -> purchase.refundReceivedPence >= purchase.refundExpectedPence
    purchase.refundReceivedPence > 0 -> purchase.refundReceivedPence >= purchase.grossPence
    else -> false
}

/**
 * A true cancelled + refunded purchase line for accounting export. Partial price adjustments never
 * qualify. Kept separate from the dashboard rule because a cancelled order with money still owed
 * should remain visible in records/refund tracking even though it contributes zero input VAT.
 */
fun isCancelledAndFullyRefundedPurchase(purchase: PurchaseEntity): Boolean =
    isFullyCancelledPurchase(purchase) && isFullSupplierRefundComplete(purchase)

/**
 * Used for Dropbox document cleanup. An order line is financially/physically closed when every
 * unit was either cancelled before receipt or returned after receipt, and the supplier refund is
 * complete. This intentionally excludes partial monetary refunds where the item is retained.
 */
private fun isClosedAndFullyRefundedPurchase(purchase: PurchaseEntity): Boolean =
    !purchase.partialRefund &&
        purchase.quantity > 0 &&
        purchase.cancelledQty + purchase.returnedQty >= purchase.quantity &&
        isFullSupplierRefundComplete(purchase)

/**
 * Finance/dashboard rule. A fully cancelled line is immediately excluded from purchase VAT even if
 * a supplier refund is still outstanding. A received purchase is excluded only when all units have
 * subsequently been closed/returned and the full supplier refund is complete.
 */
fun isVoidedPurchaseForAccounting(purchase: PurchaseEntity): Boolean =
    isFullyCancelledPurchase(purchase) || isClosedAndFullyRefundedPurchase(purchase)

/** A supplier invoice is removed only when every line on the order is fully closed/refunded. */
fun isCancelledAndFullyRefundedOrder(lines: List<PurchaseEntity>): Boolean =
    lines.isNotEmpty() && lines.all(::isClosedAndFullyRefundedPurchase)

/** Clearer alias used by the period-aware Dropbox synchroniser. */
fun isFullyRefundedOrderForDropbox(lines: List<PurchaseEntity>): Boolean =
    isCancelledAndFullyRefundedOrder(lines)
