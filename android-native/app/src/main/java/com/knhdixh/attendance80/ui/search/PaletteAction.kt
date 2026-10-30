package com.knhdixh.attendance80.ui.search

import kotlinx.serialization.Serializable

/** A durable request for a future form or controller, or an immediately executable mutation. */
@Serializable
sealed interface PaletteAction {
    @Serializable data class OpenForm(val form: String, val input: String = "", val entityId: Long? = null) : PaletteAction
    @Serializable data class Focus(val command: String, val homeworkId: Long? = null, val task: String? = null) : PaletteAction
    @Serializable data class CopyFeed(val url: String) : PaletteAction
    @Serializable data class SessionSearch(val query: String) : PaletteAction
    @Serializable data class BulkAttendance(val ids: List<Long>) : PaletteAction
    @Serializable data class ToggleHomework(val id: Long, val done: Boolean) : PaletteAction
    @Serializable data class CheckHabit(val id: Long, val day: String, val count: Int) : PaletteAction
    @Serializable data class SyncCalendar(val id: Long) : PaletteAction
    @Serializable data class AddHabit(val input: String) : PaletteAction
    @Serializable data class AddCountdown(val input: String) : PaletteAction
    @Serializable data class CourseField(val id: Long, val field: String, val value: String) : PaletteAction
    @Serializable data class CourseGroups(val ids: List<Long>, val selected: Long?) : PaletteAction
}
