package info.nightscout.comboctl.base

/**
 * Process-wide choices for running this driver on a device other than the phone it was written
 * for. A process drives one pump over one Bluetooth link, so one set of switches per process is
 * the right granularity.
 *
 * Everything here is off by default, and with everything off the driver executes exactly the
 * code it had before these switches existed: each variant is a separate branch that is never
 * entered. The phone's direct mode relies on that and never sets any of them. The controller on
 * the watch sets them before it connects.
 *
 * A note on how these came about, because the first explanation was wrong. On a Wear OS watch
 * the driver could not walk a TBR percentage from 0 to 100: presses were sent late and held too
 * long, and every reading of the screen was staler than the last (0.9 s growing to 4.8 s within
 * one connection). That looked like a slow Bluetooth link or a slow pump, and
 * [confirmedStepPacing] was written to cope with one. Profiling on the watch showed neither was
 * slow. Two thirds of a CPU core went into recognising display frames inside the packet receive
 * loop, which made the loop fall behind the pump ([lazyDisplayFrameParsing] fixes that), and
 * most of the rest went into a pump state store that encrypted and synced the transmit nonce on
 * every packet (fixed in that store, outside this library). With both removed, a press is
 * applied in a steady ~0.85 s and either pacing completes every walk that was tried.
 */
object DriverProfile {

    /**
     * A more defensive way of walking a quantity on a setting screen and of confirming it.
     *
     * - A button is only held when the target is a limit of the pump (a TBR of 0 %), where
     *   running past it is impossible. Every other distance is covered with single presses, each
     *   confirmed on screen before the next.
     * - The press that confirms the edit is repeated until the pump leaves the setting screen.
     *   In the standard pacing it is the one press with no retry behind it, and a lost one makes
     *   the pump discard the whole edit.
     * - Waiting for a screen is bounded as a whole and tolerates the gaps between frames, and a
     *   walk stops the moment a foreign screen shows up.
     *
     * Both pacings were run through the same ten commands on pump 10392647 from the watch (stops,
     * both kinds of cancel, 0 -> 200 %, 150 -> 50 %, a bolus) and both completed all ten in the
     * same time, so this is a choice of caution rather than a necessity.
     */
    @Volatile
    var confirmedStepPacing: Boolean = false

    /**
     * Parse a display frame when it is asked for, instead of when it arrives.
     *
     * Recognising what a frame shows means searching it for every glyph, which is cheap on a
     * phone and costly on a watch. A setting screen blinks, so the pump sends frames
     * continuously while one is open, and by default each is parsed inside the packet receive
     * loop. On the watch that loop then cannot keep up with the pump.
     *
     * With this on, the receive loop only stores the newest raw frame and the caller that wants
     * a screen pays for parsing it. Frames nobody asked for are dropped unparsed, which is what
     * already happened to them after parsing.
     */
    @Volatile
    var lazyDisplayFrameParsing: Boolean = false
}
