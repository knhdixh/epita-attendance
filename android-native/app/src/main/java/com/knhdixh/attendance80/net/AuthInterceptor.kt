package com.knhdixh.attendance80.net

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isApi = request.url.encodedPath.startsWith("/api/")
        val newRequest = if (isApi) {
            val token = tokenProvider()
            if (!token.isNullOrBlank()) {
                request.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                request
            }
        } else {
            request
        }

        val response: Response
        try {
            response = chain.proceed(newRequest)
        } catch (e: ApiError) {
            if (isApi && e.status == 401) {
                onUnauthorized()
            }
            throw e
        }

        if (isApi && response.code == 401) {
            onUnauthorized()
        }
        return response
    }
}
