package app.aaps.core.interfaces.pump

/**
 * A pump driver that can say, in the owner's words, what a reading of the pump brought.
 *
 * For the «Обновить с помпы» buttons: the reading itself is asked for through the command queue like any
 * other (`readStatus`); once the queue has run it, the driver says whether the pump was read, which boluses
 * were new to the records, and what insulin on board is with them counted. The screens that offer the button
 * do not know the driver; they only ask the active pump whether it is one of these.
 */
interface PumpReadReporter {

    /**
     * What the reading asked for at [askedAtEpochMs] brought, as lines for the screen, or why it did not
     * happen. Called when the queue reports the reading done, off the main thread.
     */
    fun readReport(askedAtEpochMs: Long): String
}
