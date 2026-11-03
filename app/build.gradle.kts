import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cz.slush.adbanywhere"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.slush.adbanywhere"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["debugReceiverEnabled"] = "false"
    }

    buildTypes {
        debug {
            manifestPlaceholders["debugReceiverEnabled"] = "true"
        }
        release {
            manifestPlaceholders["debugReceiverEnabled"] = "false"
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
 * The daemon lives in its own repository (a minimal AOSP adb fork) and is cloned
 * next to this project. The build cross-compiles it and copies the per-ABI binaries
 * into assets. If the clone is missing, the committed binaries are used as-is.
 */
val defaultAdbdDir = rootProject.projectDir.parentFile?.resolve("adb-anywhere-adbd")
    ?: rootProject.projectDir.resolve("../adb-anywhere-adbd")
val adbdDir = providers.gradleProperty("adbdDir").orElse(defaultAdbdDir.absolutePath)
val syncAdbd = tasks.register("syncAdbd") {
    val fork = file(adbdDir.get())
    val assets = file("src/main/assets/adbd")
    doLast {
        if (!fork.resolve("tools/build-android.sh").exists()) {
            logger.warn("adb-anywhere-adbd not found at ${fork}; using committed adbd assets")
            return@doLast
        }
        val process = ProcessBuilder("sh", "-c", "cd '${fork.absolutePath}' && ./tools/build-android.sh")
            .inheritIO()
            .start()
        check(process.waitFor() == 0) { "adb-anywhere-adbd build failed" }
        copy {
            from(fork.resolve("out")) { include("*/adbd") }
            into(assets)
        }
        logger.lifecycle("adbd synced from ${fork}")
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
