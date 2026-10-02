import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
}

// What the watch does for glucose when the phone is away: a forecast and a choice of basal.
//
// None of the algorithm lives here as a copy. The forecast, the insulin and carbohydrate models
// and the basal safeguards are compiled from the phone algorithm's own source files, collected at
// build time by the task below. There is one source for them, so the watch cannot quietly keep an
// older idea of the rules than the phone has: if one of those files changes in a way this module
// cannot follow, the build fails here.
//
// This module must never be put into the phone app together with :plugins:aps - the same classes
// would be there twice. The phone needs none of it: it only sends the watch a snapshot.
android {
    namespace = "app.aaps.pump.combowatch.regulation"
}

val sharedAlgorithmDir = layout.buildDirectory.dir("generated/sharedAlgorithm/kotlin")

val sharedAlgorithmSources = tasks.register<Sync>("sharedAlgorithmSources") {
    from(rootProject.file("plugins/aps/src/main/kotlin")) {
        include("app/aaps/plugins/aps/openAPSAIMI/pkpd/AdvancedPredictionEngine.kt")
        include("app/aaps/plugins/aps/openAPSAIMI/pkpd/CarbAbsorptionModel.kt")
        include("app/aaps/plugins/aps/openAPSAIMI/pkpd/ForecastCarbImpact.kt")
        include("app/aaps/plugins/aps/openAPSAIMI/pkpd/PlannedInsulinAction.kt")
        include("app/aaps/plugins/aps/openAPSAIMI/safety/GuardedBasalSelector.kt")
        include("app/aaps/plugins/aps/openAPSAIMI/safety/HypoTools.kt")
        include("app/aaps/plugins/aps/openAPSAIMI/basal/BasalPlanner.kt")
        include("app/aaps/plugins/aps/openAPSAIMI/basal/BasalHistoryUtils.kt")
        include("app/aaps/plugins/aps/openAPSAIMI/AIMIAdaptiveBasal.kt")
        include("app/aaps/plugins/aps/openAPSAIMI/model/Models.kt")
        include("app/aaps/plugins/aps/openAPS/DeltaCalculator.kt")
    }
    from(rootProject.file("core/objects/src/main/kotlin")) {
        include("app/aaps/core/objects/aps/MealAbsorptionSchedule.kt")
    }
    into(sharedAlgorithmDir)
}

android.sourceSets.getByName("main").kotlin.srcDir(sharedAlgorithmDir)

tasks.withType<KotlinCompile>().configureEach { dependsOn(sharedAlgorithmSources) }

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":pump:combowatch-protocol"))

    // Only the annotations the shared files carry; nothing is injected in this module.
    implementation(libs.com.google.dagger.android)
}
