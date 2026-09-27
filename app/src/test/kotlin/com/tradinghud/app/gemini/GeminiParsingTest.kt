package com.tradinghud.app.gemini

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeminiParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `test valid trade signal json parsing`() {
        val rawJson = """
            {
              "signal": "BUY",
              "market_type": "INDIAN_EQUITY",
              "entry_price": "2450.50",
              "stop_loss": "2400.00",
              "take_profit": "2550.00",
              "rationale": "Strong breakout above resistance"
            }
        """.trimIndent()

        val signal = json.decodeFromString<TradeSignal>(rawJson)
        assertEquals("BUY", signal.signal)
        assertEquals("INDIAN_EQUITY", signal.market_type)
        assertEquals("2450.50", signal.entry_price)
        assertEquals("2400.00", signal.stop_loss)
        assertEquals("2550.00", signal.take_profit)
        assertEquals("Strong breakout above resistance", signal.rationale)
    }

    @Test
    fun `test WAIT signal json parsing`() {
        val rawJson = """
            {
              "signal": "WAIT",
              "market_type": "INDIAN_FNO",
              "entry_price": "0.0",
              "stop_loss": "0.0",
              "take_profit": "0.0",
              "rationale": "High volatility, wait for consolidation"
            }
        """.trimIndent()

        val signal = json.decodeFromString<TradeSignal>(rawJson)
        assertEquals("WAIT", signal.signal)
        assertEquals("INDIAN_FNO", signal.market_type)
        assertEquals("0.0", signal.entry_price)
    }

    @Test
    fun `test parsing failure on missing required fields`() {
        val rawJson = """
            {
              "signal": "BUY",
              "entry_price": "100.0"
            }
        """.trimIndent()

        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString<TradeSignal>(rawJson)
        }
    }
}
