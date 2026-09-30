plugins {
    alias(libs.plugins.android.library)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
}

// Watch-side decision logic: what may run, what must not run twice, and what has to be read
// back from the pump before anything else is allowed. Kept free of Android services so it can
// be tested without a device.
android {
    namespace = "app.aaps.pump.combowatch.executor"
}

dependencies {
    implementation(project(":pump:combowatch-protocol"))
}
