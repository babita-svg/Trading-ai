package com.tradinghud.app.gemini

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface GeminiApi {
    /**
     * Targets the Interactions API for stateless multimodal requests.
     * The model is appended via [model]; the API key via [key].
     */
    @POST("v1beta/models/{model}:generateContent")
    suspend fun interact(
        @retrofit2.http.Path("model") model: String,
        @Query("key") key: String,
        @Body request: GeminiRequest,
    ): Response<GeminiResponse>
}
