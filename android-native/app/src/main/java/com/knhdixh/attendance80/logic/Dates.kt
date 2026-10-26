package com.knhdixh.attendance80.logic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

object Dates {
    fun formatTime(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zoneId)
        return formatter.format(Instant.ofEpochMilli(epochMillis))
    }

    fun formatDate(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zoneId)
        return formatter.format(Instant.ofEpochMilli(epochMillis))
    }

    fun formatShortDate(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val formatter = DateTimeFormatter.ofPattern("EEE, d MMM", locale).withZone(zoneId)
        return formatter.format(Instant.ofEpochMilli(epochMillis))
    }

    fun formatDurationMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    fun formatDurationSeconds(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(Locale.ROOT, m, s)
    }

    fun formatSkipsLeft(remainingDisplay: Double, unit: String): String {
        return if (unit == "hours") {
            val h = if (remainingDisplay % 1.0 == 0.0) {
                remainingDisplay.toInt().toString()
            } else {
                remainingDisplay.toString()
            }
            if (remainingDisplay <= 0.0) "no hours left" else "$h h left"
        } else {
            val count = remainingDisplay.toInt()
            when {
                count <= 0 -> "no skips left"
                count == 1 -> "1 skip left"
                else -> "$count skips left"
            }
        }
    }
}
