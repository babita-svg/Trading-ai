package com.tradinghud.app.gemini

import com.tradinghud.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"
    private const val TIMEOUT_SECONDS = 30L

    val api: GeminiApi by lazy {
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiApi::class.java)
    }

    val apiKey: String get() = BuildConfig.GEMINI_API_KEY
    const val MODEL = "gemini-3.8-flash"

    val responseSchema = ResponseSchema(
        properties = mapOf(
            "signal"       to SchemaProperty("STRING", "BUY, SELL, or WAIT"),
            "market_type"  to SchemaProperty("STRING", "INDIAN_EQUITY, INDIAN_FNO, or CRYPTO"),
            "entry_price"  to SchemaProperty("NUMBER", "Suggested entry price"),
            "stop_loss"    to SchemaProperty("NUMBER", "Structural stop loss price"),
            "take_profit"  to SchemaProperty("NUMBER", "Logical take profit price"),
            "rationale"    to SchemaProperty("STRING", "Brief reason for the signal"),
        ),
        required = listOf("signal", "market_type", "entry_price", "stop_loss", "take_profit", "rationale"),
    )
}
