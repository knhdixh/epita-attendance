package com.knhdixh.attendance80.navigation

import java.net.URI

/** Only the app's own navigation scheme is accepted here; authentication has its own callback. */
fun internalRoute(value: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme != "a80" || uri.userInfo != null || uri.port != -1 ||
        uri.query != null || uri.fragment != null) return null
    val host = uri.host ?: return null
    val path = uri.rawPath.orEmpty()
    return when {
        host in setOf("today", "focus", "add") && path.isEmpty() -> host
        host == "homework" && path in setOf("", "/new") -> host + path
        host in setOf("session", "course", "homework") && path.matches(Regex("/[1-9][0-9]*")) -> host + path
        else -> null
    }
}
