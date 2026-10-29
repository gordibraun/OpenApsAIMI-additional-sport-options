package app.aaps.plugins.aps.openAPSAIMI.food

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
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
import kotlin.math.roundToInt

/**
 * The «Еда» tab: a big camera button, then a card per photographed plate with the server's
 * estimate and three plain choices: eat it (the grams go to the bolus wizard), give another number,
 * or decline. Words come from [FoodPhotoCard]; the record is [FoodPhotoStore].
 */
class FoodPhotoFragment : DaggerFragment() {

    @Inject lateinit var store: FoodPhotoStore
    @Inject lateinit var api: FoodApi
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsLogger: AAPSLogger

    private val disposable = CompositeDisposable()

    private val bg = Color.parseColor("#10141C")
    private val card = Color.parseColor("#1E293B")
    private val textMain = Color.WHITE
    private val textDim = Color.parseColor("#B0BEC5")
    private val green = Color.parseColor("#43A047")
    private val greenChip = Color.parseColor("#2E7D32")
    private val blue = Color.parseColor("#1E88E5")
    private val blueChip = Color.parseColor("#1565C0")
    private val orange = Color.parseColor("#FFB74D")
    private val orangeChip = Color.parseColor("#8D5A1B")
    private val dimChip = Color.parseColor("#37474F")
    private val red = Color.parseColor("#EF5350")

    private var pendingId: String? = null
    private var pendingPath: String? = null
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var caption: EditText
    private var banner: View? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val id = pendingId
        val path = pendingPath
        pendingId = null
        pendingPath = null
        if (id == null || path == null) return@registerForActivityResult
        val file = File(path)
        if (ok && file.length() > 0) keep(id, file) else file.delete()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val (id, file) = store.newPhotoFile()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                requireContext().contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ?: error("картинка не открылась")
                store.add(id, file, caption.text.toString())
            }.onFailure {
                file.delete()
                aapsLogger.warn(LTag.CORE, "food photo from gallery failed: $it")
            }
            withContext(Dispatchers.Main) { if (isAdded) caption.setText("") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingId = savedInstanceState?.getString("pendingId")
        pendingPath = savedInstanceState?.getString("pendingPath")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingId", pendingId)
        outState.putString("pendingPath", pendingPath)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
            setBackgroundColor(bg)
        }
        root.addView(text(FoodPhotoCard.INTRO, 13f, textDim).apply { setPadding(dp(4), 0, dp(4), dp(8)) })

        caption = EditText(ctx).apply {
            hint = "Что на тарелке? (необязательно)"
            setHintTextColor(textDim)
            setTextColor(textMain)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            background = rounded(card, 10)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        root.addView(caption, lp().apply { bottomMargin = dp(10) })

        root.addView(button("📷  Сфотографировать еду", green, textMain, 18f, dp(58)) { shoot() }, lp().apply { bottomMargin = dp(8) })
        root.addView(button("🖼  Выбрать из галереи", card, textMain, 15f, dp(46)) { pickImage.launch("image/*") }, lp().apply { bottomMargin = dp(14) })

        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        return NestedScrollView(ctx).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onResume() {
        super.onResume()
        disposable += rxBus.toObservable(EventFoodPhotoChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ refresh() }, fabricPrivacy::logException)
        refresh()
        if (store.pending().isNotEmpty()) store.enqueueSync()
    }

    override fun onPause() {
        disposable.clear()
        super.onPause()
    }

    // ---- taking and keeping photos ----

    private fun shoot() {
        val ctx = requireContext()
        val (id, file) = store.newPhotoFile()
        pendingId = id
        pendingPath = file.absolutePath
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        runCatching { takePicture.launch(uri) }.onFailure {
            pendingId = null
            pendingPath = null
            toast("Камера не открылась: ${it.javaClass.simpleName}")
        }
    }

    private fun keep(id: String, file: File) {
        val words = caption.text.toString()
        caption.setText("")
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { store.add(id, file, words) }.onFailure { aapsLogger.error(LTag.CORE, "food photo not kept", it) }
        }
    }

    // ---- drawing ----

    private fun refresh() {
        if (!isAdded) return
        list.removeAllViews()
        banner?.let { root.removeView(it) }
        banner = null
        if (!api.configured()) {
            banner = configBanner().also { root.addView(it, 1, lp().apply { bottomMargin = dp(10) }) }
        }
        val entries = store.all()
        if (entries.isEmpty()) {
            list.addView(text(FoodPhotoCard.EMPTY, 15f, textDim).apply { setPadding(dp(8), dp(24), dp(8), 0); gravity = Gravity.CENTER })
            return
        }
        entries.take(40).forEach { entry -> list.addView(cardView(entry), lp().apply { bottomMargin = dp(10) }) }
    }

    private fun configBanner(): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(orangeChip, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(text("Сервер оценки не подключён", 16f, textMain, bold = true))
        box.addView(text("Напишите боту /token, затем вставьте адрес и токен в настройки этого плагина. Фото до тех пор будут ждать.", 13f, textMain).apply { setPadding(0, dp(4), 0, dp(8)) })
        box.addView(button("Открыть настройки", card, textMain, 14f, dp(40)) {
            startActivity(Intent(ctx, uiInteraction.preferencesActivity).putExtra(UiInteraction.PLUGIN_NAME, FoodPhotoPlugin::class.java.simpleName))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun cardView(entry: FoodPhotoEntry): View {
        val ctx = requireContext()
        val v = FoodPhotoCard.of(entry, store.sending(entry.id)) { dateUtil.timeString(it) }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }

        // Photo on the left, header and chip then the headline on the right.
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val thumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(dimChip, 10)
            clipToOutline = true
        }
        top.addView(thumb, LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(12) })
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = store.thumbnail(entry, dp(168))
            withContext(Dispatchers.Main) { if (bitmap != null) thumb.setImageBitmap(bitmap) }
        }
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val headerRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(text(v.header, 13f, textDim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(chip(v.chip, v.chipTone))
        right.addView(headerRow)
        right.addView(text(v.headline, 19f, if (v.chipTone == Tone_WARN) orange else textMain, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        v.subline?.takeIf { it.isNotBlank() }?.let { right.addView(text(it, 13f, textDim)) }
        top.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(top)

        if (v.items.isNotEmpty()) column.addView(text(v.items.joinToString("\n"), 14f, textMain).apply { setPadding(0, dp(8), 0, 0) })
        v.composition?.let { column.addView(text(it, 13f, textDim).apply { setPadding(0, dp(4), 0, 0) }) }
        v.advice?.let { column.addView(text("💡 $it", 13f, textDim).apply { setPadding(0, dp(4), 0, 0) }) }
        v.problem?.let { column.addView(text("⚠ $it", 13f, orange).apply { setPadding(0, dp(6), 0, 0) }) }

        // The main action full width, the rest in one row underneath.
        val actions = v.actions
        if (actions.isNotEmpty()) {
            val main = actions.first()
            if (main == FoodPhotoCard.Action.EAT) {
                column.addView(button(FoodPhotoCard.actionLabel(main, v.gramsToEnter), green, textMain, 16f, dp(50)) { act(entry, main) }, lp().apply { topMargin = dp(10) })
            }
            val rest = if (main == FoodPhotoCard.Action.EAT) actions.drop(1) else actions
            if (rest.isNotEmpty()) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                rest.forEachIndexed { i, a ->
                    val danger = a == FoodPhotoCard.Action.DELETE || a == FoodPhotoCard.Action.DECLINE
                    row.addView(
                        button(FoodPhotoCard.actionLabel(a, v.gramsToEnter), dimChip, if (danger) orange else textMain, 14f, dp(42)) { act(entry, a) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6) }
                    )
                }
                column.addView(row, lp().apply { topMargin = dp(8) })
            }
        }

        return CardView(ctx).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(card)
            cardElevation = 0f
            alpha = if (entry.status == FoodPhotoEntry.Status.DECLINED) 0.6f else 1f
            addView(column)
        }
    }

    // ---- the owner's choices ----

    private fun act(entry: FoodPhotoEntry, action: FoodPhotoCard.Action) {
        when (action) {
            FoodPhotoCard.Action.EAT     -> eat(entry)
            FoodPhotoCard.Action.CORRECT -> askGrams(entry)
            FoodPhotoCard.Action.DECLINE -> store.decide(entry.id, FoodPhotoEntry.Status.DECLINED)
            FoodPhotoCard.Action.UNDO    -> store.decide(entry.id, if (entry.serverId != null) FoodPhotoEntry.Status.ESTIMATED else FoodPhotoEntry.Status.QUEUED)
            FoodPhotoCard.Action.RETRY   -> store.update(entry.id) { status = FoodPhotoEntry.Status.QUEUED; error = null }
            FoodPhotoCard.Action.DELETE  -> confirmDelete(entry)
        }
    }

    private fun eat(entry: FoodPhotoEntry) {
        val grams = entry.gramsToEnter ?: return askGrams(entry)
        store.decide(entry.id, FoodPhotoEntry.Status.EATEN, grams)
        uiInteraction.runWizardDialog(childFragmentManager, grams.roundToInt(), "фото ${entry.shortId}")
    }

    private fun askGrams(entry: FoodPhotoEntry) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "граммы углеводов"
            entry.gramsToEnter?.let { setText(it.roundToInt().toString()) }
            selectAll()
        }
        val wrap = LinearLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, lp()) }
        AlertDialog.Builder(ctx)
            .setTitle("Сколько граммов на самом деле?")
            .setMessage(entry.estimatedGrams?.let { "Оценка по фото: ≈ ${it.roundToInt()} г. Ваше число заменит её и уйдёт на сервер как поправка." } ?: "Сервер ещё не ответил; ваше число будет главным.")
            .setView(wrap)
            .setPositiveButton("Сохранить") { _, _ ->
                val grams = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (grams == null || grams < 0 || grams > 500) toast("Нужно число от 0 до 500")
                else store.update(entry.id) { finalGrams = grams; decisionPending = serverId != null; decidedAtEpochMs = System.currentTimeMillis() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmDelete(entry: FoodPhotoEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("Удалить фото?")
            .setMessage("Запись и снимок исчезнут с телефона. На сервере, если он их видел, они останутся.")
            .setPositiveButton("Удалить") { _, _ -> store.remove(entry.id) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---- small helpers ----

    private val Tone_WARN get() = FoodPhotoCard.Tone.WARN

    private fun chip(label: String, tone: FoodPhotoCard.Tone): TextView = text(label, 12f, textMain, bold = true).apply {
        background = rounded(
            when (tone) {
                FoodPhotoCard.Tone.DIM    -> dimChip
                FoodPhotoCard.Tone.ACCENT -> blueChip
                FoodPhotoCard.Tone.GOOD   -> greenChip
                FoodPhotoCard.Tone.WARN   -> orangeChip
            }, 20
        )
        setPadding(dp(10), dp(3), dp(10), dp(3))
    }

    private fun button(label: String, color: Int, textColor: Int, sizeSp: Float, heightPx: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(requireContext()).apply {
            text = label
            isAllCaps = false
            setTextColor(textColor)
            textSize = sizeSp
            backgroundTintList = ColorStateList.valueOf(color)
            rippleColor = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
            cornerRadius = dp(12)
            minimumHeight = heightPx
            minHeight = heightPx
            insetTop = 0
            insetBottom = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView = TextView(requireContext()).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_LONG).show()

}
