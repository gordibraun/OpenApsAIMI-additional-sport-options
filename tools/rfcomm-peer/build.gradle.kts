plugins { id("com.android.application") }

android {
    namespace = "app.aaps.rfcommpeer"
    compileSdk = Versions.compileSdk
    defaultConfig {
        applicationId = "app.aaps.rfcommpeer"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2-peer-spp"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

dependencies { testImplementation("junit:junit:4.13.2") }
