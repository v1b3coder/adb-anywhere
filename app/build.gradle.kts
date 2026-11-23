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

/*
 * One APK per ABI family instead of every ABI in every APK. The phone builds `arm`,
 * the e2e emulator builds `x86`, and the release ships `arm`: every Play-certified
 * Android 12 device is 64-bit, so the other two ABIs were 5.5 MB no phone can run.
 * `universal` stays as the one APK that installs anywhere, for local use. A flavor
 * carries only its own adbd binary and native libraries.
 */
val flavorAbis = mapOf(
    "arm" to listOf("arm64-v8a"),
    "x86" to listOf("x86_64"),
    "universal" to listOf("arm64-v8a", "armeabi-v7a", "x86_64"),
)
val adbdBinDir = file("adbd-bin")
val adbdFlavorAssets: Map<String, File> = flavorAbis.keys.associateWith { file("src/$it/assets/adbd") }

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

    flavorDimensions += "abi"
    productFlavors {
        flavorAbis.forEach { (name, abis) ->
            create(name) {
                dimension = "abi"
                // universal is left unfiltered: it keeps every ABI the dependencies
                // and the adbd assets provide.
                if (name != "universal") {
                    ndk { abiFilters.addAll(abis) }
                }
            }
        }
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
            // Still a debug build as far as the app is concerned: BuildConfig.DEBUG
            // keeps the debug receiver, the VPN override and `run-as` that the e2e
            // scripts need. Only the code size is optimized, see below.
            manifestPlaceholders["debugReceiverEnabled"] = "true"
        }
        release {
            manifestPlaceholders["debugReceiverEnabled"] = "false"
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    /*
     * Every variant ships an optimized APK: the debug one is the release build
     * signed with the debug key, so it installs over an existing debug install and
     * the whole build still fits through the relay. Unshrunk it was 80 MB, almost
     * all dex: material-icons-extended, CameraX, BouncyCastle and Conscrypt were
     * packaged whole and none of the unused code was ever removed.
     */
    buildTypes.configureEach {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro",
        )
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
        jniLibs {
            // Deflate the native libraries and extract them at install instead of
            // loading them from the APK. Extraction costs the same app storage as
            // keeping them uncompressed, and the download is 1.4 MB smaller.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            // PICNIC is a post-quantum signature this app never asks bcpkix for.
            // Its lookup tables are 1.2 MB of root-level resources, which
            // shrinkResources does not see.
            excludes += "org/bouncycastle/pqc/crypto/picnic/*"
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
 * cross-compiles it and copies the per-ABI binaries into assets, which are not
 * committed: a local build compiles them with the local NDK, CI builds and caches
 * them, and the release injects the artifact built for that commit. Pass
 * -PadbdSkipBuild=true to keep the assets untouched.
 */
val adbdDir = providers.gradleProperty("adbdDir")
    .map { file(it) }
    .orElse(rootProject.file("adbd"))
val skipAdbdBuild = providers.gradleProperty("adbdSkipBuild")
    .map(String::toBoolean)
    .orElse(false)
val syncAdbd = tasks.register("syncAdbd") {
    val subproject = adbdDir.get()
    // Declared inputs and outputs, so an unchanged tree with the binaries
    // already in place makes this a no-op instead of re-copying the vendor tree,
    // re-applying the patches and re-running cmake on every build. They are only
    // declared when the tree is there, so a checkout without it still configures.
    if (subproject.resolve("build.sh").exists()) {
        inputs.files(
            subproject.resolve("CMakeLists.txt"),
            subproject.resolve("build.sh"),
        )
        inputs.dir(subproject.resolve("patches"))
        inputs.dir(subproject.resolve("src"))
        inputs.dir(subproject.resolve("stubs"))
        inputs.dir(subproject.resolve("tests"))
        inputs.dir(subproject.resolve("vendor"))
    }
    outputs.dirs(*adbdFlavorAssets.values.toTypedArray())
    doLast {
        if (!skipAdbdBuild.get()) {
            check(subproject.resolve("build.sh").exists()) {
                "adbd/ not found at ${subproject}; pass -PadbdSkipBuild=true to use adbd-bin/ as-is"
            }
            val process = ProcessBuilder("sh", "-c", "cd '${subproject.absolutePath}' && ./build.sh")
                .inheritIO()
                .start()
            check(process.waitFor() == 0) { "adbd build failed" }
            copy {
                from(subproject.resolve("build/out")) { include("*/adbd") }
                into(adbdBinDir)
            }
            logger.lifecycle("adbd built into ${adbdBinDir.name}/")
        }
        flavorAbis.forEach { (flavor, abis) ->
            val target = adbdFlavorAssets.getValue(flavor)
            delete(target)
            abis.forEach { abi ->
                val binary = adbdBinDir.resolve("$abi/adbd")
                check(binary.exists()) {
                    "adbd-bin/$abi/adbd is missing; build the daemon or drop the artifact there"
                }
                copy {
                    from(binary)
                    into(target.resolve(abi))
                }
            }
        }
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
