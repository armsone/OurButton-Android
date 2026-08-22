plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.armsone.button"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.armsone.button"
        minSdk = 26
        targetSdk = 37
        versionCode = 337417
        versionName = "2.0.0"
        buildConfigField("String", "BUILD_NUMBER", "\"202608230737\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Firebase client identifiers are supplied outside source control (for example from
        // ~/.gradle/gradle.properties). Empty values keep local builds and Bluetooth mode working.
        manifestPlaceholders["buttonFirebaseApplicationId"] =
            providers.gradleProperty("BUTTON_FIREBASE_APPLICATION_ID").orElse("").get()
        manifestPlaceholders["buttonFirebaseProjectId"] =
            providers.gradleProperty("BUTTON_FIREBASE_PROJECT_ID").orElse("").get()
        manifestPlaceholders["buttonFirebaseApiKey"] =
            providers.gradleProperty("BUTTON_FIREBASE_API_KEY").orElse("").get()
        manifestPlaceholders["buttonFirebaseSenderId"] =
            providers.gradleProperty("BUTTON_FIREBASE_SENDER_ID").orElse("").get()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    // CameraX still requests Fragment 1.1 transitively; keep Activity Result handling on the
    // current stable Fragment runtime even though the app UI itself is Compose-only.
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val verifyReleaseFirebaseConfiguration by tasks.registering {
    doLast {
        val requiredFirebaseProperties = listOf(
            "BUTTON_FIREBASE_APPLICATION_ID",
            "BUTTON_FIREBASE_PROJECT_ID",
            "BUTTON_FIREBASE_API_KEY",
            "BUTTON_FIREBASE_SENDER_ID",
        )
        val missing = requiredFirebaseProperties.filter {
            providers.gradleProperty(it).orNull.isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release builds require Firebase configuration: ${missing.joinToString()}",
            )
        }
    }
}

tasks.matching { it.name == "processReleaseMainManifest" }.configureEach {
    dependsOn(verifyReleaseFirebaseConfiguration)
}
