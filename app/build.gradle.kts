import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * Release inputs. The version comes from the release tag through -P; a plain checkout
 * keeps the defaults. Signing reads signing/keystore.properties locally and the same
 * values from RELEASE_* environment variables in CI. Without either, the release build
 * stays unsigned and a plain checkout still assembles.
 */
val signingProperties = Properties().apply {
    val file = rootProject.file("signing/keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(key: String, env: String): String? =
    signingProperties.getProperty(key) ?: System.getenv(env)

val releaseKeystore = signingValue("storeFile", "RELEASE_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")

android {
    namespace = "cz.slush.adbanywhere"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.slush.adbanywhere"
        minSdk = 31
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("versionName").getOrElse("0.1.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["debugReceiverEnabled"] = "false"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["debugReceiverEnabled"] = "true"
        }
        release {
            manifestPlaceholders["debugReceiverEnabled"] = "false"
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    sourceSets {
        getByName("main").assets.srcDir("src/main/assets")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// libadb-android pulls bcprov-jdk15to18, which duplicates the classes of the
// bcprov-jdk18on artifact required by bcpkix-jdk18on.
configurations.configureEach {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
}

/*
 * The daemon is vendored under adbd/ as a standalone AOSP adbd build. The task
 * cross-compiles it and copies the per-ABI binaries into assets. If the subproject
 * is missing, the committed binaries are used as-is.
 */
val adbdDir = providers.gradleProperty("adbdDir")
    .map { file(it) }
    .orElse(rootProject.file("adbd"))
val syncAdbd = tasks.register("syncAdbd") {
    val subproject = adbdDir.get()
    val assets = file("src/main/assets/adbd")
    doLast {
        if (!subproject.resolve("build.sh").exists()) {
            logger.warn("adbd/ not found at ${subproject}; using committed adbd assets")
            return@doLast
        }
        val process = ProcessBuilder("sh", "-c", "cd '${subproject.absolutePath}' && ./build.sh")
            .inheritIO()
            .start()
        check(process.waitFor() == 0) { "adbd build failed" }
        copy {
            from(subproject.resolve("build/out")) { include("*/adbd") }
            into(assets)
        }
        logger.lifecycle("adbd synced from ${subproject}")
    }
}

tasks.named("preBuild").configure { dependsOn(syncAdbd) }

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("com.google.zxing:core:3.5.3")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    testImplementation("org.json:json:20250517")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
