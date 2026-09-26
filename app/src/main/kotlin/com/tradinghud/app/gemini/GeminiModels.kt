package com.tradinghud.app.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The exact JSON shape Gemini must return, enforced via responseSchema.
 * Every field is non-null. A missing field is a parse error, never a default.
 */
@Serializable
data class TradeSignal(
    val signal: String,           // "BUY" | "SELL" | "WAIT"
    val market_type: String,      // "INDIAN_EQUITY" | "INDIAN_FNO" | "CRYPTO"
    val entry_price: Double,
    val stop_loss: Double,
    val take_profit: Double,
    val rationale: String,
)

// ── Request models ────────────────────────────────────────────────────────────

@Serializable
data class GeminiRequest(
    val contents: List<Content>,
    @SerialName("generation_config") val generationConfig: GenerationConfig,
)

@Serializable
data class Content(
    val parts: List<Part>,
)

@Serializable
data class Part(
    val text: String? = null,
    @SerialName("inline_data") val inlineData: InlineData? = null,
)

@Serializable
data class InlineData(
    @SerialName("mime_type") val mimeType: String,
    val data: String,              // base64-encoded image bytes
)

@Serializable
data class GenerationConfig(
    @SerialName("response_mime_type") val responseMimeType: String = "application/json",
    @SerialName("response_schema") val responseSchema: ResponseSchema,
)

@Serializable
data class ResponseSchema(
    val type: String = "OBJECT",
    val properties: Map<String, SchemaProperty>,
    val required: List<String>,
)

@Serializable
data class SchemaProperty(
    val type: String,
    val description: String = "",
)

// ── Response wrapper ──────────────────────────────────────────────────────────

@Serializable
data class GeminiResponse(
    val candidates: List<Candidate>? = null,
    val error: GeminiError? = null,
)

@Serializable
data class Candidate(
    val content: Content? = null,
)

@Serializable
data class GeminiError(
    val code: Int,
    val message: String,
)
