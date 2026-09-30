plugins {
    alias(libs.plugins.android.library)
    id("kotlin-android")
    id("kotlin-kapt")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

// The phone half of the watch-backed pump driver. It never opens a Bluetooth connection to a
// pump: everything it knows comes from the watch, so it does not depend on comboctl at all.
android {
    namespace = "app.aaps.pump.combowatch"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":pump:combowatch-protocol"))

    implementation(libs.com.google.android.gms.playservices.wearable)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    kapt(libs.com.google.dagger.compiler)
    kapt(libs.com.google.dagger.android.processor)
}
