package app.aaps.ui.dialogs

import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventProfileStoreChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventTreatmentChange
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import org.junit.jupiter.api.Test

class WizardInputUpdatesTest {
    private val bus = object : RxBus {
        private val events = PublishSubject.create<Event>()
        override fun send(event: Event) = events.onNext(event)
        override fun <T : Any> toObservable(eventType: Class<T>): Observable<T> = events.ofType(eventType)
    }

    @Test fun refreshesAgainWhenLoopPublishesAfterAutosens() {
        val observer = wizardInputUpdates(bus).test()
        val autosens = EventAutosensCalculationFinished(null)
        val published = EventLoopUpdateGui()
        bus.send(autosens)
        bus.send(published)
        observer.assertValues(autosens, published).assertNoErrors()
    }

    @Test fun refreshesWhenStartupDataAndTreatmentsBecomeAvailable() {
        val observer = wizardInputUpdates(bus).test()
        val events = listOf(EventInitializationChanged(), EventProfileStoreChanged(),
            EventEffectiveProfileSwitchChanged(123L), EventTreatmentChange())
        events.forEach(bus::send)
        observer.assertValueSequence(events).assertNoErrors()
    }

    @Test fun overviewRefreshDoesNotCauseRecursiveWizardCalculations() {
        val observer = wizardInputUpdates(bus).test()
        bus.send(EventRefreshOverview("Wizard forecast carbs"))
        observer.assertNoValues().assertNoErrors()
    }

    @Test fun viewDisposalStopsUpdatesAndNewViewCanSubscribeAgain() {
        val oldView = wizardInputUpdates(bus).test()
        oldView.dispose()
        val newView = wizardInputUpdates(bus).test()
        val event = EventInitializationChanged()
        bus.send(event)
        oldView.assertNoValues()
        newView.assertValue(event).assertNoErrors()
    }
}
