package com.knhdixh.attendance80.net

import java.io.IOException

class ApiError(
    val status: Int,
    override val message: String,
) : IOException("HTTP $status: $message")
