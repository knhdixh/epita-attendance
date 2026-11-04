package com.knhdixh.attendance80.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode

/** On a phone only Inbox, Today, Upcoming, Search and Browse are in the bottom bar: everything else is a row in Browse. */
fun ComposeContentTestRule.openFromBrowse(route: String) {
    onNodeWithTag("nav-browse").performClick()
    onNodeWithTag("browse-list").performScrollToNode(hasTestTag("browse-$route"))
    onNodeWithTag("browse-$route").performClick()
}
