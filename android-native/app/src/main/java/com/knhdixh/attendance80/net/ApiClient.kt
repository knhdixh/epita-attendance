package com.knhdixh.attendance80.net

import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object ApiClient {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    fun createOkHttpClient(
        tokenProvider: () -> String?,
        onUnauthorized: () -> Unit,
        cookieJar: CookieJar = InMemoryCookieJar(),
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(AuthInterceptor(tokenProvider, onUnauthorized))
            .addInterceptor(ErrorInterceptor(json))
            .build()
    }

    fun createRetrofit(
        baseUrl: String,
        okHttpClient: OkHttpClient,
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    fun createApi(
        baseUrl: String,
        tokenProvider: () -> String?,
        onUnauthorized: () -> Unit,
        cookieJar: CookieJar = InMemoryCookieJar(),
    ): Api {
        val client = createOkHttpClient(tokenProvider, onUnauthorized, cookieJar)
        val retrofit = createRetrofit(baseUrl, client)
        return retrofit.create(Api::class.java)
    }
}
