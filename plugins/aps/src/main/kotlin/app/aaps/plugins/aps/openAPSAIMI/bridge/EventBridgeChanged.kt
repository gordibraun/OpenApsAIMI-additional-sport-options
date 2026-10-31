package app.aaps.plugins.aps.openAPSAIMI.bridge

import app.aaps.core.interfaces.rx.events.Event

/** Something in the bridge's threads changed (sent, answered, failed); screens redraw. */
class EventBridgeChanged : Event()
