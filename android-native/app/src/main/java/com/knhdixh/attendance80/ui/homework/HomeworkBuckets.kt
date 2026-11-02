package com.knhdixh.attendance80.ui.homework

import com.knhdixh.attendance80.net.HomeworkDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

enum class HomeworkBucket { Overdue, Today, Tomorrow, Week, Later, Done }

/** Mirrors homework-ui.js: completed items take precedence over their deadline. */
fun homeworkBucket(homework: HomeworkDto, now: Long, zone: ZoneId): HomeworkBucket {
    if (homework.done) return HomeworkBucket.Done
    if (homework.due_at != null && homework.due_at < now) return HomeworkBucket.Overdue
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val date = runCatching { LocalDate.parse(homework.due_date) }.getOrNull() ?: return HomeworkBucket.Later
    val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val dueStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    // JavaScript Math.round is floor(x + .5), including across DST transitions.
    val days = floor((dueStart - start) / 86_400_000.0 + .5).toLong()
    return when {
        days <= 0 -> HomeworkBucket.Today
        days == 1L -> HomeworkBucket.Tomorrow
        days < 7 -> HomeworkBucket.Week
        else -> HomeworkBucket.Later
    }
}

fun homeworkBuckets(homework: List<HomeworkDto>, now: Long, zone: ZoneId): Map<HomeworkBucket, List<HomeworkDto>> =
    HomeworkBucket.entries.associateWith { bucket ->
        val items = homework.filter { homeworkBucket(it, now, zone) == bucket }
        if (bucket == HomeworkBucket.Done) items.sortedByDescending { it.due_at } else items
    }
