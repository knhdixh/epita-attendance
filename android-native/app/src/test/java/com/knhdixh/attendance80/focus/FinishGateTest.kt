package com.knhdixh.attendance80.focus

import org.junit.Assert.*
import org.junit.Test

/** The quick-focus sheet must stay open while the permission dialog is, or its answer is lost with the activity. */
class FinishGateTest {
    @Test fun closesRightAwayWhenNothingWasAsked() {
        var closed = 0
        FinishGate { closed++ }.commandDone()
        assertEquals(1, closed)
    }
    @Test fun waitsForTheAnswerWhenThePermissionWasAsked() {
        var closed = 0
        val gate = FinishGate { closed++ }
        gate.asked()
        gate.commandDone()
        assertEquals("still open: the dialog is showing", 0, closed)
        gate.answered()
        assertEquals(1, closed)
    }
    @Test fun anAnswerBeforeTheCommandFinishesDoesNotCloseEarly() {
        var closed = 0
        val gate = FinishGate { closed++ }
        gate.asked()
        gate.answered() // the user answered fast; the command is still running
        assertEquals(0, closed)
        gate.commandDone()
        assertEquals(1, closed)
    }
}
