import com.android.build.api.artifact.SingleArtifact
import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is optional: provide keystore.properties (see README) or the WAKENUDGE_* env vars (CI).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

// CI sets WAKENUDGE_REQUIRE_SIGNING=true when it builds a release, so a missing or incomplete key stops the build instead
// of quietly producing an unsigned (or debug-signed) artifact.
val requireSigning = providers.environmentVariable("WAKENUDGE_REQUIRE_SIGNING").orNull == "true"

// --- Versioning (see docs/RELEASING.md and scripts/compute-version.sh, which implements the same rules) -------------
// versionName = <major>.<minor>.<N> and versionCode = N. major.minor come from version.properties; N is the number of
// commits reachable from HEAD, which only ever grows on `main` (what Google Play needs from a versionCode). CI computes
// both once and passes them in as WAKENUDGE_VERSION_CODE / WAKENUDGE_VERSION_NAME, so the build and the release agree.
// Precedence: Gradle property (-Pwakenudge.versionCode / -Pwakenudge.versionName), then the env var, then git. Without
// git (a source tarball) the code is 1 and the name <major>.<minor>.0.
val versionProps = Properties().apply {
    val text = providers.fileContents(rootProject.layout.projectDirectory.file("version.properties")).asText
    load(StringReader(checkNotNull(text.orNull) { "version.properties is missing from the repository root" }))
}
fun versionOverride(gradleProperty: String, envVar: String): String? =
    providers.gradleProperty(gradleProperty).orElse(providers.environmentVariable(envVar)).orNull?.trim()?.takeIf { it.isNotEmpty() }

val versionMajor = versionProps.getProperty("major")?.trim()?.toIntOrNull()
val versionMinor = versionProps.getProperty("minor")?.trim()?.toIntOrNull()
check(versionMajor != null && versionMinor != null) { "version.properties needs numeric major= and minor= entries" }

// Only asked for when no override is given. providers.exec keeps this configuration-cache safe: the cache re-runs git
// and is discarded when the commit count changes.
val gitCommitCount: Int? by lazy {
    runCatching {
        providers.exec {
            workingDir = rootDir
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toInt()
    }.getOrNull()?.takeIf { it > 0 }
}

val maxVersionCode = 2_100_000_000 // Google Play rejects anything above this
val buildNumber: Int? = versionOverride("wakenudge.versionCode", "WAKENUDGE_VERSION_CODE")?.let { raw ->
    raw.takeIf { Regex("[1-9][0-9]{0,9}").matches(it) }?.toLongOrNull()?.takeIf { it <= maxVersionCode }?.toInt()
        ?: throw GradleException("versionCode '$raw' must be an integer between 1 and $maxVersionCode")
} ?: gitCommitCount
val appVersionCode: Int = buildNumber ?: 1
val appVersionName: String = versionOverride("wakenudge.versionName", "WAKENUDGE_VERSION_NAME")?.also {
    if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.]+)?").matches(it)) {
        throw GradleException("versionName '$it' must look like 1.2.3")
    }
} ?: "$versionMajor.$versionMinor.${buildNumber ?: 0}"

tasks.register("printVersion") {
    group = "help"
    description = "Prints the versionName and versionCode this build uses."
    val line = "versionName=$appVersionName versionCode=$appVersionCode"
    doLast { println(line) }
}

/**
 * Fails the build if the merged manifest of a variant requests network permissions. WakeNudge is offline-only; the one
 * exception is the `github` flavour (the APK on GitHub Releases), which may hold INTERNET to look for a newer release.
 * Nothing but INTERNET is ever allowed, and the Play flavour must stay completely offline.
 */
abstract class VerifyOfflineManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowInternet: Property<Boolean>

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
        val forbidden = buildSet {
            if (!allowInternet.get()) add("android.permission.INTERNET")
            add("android.permission.ACCESS_NETWORK_STATE")
            add("android.permission.ACCESS_WIFI_STATE")
        }
        val offending = declared.intersect(forbidden)
        check(offending.isEmpty()) { "Network permissions are not allowed in this WakeNudge variant but the merged manifest declares: $offending" }
    }
}

android {
    namespace = "com.riyadm.wakenudge"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.riyadm.wakenudge"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "com.riyadm.wakenudge.HiltTestRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "REPO_URL", "\"https://github.com/riyadmondol2006/WakeNudge\"")
        buildConfigField("String", "SITE_URL", "\"https://wakenudge.site\"")
    }

    // Two ways to ship the same app. `play` is the Play Store bundle: no network permission, no update code.
    // `github` is the APK on GitHub Releases: it may check GitHub for a newer release (the user can switch that off).
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATE_CHECKS", "false")
        }
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATE_CHECKS", "true")
        }
    }

    signingConfigs {
        val storePath = signingValue("storeFile", "WAKENUDGE_KEYSTORE_PATH")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "WAKENUDGE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "WAKENUDGE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "WAKENUDGE_KEY_PASSWORD")
            }
        }
        if (requireSigning) {
            val release = findByName("release")
            check(
                release != null && release.storeFile?.isFile == true &&
                    !release.storePassword.isNullOrEmpty() && !release.keyAlias.isNullOrEmpty() && !release.keyPassword.isNullOrEmpty(),
            ) { "WAKENUDGE_REQUIRE_SIGNING is set but the release signing key is missing or incomplete (keystore file, store password, key alias and key password are all needed)" }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "WakeNudge Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "WakeNudge")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        // Dependencies are pinned in the version catalog and bumped deliberately.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }

    sourceSets {
        // Room exports schemas here so migrations can be verified.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

androidComponents {
    onVariants { variant ->
        val capName = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyOfflineManifestTask>("verifyOfflineManifest$capName") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowInternet.set(variant.flavorName == "github")
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlin.time.ExperimentalTime",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material3.adaptive.nav.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.graphics.shapes)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
