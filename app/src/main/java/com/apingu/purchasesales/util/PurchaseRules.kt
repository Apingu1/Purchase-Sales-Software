package com.apingu.purchasesales.util

import com.apingu.purchasesales.data.PurchaseEntity

/**
 * A true voided purchase line: all ordered units were cancelled and the expected supplier refund
 * has been received in full. Partial price adjustments deliberately never qualify.
 */
fun isCancelledAndFullyRefundedPurchase(purchase: PurchaseEntity): Boolean =
    !purchase.partialRefund &&
        purchase.quantity > 0 &&
        purchase.cancelledQty >= purchase.quantity &&
        purchase.refundExpectedPence > 0 &&
        purchase.refundReceivedPence >= purchase.refundExpectedPence

/**
 * Used for document cleanup. A supplier invoice is order-level, so it is only removed from Dropbox
 * when every line on that purchase order has been cancelled and fully refunded.
 */
fun isCancelledAndFullyRefundedOrder(lines: List<PurchaseEntity>): Boolean =
    lines.isNotEmpty() && lines.all(::isCancelledAndFullyRefundedPurchase)
