package com.apingu.purchasesales.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ElevatedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compatibility overload for Material 3's clickable ElevatedCard.
 *
 * Several V1 screens were written using the older positional style
 * `ElevatedCard(modifier, onClick = { ... })`. Material 3 now places
 * `onClick` before `modifier`, so the original calls fail Kotlin overload
 * resolution. Keeping this adapter in our UI package preserves the concise
 * call sites while delegating to the supported named-argument API.
 */
@Composable
fun ElevatedCard(
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ElevatedCard(
        onClick = onClick,
        modifier = modifier,
        content = content,
    )
}
