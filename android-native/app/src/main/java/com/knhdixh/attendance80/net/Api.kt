package com.knhdixh.attendance80.net

import okhttp3.ResponseBody
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Headers
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface Api {
    @GET("auth/config")
    suspend fun authConfig(): AuthConfigResponse

    @POST("auth/app/token")
    suspend fun exchangeToken(@Body req: TokenExchangeRequest): TokenResponse

    @POST("auth/passkey/register/options")
    // /auth/* gets no bearer from the interceptor: pass it to add a passkey to the signed-in account.
    suspend fun passkeyRegisterOptions(@Body req: PasskeyRegisterOptionsRequest, @Header("Authorization") authorization: String? = null): JsonElement

    @Headers("X-A80-App: 1")
    @POST("auth/passkey/register/verify")
    suspend fun passkeyRegisterVerify(@Body req: PasskeyRegisterVerifyRequest, @Header("Authorization") authorization: String? = null): PasskeyRegisterVerifyResponse

    @POST("auth/passkey/login/options")
    suspend fun passkeyLoginOptions(): JsonElement

    @Headers("X-A80-App: 1")
    @POST("auth/passkey/login/verify")
    suspend fun passkeyLoginVerify(@Body req: PasskeyLoginVerifyRequest): PasskeyLoginVerifyResponse

    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") authorization: String? = null): OkResponse

    @GET("api/me")
    suspend fun me(): MeResponse

    @GET("api/data")
    suspend fun data(): AppData

    /** `tasks=1`: tasks without a date too (plan section 4). */
    @GET("api/data")
    suspend fun rawData(@Query("tasks") tasks: Int = 1): ResponseBody

    // The outbox sends what it queued: any method and path, with the body the change was made with.
    @GET("api/homework/{id}/comments") suspend fun comments(@Path("id") id: Long): CommentsResponse
    @GET("api/completions") suspend fun completions(@Query("since") since: Long): CompletionsResponse
    @POST suspend fun sendPost(@retrofit2.http.Url path: String, @Body body: JsonObject): ResponseBody
    @PATCH suspend fun sendPatch(@retrofit2.http.Url path: String, @Body body: JsonObject): ResponseBody
    @PUT suspend fun sendPut(@retrofit2.http.Url path: String, @Body body: JsonObject): ResponseBody
    @HTTP(method = "DELETE") suspend fun sendDelete(@retrofit2.http.Url path: String): ResponseBody

    @GET("api/focus")
    suspend fun listFocus(@Query("since") since: Long): FocusListResponse

    @POST("api/focus")
    suspend fun logFocus(@Body session: FocusLogRequest): FocusSessionDto

    @DELETE("api/focus/{id}")
    suspend fun deleteFocus(@Path("id") id: Long): OkResponse

    @PUT("api/focus/settings")
    suspend fun saveFocusSettings(@Body settings: JsonObject): FocusSettingsResponse

    @PATCH("api/attendance/{id}")
    suspend fun updateAttendance(@Path("id") id: Long, @Body req: JsonObject): OkResponse

    @POST("api/attendance/bulk")
    suspend fun bulkAttendance(@Body req: JsonObject): AttendanceBulkResponse

    @PATCH("api/courses/{id}")
    suspend fun updateCourse(@Path("id") id: Long, @Body req: JsonObject): OkResponse

    @POST("api/courses/tracked")
    suspend fun updateCoursesTracked(@Body req: CourseTrackedBulkRequest): OkResponse

    @POST("api/catchups")
    suspend fun createCatchup(@Body req: CatchupRequest): HomeworkDto

    @POST("api/homework")
    suspend fun createHomework(@Body req: JsonObject): HomeworkDto

    @PATCH("api/homework/{id}")
    suspend fun updateHomework(@Path("id") id: Long, @Body req: JsonObject): HomeworkDto

    @DELETE("api/homework/{id}")
    suspend fun deleteHomework(@Path("id") id: Long): OkResponse

    @POST("api/homework/plan")
    suspend fun planHomework(@Body req: JsonObject): HomeworkPlanResponse

    @POST("api/habits")
    suspend fun createHabit(@Body req: JsonObject): HabitDto

    @PATCH("api/habits/{id}")
    suspend fun updateHabit(@Path("id") id: Long, @Body req: JsonObject): HabitDto

    @DELETE("api/habits/{id}")
    suspend fun deleteHabit(@Path("id") id: Long): OkResponse

    @PUT("api/habits/{id}/log")
    suspend fun logHabit(@Path("id") id: Long, @Body req: HabitLogRequest): HabitLogDto

    @POST("api/habits/order")
    suspend fun orderHabits(@Body req: HabitOrderRequest): OkResponse

    @POST("api/countdowns")
    suspend fun createCountdown(@Body req: JsonObject): CountdownDto

    @PATCH("api/countdowns/{id}")
    suspend fun updateCountdown(@Path("id") id: Long, @Body req: JsonObject): CountdownDto

    @DELETE("api/countdowns/{id}")
    suspend fun deleteCountdown(@Path("id") id: Long): OkResponse

    @PUT("api/notify/settings")
    suspend fun saveNotifySettings(@Body req: JsonObject): NotifySettingsResponse

    @POST("api/breaks")
    suspend fun createBreak(@Body req: BreakCreateRequest): BreakCreateResponse

    @DELETE("api/breaks/{id}")
    suspend fun deleteBreak(@Path("id") id: Long): BreakDeleteResponse

    @POST("api/calendars")
    suspend fun addCalendar(@Body req: CalendarAddRequest): CalendarImportSummary

    @PATCH("api/calendars/{id}")
    suspend fun updateCalendar(@Path("id") id: Long, @Body req: JsonObject): CalendarImportSummary

    @POST("api/calendars/{id}/sync")
    suspend fun syncCalendar(@Path("id") id: Long): CalendarImportSummary

    @POST("api/calendars/{id}/file")
    suspend fun updateCalendarFile(@Path("id") id: Long, @Body req: CalendarFileRequest): CalendarImportSummary

    @DELETE("api/calendars/{id}")
    suspend fun deleteCalendar(@Path("id") id: Long): CalendarDeleteResponse

    @POST("api/gcal/resync")
    suspend fun resyncGcal(): GcalResyncResponse

    @POST("api/gcal/disconnect")
    suspend fun disconnectGcal(): OkResponse

    @POST("api/feed/rotate")
    suspend fun rotateFeed(): OkResponse

    @POST("api/app/web-link")
    suspend fun createWebLink(@Body req: WebLinkRequest): WebLinkResponse

    @GET("api/tokens")
    suspend fun listTokens(): TokenListResponse

    @POST("api/tokens")
    suspend fun createToken(@Body req: TokenCreateRequest): PersonalTokenCreatedDto

    @DELETE("api/tokens/{id}")
    suspend fun deleteToken(@Path("id") id: Long): OkResponse

    @DELETE("api/passkeys/{id}")
    suspend fun deletePasskey(@Path("id") id: String): OkResponse

    @PATCH("api/settings")
    suspend fun updateSettings(@Body req: SettingsUpdateRequest): OkResponse

    @DELETE("api/data")
    suspend fun deleteData(): OkResponse

    @HTTP(method = "DELETE", path = "api/account", hasBody = true)
    suspend fun deleteAccount(@Body req: AccountDeleteRequest): OkResponse
}
