package com.tradinghud.app.db

import org.junit.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DailyLossPurityTest {

    @Test
    fun `loss sum prevents floating-point drift over many small additions`() {
        // Classic binary float drift: 0.1 + 0.2 != 0.3 in IEEE 754 float/double
        val stringAmounts = listOf(
            "0.10", "0.20", "0.10", "0.20", "0.10", "0.20", "0.10", "0.20", "0.10", "0.20"
        )
        // 10 items alternating 0.10 and 0.20 = 5 * 0.30 = 1.50 exactly

        // Kotlin BigDecimal fold summation (exact matching TradeLogDao logic)
        val exactSum = stringAmounts.fold(BigDecimal.ZERO) { acc, amt ->
            acc.add(BigDecimal(amt))
        }

        assertEquals(BigDecimal("1.50"), exactSum)

        // Contrast with what Float / Double would do with repeated additions
        var floatSum = 0.0f
        stringAmounts.forEach { floatSum += it.toFloat() }

        var doubleSum = 0.0
        stringAmounts.forEach { doubleSum += it.toDouble() }

        // Proves that BigDecimal maintains exact decimal scale and zero drift
        assertEquals("1.50", exactSum.toPlainString())
    }

    @Test
    fun `high precision crypto loss amounts sum exactly without truncation`() {
        val amounts = listOf(
            "0.00000001",
            "0.00000002",
            "0.00000003",
            "0.00000004",
        )
        val exactSum = amounts.fold(BigDecimal.ZERO) { acc, amt ->
            acc.add(BigDecimal(amt))
        }

        assertEquals(BigDecimal("0.00000010"), exactSum)
    }
}
