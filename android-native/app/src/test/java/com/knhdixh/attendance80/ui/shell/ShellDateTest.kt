package com.knhdixh.attendance80.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ShellDateTest {
    @Test fun dateOnlyUsesLocaleWithoutShiftingDayInAnotherTimeZone() {
        val oldLocale = Locale.getDefault()
        val oldZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            assertEquals("1 nov. 2026", dateOnly("2026-11-01"))
            assertEquals("31 oct. 2026, 14:30", date(java.time.Instant.parse("2026-11-01T00:30:00Z").toEpochMilli(), java.time.ZoneId.of("Pacific/Honolulu")))
        } finally {
            Locale.setDefault(oldLocale)
            TimeZone.setDefault(oldZone)
        }
    }
}
