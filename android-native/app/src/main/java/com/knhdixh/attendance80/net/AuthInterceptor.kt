package com.knhdixh.attendance80.net

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: (String?) -> Unit,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isApi = request.url.encodedPath.startsWith("/api/")
        val hadBearerBefore = request.header("Authorization")?.startsWith("Bearer ") == true
        val token = if (isApi) tokenProvider() else null
        val newRequest = if (isApi && !token.isNullOrBlank()) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }
        val carriedBearer = hadBearerBefore || (!token.isNullOrBlank())

        val response: Response
        try {
            response = chain.proceed(newRequest)
        } catch (e: ApiError) {
            if (isApi && e.status == 401 && carriedBearer) {
                onUnauthorized(newRequest.header("Authorization")?.removePrefix("Bearer "))
            }
            throw e
        }

        if (isApi && response.code == 401 && carriedBearer) {
            onUnauthorized(newRequest.header("Authorization")?.removePrefix("Bearer "))
        }
        return response
    }
}
