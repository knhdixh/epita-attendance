package com.knhdixh.attendance80.ui.homework

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import com.knhdixh.attendance80.net.HomeworkDto
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeworkState(val data: AppData, val now: Long, val zone: ZoneId,
    val buckets: Map<HomeworkBucket, List<HomeworkDto>>, val tracked: List<CourseDto>)

class HomeworkViewModel : ViewModel() {
    private val mutable = MutableStateFlow<HomeworkState?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0
    fun update(data: AppData?, now: Long, zone: ZoneId) {
        val version = ++generation
        if (data == null) { mutable.value = null; return }
        if (mutable.value?.let { it.data == data && it.now == now && it.zone == zone } == true) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                HomeworkState(data, now, zone, homeworkBuckets(data.homework, now, zone), data.courses.filter { it.tracked })
            }
            if (version == generation) mutable.value = result
        }
    }
}

fun homeworkSessions(events: List<EventDto>, courseId: Long?, now: Long): List<EventDto> =
    if (courseId == null) emptyList() else events.filter { it.course_id == courseId && it.start_at > now }.take(10)
