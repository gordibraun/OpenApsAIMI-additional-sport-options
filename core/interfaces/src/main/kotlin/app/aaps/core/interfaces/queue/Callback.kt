package app.aaps.core.interfaces.queue

import app.aaps.core.interfaces.pump.PumpEnactResult

abstract class Callback : Runnable {

    lateinit var result: PumpEnactResult
    fun result(result: PumpEnactResult): Callback {
        this.result = result
        return this
    }

    /** Revalidate immutable APS inputs after connecting to the pump, before issuing a dose command. */
    open fun validationErrorBeforeDelivery(): String? = null

    /** Carb persistence is a separate event from the pump's insulin delivery result. */
    open fun onCarbsStored(result: PumpEnactResult) {
        result(result).run()
    }
}
