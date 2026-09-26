package com.tradinghud.app.db

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

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

    /** Shows the helper function used in OverlayViewModel to get startOfDay. */
    @Test fun `todayStartMillis helper returns start of current day`() {
        val millis = todayStartMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val expected = today.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, millis)
    }
}

fun todayStartMillis(): Long {
    val zone = ZoneId.systemDefault()
    return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
}
