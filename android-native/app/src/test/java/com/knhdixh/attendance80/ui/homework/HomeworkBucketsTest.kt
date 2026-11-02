package com.knhdixh.attendance80.ui.homework

import com.knhdixh.attendance80.net.HomeworkDto
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeworkBucketsTest {
    private val zone = ZoneId.of("Europe/Paris")

    @Test fun bucketBoundariesAndDoneOrderAcrossBothDstTransitions() {
        for (date in listOf(LocalDate.parse("2026-03-28"), LocalDate.parse("2026-10-24"))) {
            val now = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            fun item(id: Long, day: Long, due: Long, done: Boolean = false) = HomeworkDto(
                id = id, title = "$id", due_date = date.plusDays(day).toString(), due_at = due, done = done,
            )
            val rows = listOf(
                item(1, 0, now - 1), item(2, 0, now), item(3, 1, now + 1),
                item(4, 6, now + 1), item(5, 7, now + 1),
                item(6, 0, now - 2, true), item(7, 0, now - 1, true),
            )
            val buckets = homeworkBuckets(rows, now, zone)
            assertEquals(listOf(1L), buckets.getValue(HomeworkBucket.Overdue).map { it.id })
            assertEquals(listOf(2L), buckets.getValue(HomeworkBucket.Today).map { it.id })
            assertEquals(listOf(3L), buckets.getValue(HomeworkBucket.Tomorrow).map { it.id })
            assertEquals(listOf(4L), buckets.getValue(HomeworkBucket.Week).map { it.id })
            assertEquals(listOf(5L), buckets.getValue(HomeworkBucket.Later).map { it.id })
            assertEquals(listOf(7L, 6L), buckets.getValue(HomeworkBucket.Done).map { it.id })
        }
    }
}
