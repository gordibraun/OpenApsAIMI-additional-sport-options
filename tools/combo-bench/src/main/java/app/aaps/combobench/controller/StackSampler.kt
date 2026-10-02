package app.aaps.combobench.controller

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A crude sampling profiler for one session: every [intervalMs] it records where the runnable
 * threads of this process are, and afterwards reports the most frequent places.
 *
 * It exists because the driver was seen holding a whole CPU core on the watch while a setting
 * screen was open, which makes it fall behind the pump's stream of display frames. Finding out
 * which code that is has to be measured on the watch itself; guessing from the phone's behaviour
 * is what the earlier attempts did.
 */
internal class StackSampler(private val intervalMs: Long = 50L) {

    private val running = AtomicBoolean(false)
    private val leaf = HashMap<String, Int>()
    private val driver = HashMap<String, Int>()
    private var samples = 0
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            val self = Thread.currentThread()
            while (running.get()) {
                for ((candidate, stack) in Thread.getAllStackTraces()) {
                    if ((candidate === self) || (candidate.state != Thread.State.RUNNABLE) || stack.isEmpty()) continue
                    // Threads parked in native waits report RUNNABLE too; they are not working.
                    val top = stack[0]
                    if (top.isNativeMethod && IDLE_NATIVE.any { top.methodName.contains(it) }) continue
                    synchronized(this) {
                        samples++
                        leaf.merge("${top.className.substringAfterLast('.')}.${top.methodName}", 1, Int::plus)
                        // The innermost driver frame says which part of the driver the time belongs to.
                        stack.firstOrNull { it.className.startsWith("info.nightscout.comboctl") }?.let {
                            driver.merge("${it.className.substringAfterLast('.')}.${it.methodName}", 1, Int::plus)
                        }
                    }
                }
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
        }, "combo-stack-sampler").apply { isDaemon = true; start() }
    }

    fun stop(): JSONObject {
        running.set(false)
        thread?.interrupt()
        synchronized(this) {
            fun top(map: Map<String, Int>) = JSONArray().apply {
                map.entries.sortedByDescending { it.value }.take(14).forEach {
                    put(JSONObject().put("where", it.key).put("samples", it.value))
                }
            }
            return JSONObject().put("samples", samples).put("intervalMs", intervalMs)
                .put("leaf", top(leaf)).put("driver", top(driver))
        }
    }

    private companion object {
        val IDLE_NATIVE = listOf("nativePollOnce", "park", "wait", "accept", "read", "recv", "poll", "sleep")
    }
}
