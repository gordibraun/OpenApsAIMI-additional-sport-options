package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.AimiMealAssist
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.activity.ActivityPlanCalculator
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.aps.BolusInputSnapshot
import app.aaps.core.objects.wizard.BolusWizard
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchControlHandler @Inject constructor(
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val loop: Loop,
    private val activePlugin: ActivePlugin,
    private val constraints: ConstraintsChecker,
    private val persistence: PersistenceLayer,
    private val commandQueue: CommandQueue,
    private val rxBus: RxBus,
    private val logger: AAPSLogger,
    private val uel: UserEntryLogger,
    private val dateUtil: DateUtil,
    private val mealAssist: AimiMealAssist,
    private val mealCalculator: WatchMealCalculator
) {
    private data class Preview(val request: EventData.WatchControlRequest, val plan: ActivityPlanCalculator.Plan?, val wizard: BolusWizard?)
    private val pending = PendingWatchActions<Preview>()

    fun preview(request: EventData.WatchControlRequest) = safely(request.sourceNodeId) {
        require(dateUtil.now() - request.createdAt in -30_000L..90_000L) { "Запрос устарел. Откройте ввод заново." }
        validate(request)
        val plan = if (request.kind == "ACTIVITY") activityPlan(request) else null
        val wizard = if (request.kind == "MEAL") mealCalculator.calculate(request.carbs, request.carbType) else null
        val token = pending.issue(request.sourceNodeId, request.requestId, dateUtil.now(), Preview(request.copy(), plan, wizard))
            ?: error("Этот запрос уже обработан. Откройте ввод заново.")
        val summary = when (request.kind) {
            "CARBS" -> "Записать углеводы: ${request.carbs} г.\n${foodLabel(request.carbType)}.\nИнсулин: 0 Е."
            "MEAL" -> requireNotNull(wizard).let {
                "Углеводы: ${request.carbs} г.\n${foodLabel(request.carbType)}.\nПодать инсулин: ${it.insulinAfterConstraints} Е.\n\n${it.explainShort()}"
            }
            "INSULIN" -> "Подать инсулин: ${request.insulin} Е.\nУглеводы: 0 г."
            else -> requireNotNull(plan).let {
                "${if (it.mode == "WALK") "Прогулка" else "Спорт"}: ${it.durationMinutes} мин.\n" +
                    "Начало: ${if (it.startOffsetMinutes == 0) "сейчас" else "через ${it.startOffsetMinutes} мин"}.\n" +
                    "Учет после нагрузки: ${it.tailMinutes} мин.\n" +
                    if (it.requiredCarbs > 0) "Будут записаны ${it.requiredCarbs} г ${if (it.carbType == "fast") "быстрых" else "обычных"} углеводов через ${it.carbsWithinMinutes} мин. Подтвердите только если планируете их принять." else "Дополнительные углеводы по прогнозу не требуются."
            }
        }
        reply(request.sourceNodeId, "Подтверждение", summary, EventData.WatchControlConfirmed(token))
        logger.debug(LTag.WEAR, "Watch control preview ${request.kind} request=${request.requestId}")
    }

    fun confirm(command: EventData.WatchControlConfirmed) = safely(command.sourceNodeId) {
        val preview = pending.consume(command.sourceNodeId, command.token, dateUtil.now())
            ?: error("Подтверждение устарело или уже использовано. Ничего повторно не отправлено.")
        val request = preview.request
        validate(request)
        if (request.kind == "MEAL") {
            requireNotNull(preview.wizard).executeFromWear { success, comment, delivered ->
                if (success) reply(command.sourceNodeId, "Готово", "Углеводы записаны: ${request.carbs} г.\nПодтверждено инсулина: $delivered Е.")
                else reply(command.sourceNodeId, "Проверьте телефон", "$comment\nПроверьте историю подачи перед повторением.")
            }
        } else if (request.kind == "ACTIVITY") {
            val old = requireNotNull(preview.plan)
            val current = activityPlan(request)
            require(current.requiredCarbs == old.requiredCarbs && current.carbsWithinMinutes == old.carbsWithinMinutes) {
                "Прогноз изменился. Снова выберите нагрузку для нового подтверждения."
            }
            recordActivity(command.sourceNodeId, request, current)
        } else {
            val note = if (request.kind == "CARBS") "С часов AIMI_CARB_TYPE type=${request.carbType}" else "С часов"
            enqueueTreatment(command.sourceNodeId, request.insulin, request.carbs, dateUtil.now(), note)
        }
    }

    private fun validate(request: EventData.WatchControlRequest) {
        require(preferences.get(BooleanKey.WearControl)) { "Управление с часов отключено в настройках AAPS на телефоне." }
        require(request.requestId.length in 1..80 && request.kind in setOf("CARBS", "INSULIN", "ACTIVITY", "MEAL")) { "Неизвестная команда часов." }
        require(request.insulin.isFinite() && request.insulin >= 0 && request.carbs >= 0) { "Некорректное количество." }
        when (request.kind) {
            "INSULIN" -> {
                require(request.insulin > 0 && request.carbs == 0) { "Укажите количество инсулина." }
                require(abs(constraints.applyBolusConstraints(ConstraintObject(request.insulin, logger)).value() - request.insulin) < 0.000001) { "Доза превышает допустимый предел. Измените ее и подтвердите заново." }
                require(activePlugin.activePump.isInitialized() && !loop.runningMode.isSuspended()) { "Помпа сейчас недоступна." }
            }
            "CARBS", "MEAL" -> {
                require(request.carbs > 0 && request.insulin == 0.0) { "Укажите количество углеводов." }
                require(request.carbType in setOf("fast", "balanced", "slow")) { "Неизвестный тип еды." }
                require(constraints.applyCarbsConstraints(ConstraintObject(request.carbs, logger)).value() == request.carbs) { "Количество углеводов превышает установленный предел." }
            }
            "ACTIVITY" -> {
                require(request.insulin == 0.0 && request.carbs == 0) { "Нагрузка не может содержать дозу инсулина." }
                require(request.mode in setOf("WALK", "SPORT") && request.duration in setOf(30, 50, 90) &&
                    request.startOffset in setOf(0, 20, 30, 50, 60) && request.carbType in setOf("fast", "balanced")) { "Некорректный режим нагрузки." }
            }
        }
    }

    private fun foodLabel(type: String) = when (type) { "fast" -> "Быстрая еда"; "slow" -> "Медленная еда"; else -> "Обычная еда" }

    private fun activityPlan(request: EventData.WatchControlRequest): ActivityPlanCalculator.Plan {
        val profile = profileFunction.getProfile() ?: error("Нет активного профиля.")
        val result = loop.lastRun?.constraintsProcessed ?: error("Нет актуального расчета. Дождитесь обновления телефона.")
        require(dateUtil.now() - result.date in 0..360_000L) { "Расчет устарел. Дождитесь обновления телефона." }
        val glucose = glucoseStatusProvider.glucoseStatusData ?: error("Нет свежей глюкозы.")
        require(dateUtil.now() - glucose.date in 0..360_000L) { "Глюкоза устарела. Проверьте телефон." }
        val inputs = BolusInputSnapshot.from(result.iobData?.firstOrNull())
        require(inputs != null && inputs.validationError(persistence, dateUtil.now()) == null &&
            (persistence.getNewestCarbs()?.dateCreated ?: 0) <= result.date && mealAssist.lastTreatmentAcceptedAt() <= result.date) {
            "После еды или инсулина нужен новый расчет. Дождитесь обновления телефона."
        }
        val forecast = result.predictions()?.AIMI_FINAL?.map { it.toDouble() }.orEmpty()
        require(forecast.isNotEmpty()) { "Прогноз пока недоступен." }
        val existing = persistence.getTherapyEventDataFromTime(dateUtil.now() - T.hours(12).msecs(), TE.Type.EXERCISE, true)
        require(existing.none { it.isValid &&
            (ActivityPlanCalculator.endWithTail(it.timestamp, it.duration, it.note) ?: 0) > dateUtil.now() }) {
            "Нагрузка уже активна, запланирована или действует ее хвост. Проверьте ее на телефоне перед новой."
        }
        val plan = ActivityPlanCalculator.build(
            request.mode, request.duration, request.startOffset, request.carbType,
            profile.getBasal(), profile.getIsfMgdl("Activity v2"), profile.getIc(), profile.getTargetMgdl(), profile.getTargetLowMgdl(),
            profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewLowMark), profileFunction.getUnits()),
            glucose.glucose, forecast,
            profile.getIsfMgdl("Activity v2 carbs")
        )
        require(constraints.applyCarbsConstraints(ConstraintObject(plan.requiredCarbs, logger)).value() == plan.requiredCarbs) { "Требуется проверить углеводы для нагрузки на телефоне." }
        return plan
    }

    private fun recordActivity(node: String, request: EventData.WatchControlRequest, plan: ActivityPlanCalculator.Plan) {
        val now = dateUtil.now()
        val start = now + T.mins(plan.startOffsetMinutes.toLong()).msecs()
        val note = ActivityPlanCalculator.note(plan.mode, plan.effectPercent, plan.startOffsetMinutes,
            plan.durationMinutes, plan.tailMinutes, plan.requiredCarbs, plan.carbType)
        persistence.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(timestamp = start - start % 1000, type = TE.Type.EXERCISE, glucoseUnit = profileFunction.getUnits(),
                duration = T.mins(plan.durationMinutes.toLong()).msecs(),
                exerciseDuty = if (plan.mode == "SPORT") TE.ExerciseDuty.MIDDLE else TE.ExerciseDuty.LIGHT,
                note = note, enteredBy = "AndroidAPS Wear"),
            action = Action.CAREPORTAL, source = Sources.Wear, note = note,
            listValues = listOf(ValueWithUnit.Timestamp(start), ValueWithUnit.Minute(plan.durationMinutes))
        ).blockingGet()
        rxBus.send(EventRefreshOverview("Watch activity recorded", now = true))
        if (plan.requiredCarbs > 0) {
            enqueueTreatment(node, 0.0, plan.requiredCarbs, now + T.mins(plan.carbsWithinMinutes.toLong()).msecs(),
                "AIMI_ACTIVITY_V2_CARBS type=${plan.carbType} for=${plan.mode} activity", "Нагрузка и углеводы записаны")
        } else reply(node, "Готово", "Нагрузка записана на телефоне.")
        loop.invoke("Watch activity recorded", allowNotification = false)
        logger.debug(LTag.WEAR, "Watch activity recorded request=${request.requestId}")
    }

    private fun enqueueTreatment(node: String, insulin: Double, carbs: Int, at: Long, note: String, success: String? = null) {
        val info = DetailedBolusInfo().also {
            it.insulin = insulin
            it.carbs = carbs.toDouble()
            it.bolusType = BS.Type.NORMAL
            it.carbsTimestamp = at
            it.notes = note
        }
        uel.log(action = if (insulin > 0) Action.BOLUS else Action.CARBS, source = Sources.Wear,
            listValues = listOfNotNull(ValueWithUnit.Insulin(insulin).takeIf { insulin > 0 }, ValueWithUnit.Gram(carbs).takeIf { carbs > 0 }))
        val queued = commandQueue.bolus(info, object : Callback() {
            override fun run() {
                if (result.success && carbs > 0) {
                    rxBus.send(EventRefreshOverview("Watch carbs recorded", now = true))
                    loop.invoke("Watch carbs recorded", allowNotification = false)
                }
                if (result.success) reply(node, "Готово", success ?: if (insulin > 0) "Помпа подтвердила выполнение: ${result.bolusDelivered} Е." else "Углеводы записаны: $carbs г.")
                else reply(node, "Проверьте телефон", "${result.comment}\nНе повторяйте инсулин, пока не проверите историю подачи. Если нагрузка уже записана, она остается активной.")
            }
        })
        if (!queued) reply(node, "Проверьте телефон", "Запрос не поставлен в очередь. Проверьте историю, прежде чем повторять ввод.")
    }

    private fun reply(node: String, title: String, message: String, command: EventData? = null) {
        rxBus.send(EventMobileToWear(EventData.ConfirmAction(title, message, command).also { it.sourceNodeId = node }))
    }

    private inline fun safely(node: String, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            logger.error(LTag.WEAR, "Watch control rejected", e)
            reply(node, "Не выполнено", e.message ?: "Ошибка обработки. Проверьте телефон перед повторением.")
        }
    }
}
