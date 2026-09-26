package com.tradinghud.app.gemini

import android.util.Base64
import kotlinx.serialization.json.Json

class GeminiRepository(private val api: GeminiApi = GeminiClient.api) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends the chart image and trading context to Gemini.
     * Returns [Result.success] with a parsed [TradeSignal], or
     * [Result.failure] with a descriptive exception on any error.
     *
     * @param imageBytes  JPEG bytes from ImageProcessor (already scaled + compressed)
     * @param capitalInr  Capital string the user typed (for the prompt)
     * @param marketType  One of "INDIAN_EQUITY", "INDIAN_FNO", "CRYPTO"
     */
    suspend fun analyze(
        imageBytes: ByteArray,
        capitalInr: String,
        marketType: String,
    ): Result<TradeSignal> = runCatching {
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

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

        val response = api.interact(
            model = GeminiClient.MODEL,
            key = GeminiClient.apiKey,
            request = request,
        )

        if (!response.isSuccessful) {
            throw GeminiApiException(
                "HTTP ${response.code()}: ${response.errorBody()?.string() ?: "unknown error"}"
            )
        }

        val body = response.body()
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

        json.decodeFromString<TradeSignal>(rawJson)
    }

    private fun buildPrompt(capitalInr: String, marketType: String): String = """
        You are a strict quantitative trade analyst. Analyze the chart in the image.

        Context:
        - Available capital: ₹$capitalInr
        - Market type: $marketType

        Return a JSON trade signal with:
        - signal: "BUY", "SELL", or "WAIT" (WAIT if the chart is unclear or no trade is valid)
        - market_type: must be exactly "$marketType"
        - entry_price: the suggested entry price (0.0 if WAIT)
        - stop_loss: the structural stop loss price (0.0 if WAIT)
        - take_profit: the logical take profit price (0.0 if WAIT)
        - rationale: a brief (1–2 sentence) reason for the signal

        Rules:
        1. If signal is WAIT, set all prices to 0.0.
        2. For BUY, stop_loss must be below entry_price.
        3. For SELL, stop_loss must be above entry_price.
        4. Do not suggest a trade if the chart structure is ambiguous.
    """.trimIndent()
}

class GeminiApiException(message: String) : Exception(message)
class GeminiParseException(message: String) : Exception(message)
