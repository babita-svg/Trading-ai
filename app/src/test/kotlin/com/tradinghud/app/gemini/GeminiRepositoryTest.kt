package com.tradinghud.app.gemini

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun fakeApi(responseJson: String, httpCode: Int = 200): GeminiApi {
        return object : GeminiApi {
            override suspend fun interact(model: String, key: String, request: GeminiRequest): Response<GeminiResponse> {
                if (httpCode != 200) return Response.error(httpCode, "error".toResponseBody(null))
                val body = GeminiResponse(
                    candidates = listOf(
                        Candidate(content = Content(parts = listOf(Part(text = responseJson))))
                    )
                )
                return Response.success(body)
            }
        }
    }

    @Test
    fun `valid BUY signal parses correctly`() = runTest {
        val responseJson = """
            {"signal":"BUY","market_type":"CRYPTO","entry_price":100.0,
             "stop_loss":95.0,"take_profit":110.0,"rationale":"Strong breakout"}
        """.trimIndent()
        val repo = GeminiRepository(fakeApi(responseJson))
        val result = repo.analyze(ByteArray(0), "100000", "CRYPTO")
        assertTrue(result.isSuccess)
        assertEquals("BUY", result.getOrThrow().signal)
    }

    @Test
    fun `WAIT signal parses correctly`() = runTest {
        val responseJson = """
            {"signal":"WAIT","market_type":"INDIAN_FNO","entry_price":0.0,
             "stop_loss":0.0,"take_profit":0.0,"rationale":"Ranging"}
        """.trimIndent()
        val repo = GeminiRepository(fakeApi(responseJson))
        val result = repo.analyze(ByteArray(0), "100000", "INDIAN_FNO")
        assertTrue(result.isSuccess)
        assertEquals("WAIT", result.getOrThrow().signal)
    }

    @Test
    fun `HTTP 500 returns failure`() = runTest {
        val repo = GeminiRepository(fakeApi("{}", httpCode = 500))
        val result = repo.analyze(ByteArray(0), "100000", "CRYPTO")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }
}
