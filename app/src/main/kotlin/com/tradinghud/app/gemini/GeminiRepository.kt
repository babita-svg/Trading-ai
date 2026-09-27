package com.tradinghud.app.gemini

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.util.Base64

class GeminiRepository(private val api: GeminiApi = GeminiClient.api) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends the chart image and trading context to Gemini with bounded retry-with-backoff.
     * Returns [Result.success] with a parsed [TradeSignal], or
     * [Result.failure] with a descriptive exception on any error.
     */
    suspend fun analyze(
        imageBytes: ByteArray,
        capitalInr: String,
        marketType: String,
    ): Result<TradeSignal> = runCatching {
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)
        val prompt = buildPrompt(capitalInr, marketType)

        val request = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image)),
                        Part(text = prompt),
                    )
                )
            ),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseSchema = GeminiClient.responseSchema,
            ),
        )

        val maxAttempts = 3
        var attempt = 0
        var response: retrofit2.Response<GeminiResponse>? = null
        var lastException: Exception? = null

        while (attempt < maxAttempts) {
            try {
                response = api.interact(
                    model = GeminiClient.MODEL,
                    key = GeminiClient.apiKey,
                    request = request,
                )
                break
            } catch (e: Exception) {
                lastException = e
                attempt++
                if (attempt >= maxAttempts) {
                    throw e
                }
                delay(100L * attempt)
            }
        }

        val resp = response ?: throw (lastException ?: GeminiApiException("Network request failed after retries"))

        if (!resp.isSuccessful) {
            throw GeminiApiException(
                "HTTP ${resp.code()}: ${resp.errorBody()?.string() ?: "unknown error"}"
            )
        }

        val body = resp.body()
            ?: throw GeminiApiException("Empty response body")

        if (body.error != null) {
            throw GeminiApiException("Gemini error ${body.error.code}: ${body.error.message}")
        }

        val rawJson = body.candidates
            ?.firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?: throw GeminiParseException("No text content in response")

        val tradeSignal = json.decodeFromString<TradeSignal>(rawJson)

        try {
            BigDecimal(tradeSignal.entry_price)
            BigDecimal(tradeSignal.stop_loss)
            BigDecimal(tradeSignal.take_profit)
        } catch (e: Exception) {
            throw GeminiParseException("Invalid price format returned by model: ${e.message}")
        }

        tradeSignal
    }

    private fun buildPrompt(capitalInr: String, marketType: String): String = """
        You are a strict quantitative trade analyst. Analyze the chart in the image.

        Context:
        - Available capital: ₹$capitalInr
        - Market type: $marketType

        Return a JSON trade signal with:
        - signal: "BUY", "SELL", or "WAIT" (WAIT if the chart is unclear or no trade is valid)
        - market_type: must be exactly "$marketType"
        - entry_price: the suggested entry price as a decimal string with no scientific notation and no thousands separators ("0" if WAIT)
        - stop_loss: the structural stop loss price as a decimal string with no scientific notation and no thousands separators ("0" if WAIT)
        - take_profit: the logical take profit price as a decimal string with no scientific notation and no thousands separators ("0" if WAIT)
        - rationale: a brief (1–2 sentence) reason for the signal

        Rules:
        1. If signal is WAIT, set all prices to "0".
        2. For BUY, stop_loss must be below entry_price.
        3. For SELL, stop_loss must be above entry_price.
        4. Do not suggest a trade if the chart structure is ambiguous.
    """.trimIndent()
}

class GeminiApiException(message: String) : Exception(message)
class GeminiParseException(message: String) : Exception(message)
