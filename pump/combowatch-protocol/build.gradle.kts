plugins {
    alias(libs.plugins.android.library)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
}

// Shared by the phone plugin and the watch executor, so it must stay free of AAPS
// dependencies: anything pulled in here is pulled into the watch APK as well.
android {
    namespace = "app.aaps.pump.combowatch.protocol"
}
