package app.aaps.wear.comm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearDataToMobile
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import app.aaps.wear.R
import app.aaps.wear.events.EventWearPreferenceChange
import app.aaps.wear.heartrate.HeartRateListener
import app.aaps.wear.interaction.ConfigurationActivity
import app.aaps.wear.interaction.utils.Persistence
import app.aaps.wear.wearStepCount.StepCountListener
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dagger.android.AndroidInjection
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.ExperimentalSerializationApi
import javax.inject.Inject

class DataLayerListenerServiceWear :
    WearableListenerService(),
    CapabilityClient.OnCapabilityChangedListener {

    private val TAG = "DataLayerListenerServiceWear"

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var persistence: Persistence
    @Inject lateinit var sp: SP
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers

    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handler = Handler(
        HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper
    )

    private var lastInboundMs: Long = SystemClock.elapsedRealtime()
    private var lastPingMs: Long = 0L

    // --- watchdog/keepalive constants ---
    private val KEEPALIVE_PERIOD_MS: Long = 10_000L      // тик каждые 10с
    private val WATCHDOG_SOFT_TIMEOUT_MS: Long = 35_000L // мягкий нудж ~35с
    private val WATCHDOG_HARD_TIMEOUT_MS: Long = 60_000L // жёсткая попытка ~60с

    // для подавления повторной эскалации до следующего inbound
    private var escalationLevel: Int = 0

    private val disposable = CompositeDisposable()
    private var heartRateListener: HeartRateListener? = null
    private var stepCountListener: StepCountListener? = null

    private val rxPath get() = getString(app.aaps.core.interfaces.R.string.path_rx_bridge)
    private val rxDataPath get() = getString(app.aaps.core.interfaces.R.string.path_rx_data_bridge)

    private fun withWake(block: () -> Unit) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:wake")
        wl.setReferenceCounted(false)
        try {
            wl.acquire(10_000)
            block()
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    @ExperimentalSerializationApi
    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        startForegroundService()
        handler.post { updateTranscriptionCapability() }

        capabilityClient.addListener(this, PHONE_CAPABILITY)

        disposable += rxBus
            .toObservable(EventWearToMobile::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe {
                Log.d(TAG, "trigger EventWearToMobile for payload: ${it.payload}")
                sendMessage(rxPath, it.payload.serialize())
            }

        disposable += rxBus
            .toObservable(EventWearDataToMobile::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe {
                sendMessage(rxDataPath, it.payload.serializeByte())
            }

        disposable += rxBus
            .toObservable(EventWearPreferenceChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { event: EventWearPreferenceChange ->
                if (event.changedKey == getString(R.string.key_heart_rate_sampling)) updateHeartRateListener()
                if (event.changedKey == getString(R.string.key_steps_sampling)) updateStepsCountListener()
            }

        updateHeartRateListener()
        updateStepsCountListener()
        startKeepAlive()
        lastInboundMs = SystemClock.elapsedRealtime()
    }

    override fun onCapabilityChanged(p0: CapabilityInfo) {
        super.onCapabilityChanged(p0)
        handler.post { updateTranscriptionCapability() }
        aapsLogger.debug(
            LTag.WEAR,
            "$TAG onCapabilityChanged:  ${p0.name} ${
                p0.nodes.joinToString(", ") { it.displayName + "(" + it.id + ")" }
            }"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        capabilityClient.removeListener(this, PHONE_CAPABILITY)
        stopKeepAlive()
        aapsLogger.debug(LTag.WEAR, "$TAG onDestroy: keepAlive stopped, scope cancel pending")
        scope.cancel()
        disposable.clear()
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach

            val path = event.dataItem.uri.path
            aapsLogger.debug(LTag.WEAR, "$TAG onDataChanged: Path: $path, item=${event.dataItem}")
            escalationLevel = 0
            lastInboundMs = SystemClock.elapsedRealtime()

            when (path) {
                "/aaps/overview" -> {
                    try {
                        val dmi = DataMapItem.fromDataItem(event.dataItem)
                        val dm = dmi.dataMap
                        aapsLogger.debug(LTag.WEAR, "$TAG overview dm keys=${dm.keySet()}")
                    } catch (e: Exception) {
                        aapsLogger.error(
                            LTag.WEAR,
                            "$TAG onDataChanged(/aaps/overview) failed",
                            e
                        )
                    }
                }

                "/aaps/snapshot" -> {
                    try {
                        val dmi = DataMapItem.fromDataItem(event.dataItem)
                        val dm = dmi.dataMap

                        val singleJson = dm.getString("singleJson")
                        val statusJson = dm.getString("statusJson")

                        var delivered = false

                        // Вариант 1: сериализованные объекты
                        if (singleJson != null) {
                            val single =
                                EventData.deserialize(singleJson) as EventData.SingleBg
                            rxBus.send(single)
                            delivered = true
                        }
                        if (statusJson != null) {
                            val st =
                                EventData.deserialize(statusJson) as EventData.Status
                            rxBus.send(st)
                        }

                        // Вариант 2: примитивы -> собрать SingleBg
                        if (!delivered && dm.containsKey("sgv") && dm.containsKey("ts")) {
                            val sgvInt = dm.getInt("sgv")
                            val ts = dm.getLong("ts")
                            val delta = dm.getString("delta") ?: "--"
                            val avgDelta = dm.getString("avgDelta") ?: "--"
                            val sgvLevel =
                                if (dm.containsKey("sgvLevel")) dm.getInt("sgvLevel") else 0

                            val high = 180.0
                            val low = 70.0

                            val single = EventData.SingleBg(
                                dataset = 0,
                                timeStamp = ts,
                                sgvString = sgvInt.toString(),
                                glucoseUnits = "-",
                                slopeArrow = "--",
                                delta = delta,
                                deltaDetailed = delta,
                                avgDelta = avgDelta,
                                avgDeltaDetailed = avgDelta,
                                sgvLevel = sgvLevel.toLong(),
                                sgv = sgvInt.toDouble(),
                                high = high,
                                low = low
                            )
                            rxBus.send(single)
                        }

                        aapsLogger.debug(LTag.WEAR, "$TAG onDataChanged(/aaps/snapshot) OK")
                    } catch (e: Exception) {
                        aapsLogger.error(
                            LTag.WEAR,
                            "$TAG onDataChanged(/aaps/snapshot) failed",
                            e
                        )
                    }
                }

                else -> {
                    aapsLogger.debug(LTag.WEAR, "$TAG onDataChanged: UNHANDLED path=$path")
                }
            }
        }
        super.onDataChanged(dataEvents)
    }

    @ExperimentalSerializationApi
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        aapsLogger.debug(LTag.WEAR, "$TAG 1. onMessageReceived: $messageEvent")
        lastInboundMs = SystemClock.elapsedRealtime()
        escalationLevel = 0
        when (messageEvent.path) {
            rxPath -> {
                aapsLogger.debug(
                    LTag.WEAR,
                    "$TAG onMessageReceived: ${String(messageEvent.data)}"
                )
                val command = EventData.deserialize(String(messageEvent.data))
                Log.d(
                    TAG,
                    "messageEvent.data = ${String(messageEvent.data)}, command = $command"
                )
                if (command is EventData.GraphData) {
                    aapsLogger.info(
                        LTag.WEAR,
                        "$TAG GraphData received: sgv=${command.entries.lastOrNull()?.sgv}, entries=${command.entries.size}"
                    )
                }
                rxBus.send(command.also { it.sourceNodeId = messageEvent.sourceNodeId })
                transcriptionNodeId = messageEvent.sourceNodeId
                aapsLogger.debug(LTag.WEAR, "$TAG Updated node: $transcriptionNodeId")
            }

            rxDataPath -> {
                aapsLogger.debug(LTag.WEAR, "$TAG; onMessageReceived: ${messageEvent.data.size}")
                ZipWatchfaceFormat.loadCustomWatchface(messageEvent.data, "", false)?.let {
                    val command = EventData.ActionSetCustomWatchface(it.cwfData)
                    rxBus.send(command.also { it.sourceNodeId = messageEvent.sourceNodeId })
                }
                transcriptionNodeId = messageEvent.sourceNodeId
                aapsLogger.debug(LTag.WEAR, "$TAG Updated node: $transcriptionNodeId")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            INTENT_CANCEL_BOLUS -> {
                NotificationManagerCompat.from(this).cancel(BOLUS_PROGRESS_NOTIF_ID)
                rxBus.send(EventWearToMobile(EventData.CancelBolus(System.currentTimeMillis())))
            }

            INTENT_WEAR_TO_MOBILE -> sendMessage(
                rxPath,
                intent.extras?.getString(KEY_ACTION_DATA)
            )

            INTENT_CANCEL_NOTIFICATION ->
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(CHANGE_NOTIF_ID)
        }

        // keepalive-ветка
        if (intent?.action == ACTION_KEEPALIVE) {
            withWake {
                lastPingMs = SystemClock.elapsedRealtime()
                rxBus.send(EventWearToMobile(EventData.ActionPing(System.currentTimeMillis())))
            }
            scheduleExactKeepAlive()
            return START_STICKY
        }

        // обычный запуск/перезапуск — гарантируем foreground
        startForegroundService()
        return START_STICKY
    }

    // foreground c типами FGS
    private fun startForegroundService() {
        createNotificationChannel()
        val notificationIntent = Intent(this, ConfigurationActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.datalayer_notification_title))
            .setContentText(getString(R.string.datalayer_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_icon)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            try {
                startForeground(FOREGROUND_NOTIF_ID, notification, type)
            } catch (_: Throwable) {
                startForeground(FOREGROUND_NOTIF_ID, notification)
            }
        } else {
            startForeground(FOREGROUND_NOTIF_ID, notification)
        }
    }

    private fun updateHeartRateListener() {
        if (sp.getBoolean(R.string.key_heart_rate_sampling, false)) {
            if (heartRateListener == null) {
                heartRateListener = HeartRateListener(
                    this, aapsLogger, sp, aapsSchedulers
                ).also { hrl -> disposable += hrl }
            }
        } else {
            heartRateListener?.let { hrl ->
                disposable.remove(hrl)
                heartRateListener = null
            }
        }
    }

    private fun updateStepsCountListener() {
        if (sp.getBoolean(R.string.key_steps_sampling, false)) {
            if (stepCountListener == null) {
                stepCountListener = StepCountListener(
                    this, aapsLogger, aapsSchedulers
                ).also { scl -> disposable += scl }
            }
        } else {
            stepCountListener?.let { scl ->
                disposable.remove(scl)
                stepCountListener = null
            }
        }
    }

    @Suppress("PrivatePropertyName")
    private val CHANNEL_ID: String = "DataLayerForegroundServiceChannel"

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Data Layer Foreground Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        serviceChannel.setShowBadge(false)
        serviceChannel.enableLights(false)
        serviceChannel.enableVibration(false)
        serviceChannel.setSound(null, null)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private var transcriptionNodeId: String? = null

    // мягкая эскалация
    private fun softNudge(now: Long) {
        withWake {
            aapsLogger.debug(
                LTag.WEAR,
                "$TAG watchdog SOFT: sinceLastInbound=${now - lastInboundMs}ms; refresh capability + extra ping"
            )
            handler.post { updateTranscriptionCapability() }
            rxBus.send(EventWearToMobile(EventData.ActionPing(System.currentTimeMillis())))
        }
    }

    // жёсткая эскалация
    private fun hardRecover(now: Long) {
        withWake {
            aapsLogger.debug(
                LTag.WEAR,
                "$TAG watchdog HARD: sinceLastInbound=${now - lastInboundMs}ms; reselect node & recreate message client"
            )
            transcriptionNodeId = null
            handler.post { updateTranscriptionCapability() }
            rxBus.send(EventWearToMobile(EventData.ActionPing(System.currentTimeMillis())))
        }
    }

    private var keepAliveStarted = false

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$TAG:keepalive"
                )
                wl.setReferenceCounted(false)
                try {
                    wl.acquire(3000)
                    lastPingMs = now
                    rxBus.send(
                        EventWearToMobile(
                            EventData.ActionPing(
                                System.currentTimeMillis()
                            )
                        )
                    )
                    aapsLogger.debug(
                        LTag.WEAR,
                        "$TAG keepAlive tick: ping sent; sinceLastInbound=${now - lastInboundMs} ms"
                    )
                } finally {
                    if (wl.isHeld) wl.release()
                }

                val silence = now - lastInboundMs
                when {
                    silence > WATCHDOG_HARD_TIMEOUT_MS && escalationLevel < 2 -> {
                        escalationLevel = 2
                        hardRecover(now)
                    }

                    silence > WATCHDOG_SOFT_TIMEOUT_MS && escalationLevel < 1 -> {
                        escalationLevel = 1
                        softNudge(now)
                    }
                }
            } catch (t: Throwable) {
                aapsLogger.error(LTag.WEAR, "$TAG keepAlive error: $t")
            } finally {
                scheduleExactKeepAlive()
            }
        }
    }

    private fun startKeepAlive() {
        if (!keepAliveStarted) {
            keepAliveStarted = true
            handler.postDelayed(keepAliveRunnable, 1_000L)
            scheduleExactKeepAlive()
        }
    }

    private fun stopKeepAlive() {
        if (keepAliveStarted) {
            keepAliveStarted = false
            handler.removeCallbacks(keepAliveRunnable)
            lastPingMs = 0L
            aapsLogger.debug(LTag.WEAR, "$TAG keepAlive stopped")
        }
    }

    private fun scheduleExactKeepAlive() {
        val am = getSystemService(android.app.AlarmManager::class.java)
        val pi = android.app.PendingIntent.getService(
            this, 1001,
            Intent(this, DataLayerListenerServiceWear::class.java).setAction(ACTION_KEEPALIVE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + KEEPALIVE_PERIOD_MS,
            pi
        )
    }

    private fun ensureNodeSelected(): String? {
        transcriptionNodeId?.let { return it }
        return try {
            val capabilityInfo: CapabilityInfo = Tasks.await(
                capabilityClient.getCapability(
                    PHONE_CAPABILITY,
                    CapabilityClient.FILTER_REACHABLE
                )
            )
            aapsLogger.debug(
                LTag.WEAR,
                "$TAG ensureNodeSelected Nodes: ${
                    capabilityInfo.nodes.joinToString(", ") { it.displayName + "(" + it.id + ")" }
                }"
            )
            pickBestNodeId(capabilityInfo.nodes)?.also { selected ->
                transcriptionNodeId = selected
                aapsLogger.debug(LTag.WEAR, "$TAG ensureNodeSelected picked: $selected")
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "$TAG ensureNodeSelected failed: $e")
            null
        }
    }

    private fun updateTranscriptionCapability() {
        val capabilityInfo: CapabilityInfo = Tasks.await(
            capabilityClient.getCapability(
                PHONE_CAPABILITY,
                CapabilityClient.FILTER_REACHABLE
            )
        )
        aapsLogger.debug(
            LTag.WEAR,
            "$TAG Nodes: ${
                capabilityInfo.nodes.joinToString(", ") { it.displayName + "(" + it.id + ")" }
            }"
        )
        pickBestNodeId(capabilityInfo.nodes)?.let { transcriptionNodeId = it }
        aapsLogger.debug(LTag.WEAR, "$TAG Selected node: $transcriptionNodeId")
    }

    private fun pickBestNodeId(nodes: Set<Node>): String? =
        nodes.firstOrNull { it.isNearby }?.id ?: nodes.firstOrNull()?.id

    @Suppress("unused")
    private fun sendData(path: String, vararg params: DataMap) {
        scope.launch {
            try {
                for (dm in params) {
                    val request = PutDataMapRequest.create(path).apply {
                        dataMap.putAll(dm)
                    }
                        .asPutDataRequest()
                        .setUrgent()

                    val result = dataClient.putDataItem(request).await()
                    aapsLogger.debug(
                        LTag.WEAR,
                        "$TAG sendData: ${result.uri} ${params.joinToString()}"
                    )
                }
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (exception: Exception) {
                aapsLogger.error(LTag.WEAR, "$TAG DataItem failed: $exception")
            }
        }
    }

    private fun sendMessage(path: String, data: String?) {
        val nodeId = ensureNodeSelected()
        if (nodeId == null) {
            aapsLogger.debug(
                LTag.WEAR,
                "$TAG sendMessage: Ignoring message. No node available (after ensure)."
            )
            return
        }
        aapsLogger.debug(LTag.WEAR, "$TAG sendMessage: $path, data: $data, nodeId = $nodeId")
        messageClient.sendMessage(nodeId, path, data?.toByteArray() ?: byteArrayOf()).apply {
            addOnSuccessListener {
                aapsLogger.debug(LTag.WEAR, "$TAG sendMessage: $path success $it")
            }
            addOnFailureListener {
                aapsLogger.debug(LTag.WEAR, "$TAG sendMessage: $path failure $it")
            }
        }
    }

    private fun sendMessage(path: String, data: ByteArray) {
        aapsLogger.debug(LTag.WEAR, "$TAG sendMessage byte array: $path ${data.size}")
        val nodeId = ensureNodeSelected()
        if (nodeId == null) {
            aapsLogger.debug(
                LTag.WEAR,
                "$TAG sendMessage(bytes): Ignoring message. No node available (after ensure)."
            )
            return
        }
        messageClient.sendMessage(nodeId, path, data).apply {
            addOnSuccessListener { /* ok */ }
            addOnFailureListener {
                aapsLogger.debug(
                    LTag.WEAR,
                    "$TAG sendMessage(bytes): $path failure ${data.size}"
                )
            }
        }
    }

    companion object {
        const val PHONE_CAPABILITY = "androidaps_mobile"

        val INTENT_NEW_DATA = DataLayerListenerServiceWear::class.java.name + ".NewData"
        val INTENT_CANCEL_BOLUS = DataLayerListenerServiceWear::class.java.name + ".CancelBolus"
        val INTENT_WEAR_TO_MOBILE = DataLayerListenerServiceWear::class.java.name + ".WearToMobile"
        val INTENT_CANCEL_NOTIFICATION =
            DataLayerListenerServiceWear::class.java.name + ".CancelNotification"

        const val KEY_ACTION_DATA = "actionData"
        const val KEY_ACTION = "action"
        const val KEY_MESSAGE = "message"
        const val KEY_TITLE = "title"

        const val BOLUS_PROGRESS_NOTIF_ID = 1
        const val CONFIRM_NOTIF_ID = 2
        const val FOREGROUND_NOTIF_ID = 3
        const val CHANGE_NOTIF_ID = 556677
        const val ACTION_KEEPALIVE = "app.aaps.wear.ACTION_KEEPALIVE"

        const val AAPS_NOTIFY_CHANNEL_ID_OPEN_LOOP = "AndroidAPS-OpenLoop"
        const val AAPS_NOTIFY_CHANNEL_ID_BOLUS_PROGRESS_SILENT =
            "bolus progress silent"
        const val AAPS_NOTIFY_CHANNEL_ID_BOLUS_PROGRESS =
            "bolus progress vibration"
    }
}