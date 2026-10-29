package com.knhdixh.attendance80.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response

class ErrorInterceptor(private val json: Json) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            val message = try {
                val element = json.parseToJsonElement(body).jsonObject
                element["error"]?.jsonPrimitive?.content ?: if (body.isNotBlank()) body else "Request failed (${response.code})"
            } catch (_: Exception) {
                if (body.isNotBlank()) body else "Request failed (${response.code})"
            }
            throw ApiError(response.code, message)
        }
        return response
    }
}
