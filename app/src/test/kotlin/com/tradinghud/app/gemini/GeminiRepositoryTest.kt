package com.tradinghud.app.gemini

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            {"signal":"BUY","market_type":"CRYPTO","entry_price":"100.0",
             "stop_loss":"95.0","take_profit":"110.0","rationale":"Strong breakout"}
        """.trimIndent()
        val repo = GeminiRepository(fakeApi(responseJson))
        val result = repo.analyze(ByteArray(0), "100000", "CRYPTO")
        assertTrue(result.isSuccess)
        assertEquals("BUY", result.getOrThrow().signal)
    }

    @Test
    fun `WAIT signal parses correctly`() = runTest {
        val responseJson = """
            {"signal":"WAIT","market_type":"INDIAN_FNO","entry_price":"0.0",
             "stop_loss":"0.0","take_profit":"0.0","rationale":"Ranging"}
        """.trimIndent()
        val repo = GeminiRepository(fakeApi(responseJson))
        val result = repo.analyze(ByteArray(0), "100000", "INDIAN_FNO")
        assertTrue(result.isSuccess)
        assertEquals("WAIT", result.getOrThrow().signal)
    }

    @Test
    fun `HTTP 500 returns failure after retries exhausted`() = runTest {
        val repo = GeminiRepository(fakeApi("{}", httpCode = 500))
        val result = repo.analyze(ByteArray(0), "100000", "CRYPTO")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
    }

    @Test
    fun `malformed price string fails with GeminiParseException`() = runTest {
        val responseJson = """
            {"signal":"BUY","market_type":"CRYPTO","entry_price":"invalid_number",
             "stop_loss":"95.0","take_profit":"110.0","rationale":"Error"}
        """.trimIndent()
        val repo = GeminiRepository(fakeApi(responseJson))
        val result = repo.analyze(ByteArray(0), "100000", "CRYPTO")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GeminiParseException)
    }

    @Test
    fun `transient failure recovers on retry`() = runTest {
        val responseJson = """
            {"signal":"BUY","market_type":"CRYPTO","entry_price":"0.00001234",
             "stop_loss":"0.00001150","take_profit":"0.00001400","rationale":"Recovered"}
        """.trimIndent()

        val callCount = AtomicInteger(0)
        val api = object : GeminiApi {
            override suspend fun interact(model: String, key: String, request: GeminiRequest): Response<GeminiResponse> {
                return if (callCount.incrementAndGet() == 1) {
                    throw IOException("Transient network timeout")
                } else {
                    val body = GeminiResponse(
                        candidates = listOf(
                            Candidate(content = Content(parts = listOf(Part(text = responseJson))))
                        )
                    )
                    Response.success(body)
                }
            }
        }

        val repo = GeminiRepository(api)
        val result = repo.analyze(ByteArray(0), "100000", "CRYPTO")
        assertTrue(result.isSuccess)
        assertEquals("0.00001234", result.getOrThrow().entry_price)
        assertEquals(2, callCount.get())
    }
}
