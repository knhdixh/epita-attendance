package com.knhdixh.attendance80.focus

import org.junit.Assert.*
import org.junit.Test

class CanAskNotificationsTest {
    @Test fun theDialogIsShownBeforeTheFirstAskAndAfterOneDenial() {
        assertTrue(canAskNotifications(missing = true, askedBefore = false, shouldShowRationale = false))
        assertTrue(canAskNotifications(missing = true, askedBefore = true, shouldShowRationale = true))
    }

    @Test fun afterTwoDenialsOnlyTheSystemSettingsCanHelp() {
        assertFalse(canAskNotifications(missing = true, askedBefore = true, shouldShowRationale = false))
    }

    @Test fun nothingToAskWhenThePermissionIsThere() {
        assertFalse(canAskNotifications(missing = false, askedBefore = false, shouldShowRationale = false))
    }
}
