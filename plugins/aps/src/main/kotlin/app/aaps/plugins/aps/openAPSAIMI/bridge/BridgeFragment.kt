package app.aaps.plugins.aps.openAPSAIMI.bridge

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import com.google.android.material.button.MaterialButton
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The owner's line to Claude. Top: what is wrong (a category, free text, photos or screenshots),
 * with a note of what the phone attaches by itself; below: the conversations, newest first, each
 * reply with buttons that continue the same thread. Everything written here is kept on the phone
 * and leaves when there is a network; the status line says so.
 */
class BridgeFragment : DaggerFragment() {

    @Inject lateinit var store: BridgeStore
    @Inject lateinit var api: BridgeApi
    @Inject lateinit var pack: BridgePack
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsLogger: AAPSLogger

    private val disposable = CompositeDisposable()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var input: EditText
    private lateinit var replyHint: TextView
    private lateinit var attachmentsRow: LinearLayout
    private lateinit var threadsBox: LinearLayout
    private val chipViews = mutableMapOf<String, TextView>()
    private var category = "other"
    private var replyTo: String? = null
    private val attachments = mutableListOf<BridgeStore.Attachment>()
    private var pendingPhoto: File? = null
    private var sending = false

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        pendingPhoto = null
        if (ok && file != null && file.exists() && file.length() > 0) addPhoto(file) else file?.delete()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val file = store.newPhotoFile()
        runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
        }.onSuccess { if (file.length() > 0) addPhoto(file) }
            .onFailure { aapsLogger.warn(LTag.CORE, "bridge: picked image not copied: ${it.message}") }
    }

    private val poll = object : Runnable {
        override fun run() {
            store.scheduleUpload()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(24)) }

        statusView = TextView(ctx).apply { textSize = 12f; setTextColor(Color.LTGRAY); setPadding(dp(4), 0, dp(4), dp(8)) }
        root.addView(statusView)

        val compose = CardView(ctx).apply { radius = dp(12).toFloat(); cardElevation = 0f; setCardBackgroundColor(0xFF1F2A33.toInt()); useCompatPadding = false }
        val composeBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)) }
        compose.addView(composeBox)
        composeBox.addView(TextView(ctx).apply { text = "Что не так?"; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })

        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(6)) }
        for ((key, title) in BridgeStore.CATEGORIES) {
            val chip = TextView(ctx).apply {
                text = title; textSize = 13f; setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { category = key; paintChips() }
            }
            chipViews[key] = chip
            chips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
        }
        composeBox.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        paintChips()

        replyHint = TextView(ctx).apply { textSize = 12f; setTextColor(0xFFF2C14E.toInt()); visibility = View.GONE; setPadding(0, 0, 0, dp(4)) }
        composeBox.addView(replyHint)

        input = EditText(ctx).apply {
            hint = "Опишите словами. Голосом — через микрофон на клавиатуре."
            setHintTextColor(0xFF8A99A6.toInt()); setTextColor(Color.WHITE); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; gravity = Gravity.TOP
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF0F171E.toInt()); setStroke(dp(1), 0xFF35444F.toInt()) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        composeBox.addView(input, LinearLayout.LayoutParams(-1, -2))

        attachmentsRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        composeBox.addView(attachmentsRow)

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun button(label: String, onClick: () -> Unit) = MaterialButton(ctx).apply {
            text = label; isAllCaps = false; textSize = 13f; setOnClickListener { onClick() }
        }
        buttons.addView(button("Фото") { openCamera() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        buttons.addView(button("Из галереи") { pickImage.launch("image/*") }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        buttons.addView(button("Отправить Claude") { send() }, LinearLayout.LayoutParams(0, -2, 1.3f))
        composeBox.addView(buttons)

        composeBox.addView(TextView(ctx).apply {
            text = "Приложится само: сахар за 3 ч, последний расчёт цикла, лечение за 6 ч, состояние помпы и профиля, батарея и сеть, хвост лога. " +
                "Снимок экрана: сделайте его как обычно и добавьте из галереи. Без сети сообщение сохранится и уйдёт позже."
            textSize = 11f; setTextColor(0xFF8A99A6.toInt()); setPadding(0, dp(8), 0, 0)
        })
        root.addView(compose, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        root.addView(TextView(ctx).apply { text = "Разговоры"; textSize = 15f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), dp(4), 0, dp(6)) })
        threadsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(threadsBox)

        return NestedScrollView(ctx).apply { addView(root) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.post(poll)
        disposable += rxBus.toObservable(EventBridgeChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ refresh() }, fabricPrivacy::logException)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
        disposable.clear()
    }

    private fun paintChips() {
        for ((key, view) in chipViews) {
            val selected = key == category
            view.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (selected) 0xFF2E6F9E.toInt() else 0xFF0F171E.toInt())
                setStroke(dp(1), if (selected) 0xFF5FA8DD.toInt() else 0xFF35444F.toInt())
            }
            view.setTextColor(if (selected) Color.WHITE else 0xFFB8C4CE.toInt())
        }
    }

    private fun openCamera() {
        val file = store.newPhotoFile()
        pendingPhoto = file
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        runCatching { takePicture.launch(uri) }.onFailure {
            pendingPhoto = null
            Toast.makeText(requireContext(), "Камера недоступна: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addPhoto(file: File) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { BridgeImages.shrink(file) }
            attachments += BridgeStore.Attachment(file.name, file.absolutePath, "image/jpeg")
            renderAttachments()
        }
    }

    private fun renderAttachments() {
        attachmentsRow.removeAllViews()
        for (a in attachments.toList()) {
            val row = TextView(requireContext()).apply {
                text = "📎 ${a.name} (${File(a.path).length() / 1024} КБ)   ✕"
                textSize = 12f; setTextColor(0xFFB8C4CE.toInt()); setPadding(0, dp(4), 0, 0)
                setOnClickListener { attachments.remove(a); renderAttachments() }
            }
            attachmentsRow.addView(row)
        }
    }

    private fun setReply(thread: BridgeStore.Thread, prefix: String) {
        replyTo = thread.localId
        replyHint.text = "В разговор: ${thread.title.take(40)}"
        replyHint.visibility = View.VISIBLE
        input.setText(prefix)
        input.setSelection(input.text.length)
        input.requestFocus()
    }

    private fun send() {
        if (sending) return
        val text = input.text.toString().trim()
        if (text.isEmpty() && attachments.isEmpty()) {
            Toast.makeText(requireContext(), "Напишите хотя бы пару слов или добавьте фото", Toast.LENGTH_SHORT).show()
            return
        }
        sending = true
        val files = attachments.toList()
        val inThread = replyTo
        val cat = category
        lifecycleScope.launch {
            val collected = withContext(Dispatchers.IO) { runCatching { pack.collect() }.getOrElse { org.json.JSONObject().put("error", "pack: ${it.message}") } }
            store.newReport(cat, text, files, collected, "phone", inThread)
            attachments.clear(); renderAttachments()
            input.setText(""); replyTo = null; replyHint.visibility = View.GONE
            store.scheduleUpload()
            sending = false
            Toast.makeText(requireContext(), if (api.configured()) "Сохранено, отправляю" else "Сохранено. ${BridgeStore.NOT_CONFIGURED}", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun refresh() {
        if (!isAdded) return
        val pending = store.pendingCount()
        statusView.text = when {
            !api.configured() -> "Сервер не настроен: ${BridgeStore.NOT_CONFIGURED}"
            pending > 0       -> "В очереди: $pending. Уйдёт, как только появится сеть."
            else              -> "Связь настроена. Ответ приходит сюда и в Telegram."
        }
        threadsBox.removeAllViews()
        val threads = store.threads().take(10)
        if (threads.isEmpty()) {
            threadsBox.addView(TextView(requireContext()).apply { text = "Пока ничего не отправлено."; textSize = 13f; setTextColor(Color.GRAY); setPadding(dp(4), 0, 0, 0) })
            return
        }
        for (thread in threads) threadsBox.addView(threadCard(thread), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun threadCard(thread: BridgeStore.Thread): View {
        val ctx = requireContext()
        val card = CardView(ctx).apply { radius = dp(12).toFloat(); cardElevation = 0f; setCardBackgroundColor(0xFF18222B.toInt()) }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        card.addView(box)
        val header = TextView(ctx).apply {
            text = "${BridgeStore.categoryTitle(thread.category)} · ${dateUtil.dateAndTimeString(thread.lastAt)}" +
                (if (thread.awaitingReply) " · ждёт ответа" else "")
            textSize = 12f; setTextColor(if (thread.awaitingReply) 0xFFF2C14E.toInt() else 0xFF8A99A6.toInt())
        }
        box.addView(header)
        for (m in thread.messages) {
            val mine = m.role == "user"
            val bubble = TextView(ctx).apply {
                val who = if (mine) (if (m.source == "watch") "Часы" else if (m.source == "telegram") "Вы (Telegram)" else "Вы") else "Claude"
                val tail = when {
                    m.pending       -> "\n⏳ ждёт отправки" + (m.error?.let { ": $it" } ?: "")
                    m.error != null -> "\n⚠ ${m.error}"
                    else            -> ""
                }
                val files = if (m.attachments.isNotEmpty()) "\n📎 ${m.attachments.size}" else ""
                text = "$who · ${dateUtil.timeString(m.at)}\n${m.text}$files$tail"
                textSize = 13f; setTextColor(Color.WHITE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (mine) 0xFF24405A.toInt() else 0xFF26323B.toInt()) }
                setTextIsSelectable(true)
            }
            box.addView(bubble, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); if (mine) leftMargin = dp(24) else rightMargin = dp(24) })
        }
        if (thread.messages.lastOrNull()?.role == "assistant") {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            for ((label, prefix) in listOf("Не помогло" to "Не помогло: ", "Подробнее" to "Подробнее: ", "Ставьте" to "Ставьте.")) {
                row.addView(MaterialButton(ctx).apply { text = label; isAllCaps = false; textSize = 12f; setOnClickListener { setReply(thread, prefix) } },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
            }
            box.addView(row)
        } else if (!thread.awaitingReply) Unit
        else box.addView(TextView(ctx).apply {
            text = "Можно дописать в этот же разговор"; textSize = 11f; setTextColor(0xFF8A99A6.toInt()); setPadding(0, dp(6), 0, 0)
            setOnClickListener { setReply(thread, "") }
        })
        return card
    }

    companion object {

        private const val POLL_MS = 45_000L
    }
}
