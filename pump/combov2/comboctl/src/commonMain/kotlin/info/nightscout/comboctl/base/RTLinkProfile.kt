package info.nightscout.comboctl.base

/**
 * Process-wide choice of how remote-terminal navigation paces itself against the pump.
 *
 * A process drives one pump over one Bluetooth link, so one switch per process is the right
 * granularity. It exists because the same driver now runs on two very different controllers:
 *
 * - The phone has always driven the Combo with the standard pacing, and its direct mode must
 *   keep behaving exactly as it did. With [slowLink] left at its default of false, every code
 *   path in the driver is the one the phone ran before this switch was introduced; the
 *   slow-link variants are separate branches that are never entered.
 *
 * - A Wear OS watch reaches the same pump over a link that is far slower while a setting screen
 *   is open (measured on pump 10392647: 0.55-1.2 s to send one packet instead of 0.23 s, and
 *   0.9-4.8 s for the pump to apply a press). The standard pacing loses presses and overshoots
 *   there, so the watch-side executor and the bench set [slowLink] before connecting.
 */
object RTLinkProfile {

    @Volatile
    var slowLink: Boolean = false
}
