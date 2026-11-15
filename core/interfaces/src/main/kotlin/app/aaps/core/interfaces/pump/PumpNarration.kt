package app.aaps.core.interfaces.pump

import app.aaps.core.data.model.TE

/**
 * A pump driver that files notes of its own among the therapy events and can tell which of them only narrate.
 *
 * With the watch driving the pump, the watch tells the records what it did or noticed - «Часы без телефона:
 * сахар 122, …», «Часы: Инсулин действует хуже…» - a dozen notes a day, arriving in a burst when the watch
 * comes back in reach. The bolus wizard compares the records at confirmation with those the dose was
 * calculated from and refuses the dose when anything was filed in between; a note that only narrates is no
 * reason for that. The wizard does not know the driver; it asks the active pump whether it is one of these.
 */
interface PumpNarration {

    /**
     * True when [event] is this driver's own note and says nothing about insulin given or tried. A note that
     * speaks of a dose - one the owner gave on the watch, a microbolus of the watch's own, an unclear outcome -
     * is not narration: the dose on the screen may have been calculated without it.
     */
    fun onlyNarrates(event: TE): Boolean
}
