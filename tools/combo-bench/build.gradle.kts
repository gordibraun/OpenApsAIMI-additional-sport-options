plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    buildFeatures { buildConfig = true }
    namespace = "app.aaps.combobench"
    compileSdk = Versions.compileSdk
    defaultConfig {
        applicationId = "app.aaps.combobench"
        minSdk = 31
        targetSdk = 34
        versionCode = 18
        versionName = "0.18-control-session"
        buildConfigField("boolean", "MANUAL_TARGET", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        missingDimensionStrategy("standard", "full")
    }
    compileOptions {
        sourceCompatibility = Versions.javaVersion
        targetCompatibility = Versions.javaVersion
    }
    flavorDimensions += "device"
    productFlavors {
        create("phone") { dimension = "device" }
        create("watch") { dimension = "device" }
        create("manual") {
            dimension = "device"
            applicationIdSuffix = ".manual"
            versionCode = 31
            versionName = "0.31-who-leads"
            buildConfigField("boolean", "MANUAL_TARGET", "true")
        }
    }
}

// This APK is a bench tool, never a release dosing application.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

dependencies {
    implementation(project(":pump:combov2:comboctl"))
    implementation(project(":pump:combowatch-protocol"))
    implementation(project(":pump:combowatch-executor"))
    implementation(project(":pump:combowatch-regulation"))
    implementation(libs.com.google.android.gms.playservices.wearable)
    // The controller's complications for the watch face (pump, link, forecast).
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests of the bench's JSON state files (the Android stub throws).
    testImplementation("org.json:json:20090211")
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.rules)
}
