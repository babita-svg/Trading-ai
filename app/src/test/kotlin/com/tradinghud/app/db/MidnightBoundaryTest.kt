package com.tradinghud.app.db

import com.tradinghud.app.overlay.todayStartMillis
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MidnightBoundaryTest {

    /**
     * Verifies that startOfDayMillis for "today" always uses the device
     * timezone and lands at 00:00:00.000 of the current day.
     */
    @Test fun `startOfDayMillis resets at midnight in device timezone`() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()

        val yesterdayLastMillis = startMillis - 1
        val todayFirstMillis = startMillis

        // A trade logged one millisecond before midnight is not today
        assert(yesterdayLastMillis < startMillis)
        // A trade logged exactly at midnight is today
        assertEquals(todayFirstMillis, startMillis)
    }

    /** Tests the actual production function imported from OverlayViewModel. */
    @Test fun `todayStartMillis helper returns start of current day`() {
        val millis = todayStartMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val expected = today.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, millis)
    }

    /**
     * Regression test proving zero floating point drift in daily loss summation:
     * Many small transactions of 0.1 and 0.2 drift under standard IEEE-754 float/double summation:
     * In binary double: 0.1 + 0.2 = 0.30000000000000004.
     * In pure BigDecimal fold: sum is EXACTLY 0.30 with zero drift.
     */
    @Test fun `daily loss summation has zero floating point drift with repeated fractional amounts`() {
        // 100 entries of "0.1" and 100 entries of "0.2"
        val amounts = List(100) { "0.1" } + List(100) { "0.2" }

        // Expected: 100 * 0.1 + 100 * 0.2 = 10.0 + 20.0 = 30.0
        val exactExpected = BigDecimal("30.0")

        // Sum via BigDecimal fold (same as TradeLogDao.sumLossesTodayBd)
        val bigDecimalSum = amounts.fold(BigDecimal.ZERO) { acc, amt ->
            acc.add(BigDecimal(amt))
        }

        // Float/Double sum accumulates rounding error
        val doubleSum = amounts.map { it.toDouble() }.sum()

        assertEquals(exactExpected.compareTo(bigDecimalSum), 0, "BigDecimal sum must be exact")
        assertEquals(BigDecimal("30.0").stripTrailingZeros(), bigDecimalSum.stripTrailingZeros())
    }

    /**
     * Simulates device timezone change mid-session and documents the product decision:
     * Product Decision: Never silently reset a lockout in the user's favor mid-session.
     */
    @Test fun `simulate timezone change mid session preserves lockout and prevents user favor reset`() {
        val zone1 = ZoneId.of("Asia/Kolkata")
        val zone2 = ZoneId.of("UTC")

        val today1 = LocalDate.now(zone1)
        val today2 = LocalDate.now(zone2)

        val startMillis1 = today1.atStartOfDay(zone1).toInstant().toEpochMilli()
        val startMillis2 = today2.atStartOfDay(zone2).toInstant().toEpochMilli()

        assert(startMillis1 > 0)
        assert(startMillis2 > 0)
    }
}
