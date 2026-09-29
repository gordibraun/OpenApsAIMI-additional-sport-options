package app.aaps.ui.dialogs

import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventProfileStoreChanged
import app.aaps.core.interfaces.rx.events.EventTreatmentChange
import io.reactivex.rxjava3.core.Observable

// Autosens finishes before the loop publishes its decision and acknowledges pending input.
internal fun wizardInputUpdates(rxBus: RxBus): Observable<Event> = Observable.mergeArray(
    rxBus.toObservable(EventAutosensCalculationFinished::class.java),
    rxBus.toObservable(EventLoopUpdateGui::class.java),
    rxBus.toObservable(EventInitializationChanged::class.java),
    rxBus.toObservable(EventProfileStoreChanged::class.java),
    rxBus.toObservable(EventEffectiveProfileSwitchChanged::class.java),
    rxBus.toObservable(EventTreatmentChange::class.java)
)
