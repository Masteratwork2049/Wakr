import java.util.Properties

plugins {
    // No `kotlin.android`: AGP 9 has built-in Kotlin support and applying it is a hard error.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wakr.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wakr.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.1.10"
    }

    // Configure lazily enough for debug/tests/lint to work in contributor checkouts.
    // Packaging a production release is guarded explicitly; there is never a debug-key fallback.
    val keystoreProps = Properties()
    val keystorePropsFile = rootProject.file("keystore.properties")
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use(keystoreProps::load)
    fun signingValue(name: String) = keystoreProps.getProperty(name) ?: providers.gradleProperty(name).orNull
    val releaseStore = signingValue("clockStoreFile")?.takeIf { it.isNotBlank() }?.let(rootProject::file)
    val releaseStorePassword = signingValue("clockStorePassword")
    val releaseAlias = signingValue("clockKeyAlias")
    val releaseKeyPassword = signingValue("clockKeyPassword")
    val productionSigningReady = releaseStore?.isFile == true &&
        listOf(releaseStorePassword, releaseAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
    signingConfigs {
        create("release") {
            storeFile = releaseStore
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
        }
    }
    val verifyProductionSigning = tasks.register("verifyProductionSigning") {
        doLast {
            check(productionSigningReady) {
                "Production release signing requires clockStoreFile, clockStorePassword, clockKeyAlias and clockKeyPassword. No debug signing fallback is permitted."
            }
        }
    }
    tasks.matching { it.name in setOf("validateSigningRelease", "packageRelease", "signReleaseBundle", "packageReleaseBundle") }
        .configureEach { dependsOn(verifyProductionSigning) }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                // Must be the -optimize variant; plain proguard-android.txt carries -dontoptimize,
                // which is a hard error on AGP 9.
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Sign optimized previews with the debug key for local testing.
        create("preview") {
            initWith(getByName("release"))
            versionNameSuffix = "-preview.2"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        debug {
            versionNameSuffix = "-debug"
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

    androidResources {
        localeFilters += listOf("en")
    }

    // No accounts, no telemetry, no ad IDs. The dependency manifest stays out of the APK too.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "/META-INF/com.android.tools/**",
                "/kotlin/**",
                "/DebugProbesKt.bin",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        // [M3E] Most expressive components are still opt-in even though MaterialExpressiveTheme
        // itself is not. Opting in centrally keeps the annotation noise out of every call site.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.ui.text.ExperimentalTextApi",
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.graphics.shapes)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.android.json.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.androidx.test.core)
}
