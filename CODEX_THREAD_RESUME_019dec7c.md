# Resume from broken Codex thread 019dec7c

This file transfers the useful context from the old Codex thread:
`019dec7c-c08c-77e0-9bbd-2f6a10e03dce`.

The old thread should not be resumed directly. Its rollout is about 344 MB and
pre-sampling compaction failed repeatedly at `/responses/compact`. Use this file
as the continuation context instead.

## Project State

- Workspace: `/Users/alexeydedeshko/StudioProjects/OpenApsAIMI-additional-sport-options`
- Branch: `case-review-mvp`
- Current HEAD when this resume was made: `5c38e4c32b2fa797c812fc94f1bac1eb58563292`
- Package used on phone: `info.nightscout.androidaps`
- Last known device in the old thread: `RRCX807YMBY`
- Last debug APK install verified in the old thread: `2026-06-02 19:35:41`
- Current ADB in this thread showed no connected devices, so reconnect/enable ADB before live checks.

The working tree is dirty with AIMI changes. Do not reset or revert unrelated
changes. The important currently changed files include:

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/smb/SmbInstructionExecutor.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/smb/SmbDampingUsecase.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/activity/ActivityContext.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/activity/ActivityManager.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/meal/AimiMealAssistImpl.kt`
- `core/interfaces/src/main/kotlin/app/aaps/core/interfaces/aps/AimiMealAssist.kt`
- `core/objects/src/main/kotlin/app/aaps/core/objects/wizard/BolusWizard.kt`
- `ui/src/main/kotlin/app/aaps/ui/dialogs/WizardDialog.kt`
- `ui/src/main/res/layout/dialog_wizard.xml`
- `ui/src/main/res/values/strings.xml`
- `workflow/src/main/kotlin/app/aaps/workflow/PreparePredictionsWorker.kt`
- `implementation/src/main/kotlin/app/aaps/implementation/iob/AutosensDataObject.kt`
- overview/graph/coloring files: `OverviewFragment.kt`, `GraphData.kt`,
  `GlucoseValueDataPoint.kt`, `SourceSensor.kt`, `colors.xml`,
  `colors-night.xml`

Useful build checks used repeatedly:

```bash
./gradlew :plugins:aps:compileFullDebugKotlin
./gradlew :implementation:compileFullDebugKotlin
./gradlew :ui:compileFullDebugKotlin
./gradlew :app:assembleFullDebug
adb -s RRCX807YMBY install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

Some old unit-test tasks in `plugins:aps` failed before reaching new tests
because older tests such as `LoopPluginTest`, `GlucoseStatusCalculatorAimiTest`,
and `BasalDecisionEngineTest` are not currently synchronized. Treat full APK
build plus targeted compilation/live log verification as the practical baseline
unless tests are repaired first.

## Safety And Working Rules

- This is insulin dosing logic. Do not change dosing behavior from intuition.
  First separate display forecast, calculation forecast, SMB/TBR decision, pump
  enactment, and manual boluses.
- Do not manually trigger Loop just to force a test if that could cause a real
  dosing action. Prefer log inspection and natural APS cycles.
- Avoid dumping full `logcat` into context. Save full logs to file, then extract
  filtered timelines with `rg`.
- Old raw data exports were huge. Do not restart broad collectors blindly.
  `aaps-data-exports` previously reached about 442 GB and was cleaned.
- Existing monitor LaunchAgents were stopped/disabled in the old thread:
  `com.aaps.phone-collector` and `com.aaps.effectiveness-monitor`.

Useful latest artifacts from the old thread:

- Last saved full logcat: `/Users/alexeydedeshko/Downloads/aaps-debug/logs/logcat-current-20260603-105842.txt`
- Last overinsulin screenshot: `/Users/alexeydedeshko/Downloads/aaps-debug/aaps-overinsulin-check-20260603-105726.png`
- Previous stale-hide screenshot: `/Users/alexeydedeshko/Downloads/aaps-debug/aaps-after-stale-hide-fix-20260602-193603.png`
- Forecast check screenshot: `/Users/alexeydedeshko/Downloads/aaps-debug/aaps-check-forecast-20260602-191526.png`

## Main AIMI Model Decisions

### Forecast lines

- There should be one honest AIMI display line.
- Before final dosing decision: `AIMI_BEFORE_DECISION`, orange/pending.
- After final SMB/TBR decision: `AIMI_FINAL`, blue/final.
- If a forecast is stale because of recent carbs/actions/profile/targets, keep
  it orange/pending rather than showing it as fresh.
- If stale by actual BG or missing meal context, hide it; do not draw an old
  line as meaningful.
- A previous UI bug existed in `PreparePredictionsWorker`: it detected stale BG
  and logged `Hiding stale AIMI prediction by BG`, but then forced
  `hidePredictions = false`. This has been changed to hide stale-by-BG and
  missing-context forecasts.

### Final forecast after SMB/TBR

- A serious bug was found on 2026-06-02: `AIMI_BEFORE_DECISION` looked sane
  around `~125`, then `AIMI_FINAL` after `SMB=0.54` and `TBR=2.52` jumped to
  about `~364`.
- The sign of SMB/TBR drop in `AdvancedPredictionEngine` looked correct.
- Root cause identified: final recomputation passed `mealFactorApplied` from
  SMB execution, so the final line changed the food model in addition to adding
  the actual SMB/TBR decision.
- Fix in `DetermineBasalAIMI2.kt`: final forecast uses
  `forecastMealFactorApplied = 1.0`; the SMB meal factor is not applied again.
  The final graph should differ from pre-decision only by the actual decision
  effect.
- This fix was compiled and APK installed, but a fully fresh validating APS
  cycle was not observed because CGM was stale and pump/BT connection was flaky.

### Carb absorption and COB

- User requirement: new carbs must not wait for older carbs to finish. Each carb
  entry starts from its own timestamp and type.
- Carb types in Wizard:
  - `fast`: peak about 15 min, absorption/expiration about 45 min
  - `balanced`: peak about 50 min, absorption/expiration about 165 min
  - `slow`: peak about 80 min, absorption/expiration about 240 min
- Food type is stored in notes as `AIMI_CARB_TYPE type=fast|balanced|slow`.
- `AutosensDataObject.kt` was changed so typed carbs expire by their own model:
  `fast=45m`, `balanced=165m`, `slow=240m`. Untyped carbs keep old
  `AbsorptionMaxTime` behavior.
- A real bug was found: fast carbs `35 g` from `16:03` still had about `10.6 g`
  COB at `19:04`. After typed expiration fix, live logs showed
  `AAPS COB 39.2 -> phys 15.1`, and forecast no longer jumped toward `~300`.
- Important UI caveat: main screen may still show raw AAPS COB, while AIMI uses
  effective/physiological COB. Future improvement: display raw `AAPS COB` and
  `AIMI/effective COB` separately.

### AIMI meal assist and COB already handled

- Problem: protective/rescue carbs and already-dosed carbs were being confused.
  After user entered extra carbs, AIMI could mark all active carbs as already
  handled for COB insulin, causing `displayCob=20`, `handledCob=20`,
  `usedCob=0`.
- Fix:
  - `AimiMealEpisode` now has `cobHandledCarbs`.
  - `AimiMealAssistImpl.activate(...)` handles only protective carbs when bolus
    is `0`, and all carbs only when a recommended bolus was delivered.
  - `BolusWizard` computes `cobUsedForInsulin = cob - alreadyHandled`.
- Persistence fix:
  - Notes now store `AIMI_COB_HANDLED grams=...`.
  - `BolusWizard` restores handled COB from carb history so reinstall/restart
    does not suddenly treat all COB as new doseable food.
- Caveat: older carb records from before this patch do not contain
  `AIMI_COB_HANDLED`, so old started cases may restore imperfectly.

### Wizard UI behavior

- If carbs are entered but calculated bolus is zero because active insulin,
  current BG, and trend already cover it, the UI now shows a reason:
  `Углеводы учтены (...), но новый болюс сейчас 0...`
- `WizardDialog` has food type controls and stores selected food type.
- Wizard now considers future activity and can show:
  `Будущая нагрузка учтена: WALK через N мин` or
  `Будущая нагрузка включена... Жду пересчет прогноза`.
- Old issue fixed: timing hint must not recommend absurd future food timing like
  `+220 min` for current fast carbs/current bolus. It should search only a
  practical window for the current meal/bolus.

## Activity / Sport Logic

### Activity V2 events

- Activity events are stored as therapy events with note token `AIMI_ACTIVITY_V2`.
- They include tokens such as `mode=`, `duration=`, `tail=`, `effect=`,
  `startOffset=`.
- Expected tail table:
  - `WALK 30`: no tail
  - `WALK 50`: no tail
  - `WALK 90`: 30 min tail
  - `SPORT 30`: 60 min tail
  - `SPORT 50`: 120 min tail
  - `SPORT 90`: 180 min tail
- This was verified when `WALK 50` ended: no tail was expected because table
  says tail `0`. UI could be clearer by saying `без хвоста`.

### Activity coloring

- Forecast can be colored for waiting/active/tail activity phases.
- `PreparePredictionsWorker` now tracks `created`, `start`, `activeEnd`,
  `tailEnd` to color:
  - waiting before future activity
  - active activity
  - tail
- Yellow activity coloring must not override orange pending/stale state.

### Activity and new insulin

- `ActivityContext` now includes `newInsulinFactor`.
- `ActivityManager` computes overlap for active or planned activity and applies
  factor in range `0.55..1.0`.
- `BolusWizard` passes `activityNewInsulinFactor` and description into
  `AimiMealAssist`.
- `AimiMealAssistImpl` multiplies new bolus recommendation by this factor when
  planned/active activity is relevant.
- `DetermineBasalAIMI2` uses `activityContext.newInsulinFactor` for planned
  activity rather than only `currentPhase`.

### Activity and SMB/high BG

- Planned activity should reduce aggressive new insulin unless high BG is truly
  severe.
- `DetermineBasalAIMI2` computes:
  - `plannedActivityForNewInsulin`
  - `plannedActivityForecastFloor`
  - `plannedActivityAllowsHighBgInsulin`
- Aggressive high-BG insulin is allowed during planned activity only if:
  - no planned activity for new insulin, or
  - BG >= 220, or
  - forecast floor >= target + 45
- `Global Hyper Kicker` and `HighBG override` can be blocked/skipped when
  planned activity is already in forecast.
- `SmbInstructionExecutor` receives planned activity flags and treats planned
  activity as exercise context for safety/damping.
- `SmbDampingUsecase` bypass changed: high-BG rise bypass is not allowed when
  `exercise=true`.

## Overview, Wear, Widgets, And COB Freshness

- There was a UI freshness bug: carbs entered after last autosens/COB could
  exist in DB/calculation but not immediately appear in top-screen COB.
- Fix involved the COB display layer and overview refresh:
  - display includes carbs entered after last autosens calculation
  - overview/graph refresh after treatment history changes
  - stale AIMI result should not visually lower COB if DB/autosens sees more
    active carbs
- Relevant files from previous work include:
  - `plugins/main/src/main/kotlin/app/aaps/plugins/main/iob/iobCobCalculator/IobCobCalculatorPlugin.kt`
  - `core/objects/src/main/kotlin/app/aaps/core/objects/extensions/AimiCobInfoExtension.kt`
  - current diff also touches `OverviewFragment.kt`, `GraphData.kt`,
    `DataHandlerMobile.kt`, `Widget.kt` in related display paths.

## Effectiveness Monitor Context

Latest monitor report before exports were cleaned:

- File: `aaps-effectiveness-monitor/latest_report.md`
- Data period: `2026-05-15 12:58:36` to `2026-05-20 06:27:21`
- APS cycles: `1181`
- SMB cycles: `124`
- SMB followed by below-target/hypo: `33`
- Fresh SMB cycles in last 60 min: `559`
- Fresh SMB followed by below-target/hypo: `207`
- False high forecast at +60 min: `399`
- Fast rescue false high forecast at +60 min: `22`
- Median forecast error +60: `8.0`
- Median absolute AIMI error: +15 `12.0`, +30 `18.0`, +60 `25.0`
- Stale/delayed APS results: `1`

Monitor recommendations from that report:

- Check whether fast carb model became shorter.
- Check forecast recomputation after SMB.
- Strengthen accounting for fresh SMB because recent SMB correlated with later
  below-target/hypo.
- Strengthen freshness protection for APS results that finish after a newer CGM
  point but still carry SMB/TBR actions.
- Check falling-glucose layer; some falling cycles predicted too low at +15/+30.

Do not run broad full export collectors again without limiting output. They
previously created huge local data under `aaps-data-exports`.

## Last Open Problem Before Thread Broke

User reported on `2026-06-03`:

> very high insulin / negative forecast; check whether there is over-delivery.

User then clarified:

> I manually added insulin myself, maybe that was my error, but after that the
> system still gave SMB, so check everything.

Initial observations from old thread before compaction failure:

- UI screenshot showed BG about `145`, IOB about `8.32 U`, temp basal `0%`, and
  forecast dropping below low.
- Current carbs recommendation was only about `6 g`, so this looked like already
  accumulated IOB, not a need to add more insulin.
- Log around `10:53` showed APS already had `IOB 8.704`.
- Last bolus around `10:39`.
- Pump had TBR `0%` with about `26 min` remaining, so system was already trying
  to brake.
- `Loop` also logged `Pas de loop : autodrive=false; BG=145.0` in the current
  state, meaning current fresh auto-loop may not have been running at that
  moment.
- The pending diagnostic task was to build a compact timeline around
  `2026-06-03 10:20..10:59`:
  - manual boluses
  - SMB boluses
  - carbs
  - TBR/temp basal
  - APS forecasts before and after decisions
  - sport/activity context and tail
  - IOB/COB/BG/delta/minGuard/target

Use the saved log:

```bash
log=/Users/alexeydedeshko/Downloads/aaps-debug/logs/logcat-current-20260603-105842.txt
rg '^06-03 10:(2[0-9]|3[0-9]|4[0-9]|5[0-9]):' "$log" | rg 'USER ENTRY|Treatment|Bolus|SMB|CommandSMBBolus|TBR|temp basal|IOB data|Meal data|COB:|Glucose status:|Final decision-aware forecast|Итоговый прогноз|AIMI_FINAL|AIMI_BEFORE|Activity planned insulin|Global Hyper|HighBG|carbsReq|minGuard|eventualBG|target'
```

Need answer for the open problem:

1. Which insulin was manual vs system SMB/TBR?
2. After the manual bolus, did system still allow SMB?
3. If yes, what guard failed: fresh SMB accounting, activity/new-insulin factor,
   high-BG override, Global Hyper Kicker, stale forecast, or manual bolus not
   visible in IOB at decision time?
4. Was the negative forecast based on fresh `AIMI_FINAL` or a stale/pending line?
5. Did pump enactments match APS decisions?

## How To Continue In A New Codex Thread

Suggested first prompt:

```text
Прочитай CODEX_THREAD_RESUME_019dec7c.md и продолжи с последней открытой задачи:
проверить перелив/избыточный инсулин 2026-06-03 около 10:20-10:59.
Не трогай код сначала. Сначала собери компактную хронологию из сохраненного
logcat и отдели ручной болюс от SMB/TBR решения системы. Потом скажи, где
именно система после ручного болюса могла разрешить SMB, и только если причина
доказана логами, предложи узкую правку.
```

