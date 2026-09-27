package com.tradinghud.app.util

import com.tradinghud.risk.RejectReason
import org.junit.Test
import kotlin.test.assertTrue

class RejectReasonTextTest {

    @Test
    fun `every RejectReason maps to a non-empty string`() {
        RejectReason.entries.forEach { reason ->
            val text = RejectReasonText.from(reason)
            assertTrue(text.isNotBlank(), "RejectReason.$reason mapped to blank string")
        }
    }

    @Test
    fun `DAILY_LOSS_LIMIT message mentions the 3 percent threshold`() {
        val text = RejectReasonText.from(RejectReason.DAILY_LOSS_LIMIT)
        assertTrue(text.contains("3%"), "Expected '3%' in daily loss limit message, got: $text")
    }

    @Test
    fun `REWARD_TOO_SMALL message mentions 1_5 ratio`() {
        val text = RejectReasonText.from(RejectReason.REWARD_TOO_SMALL)
        assertTrue(text.contains("1.5"), "Expected '1.5' in reward message, got: $text")
    }

    @Test
    fun `IMPLAUSIBLE_PRICE_DISTANCE message mentions 50 percent threshold`() {
        val text = RejectReasonText.from(RejectReason.IMPLAUSIBLE_PRICE_DISTANCE)
        assertTrue(text.contains("50%"), "Expected '50%' in implausible price message, got: $text")
    }
}
