package com.apingu.purchasesales.util

import com.apingu.purchasesales.data.PurchaseEntity

/**
 * A true voided purchase line for accounting export: all ordered units were cancelled and the
 * expected supplier refund has been received in full. Partial price adjustments never qualify.
 */
fun isCancelledAndFullyRefundedPurchase(purchase: PurchaseEntity): Boolean =
    !purchase.partialRefund &&
        purchase.quantity > 0 &&
        purchase.cancelledQty >= purchase.quantity &&
        purchase.refundExpectedPence > 0 &&
        purchase.refundReceivedPence >= purchase.refundExpectedPence

/**
 * Used for Dropbox document cleanup. An order line is financially/physically closed when every
 * unit was either cancelled before receipt or returned after receipt, and the supplier refund is
 * complete. This intentionally excludes partial monetary refunds where the item is retained.
 */
private fun isClosedAndFullyRefundedPurchase(purchase: PurchaseEntity): Boolean =
    !purchase.partialRefund &&
        purchase.quantity > 0 &&
        purchase.cancelledQty + purchase.returnedQty >= purchase.quantity &&
        purchase.refundExpectedPence > 0 &&
        purchase.refundReceivedPence >= purchase.refundExpectedPence

/** A supplier invoice is removed only when every line on the order is fully closed/refunded. */
fun isCancelledAndFullyRefundedOrder(lines: List<PurchaseEntity>): Boolean =
    lines.isNotEmpty() && lines.all(::isClosedAndFullyRefundedPurchase)

/** Clearer alias used by the period-aware Dropbox synchroniser. */
fun isFullyRefundedOrderForDropbox(lines: List<PurchaseEntity>): Boolean =
    isCancelledAndFullyRefundedOrder(lines)
