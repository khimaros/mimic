plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// THE version -- the only line to edit for a release. no "v" here: this is the
// user-visible versionName (it reaches BuildConfig.VERSION_NAME and the `status`
// payload). the git tag that publishes it prepends one, `v0.6.0`, and the release
// workflow strips it before comparing. versionCode is derived below, so the tag,
// the name and the code cannot disagree.
val mimicVersionName = "0.6.0"

// MAJOR * 10^7 + MINOR * 10^5 + PATCH * 10^3. fixed field widths keep the code
// monotonic across every bump -- 0.9.0 (900000) < 0.10.0 (1000000) < 1.0.0
// (10000000) -- which android requires to accept an update; a plain digit
// concatenation breaks exactly there. the low three digits are reserved for a
// per-abi offset (+1 armeabi-v7a, +2 arm64-v8a, +3 x86, +4 x86_64) should the app
// ever carry native code and split per architecture. it has none today, so it
// ships one universal apk and the offset stays 000.
//
// a malformed version fails the build here rather than silently producing a code
// that is wrong, or worse, lower than the last release.
val mimicVersionCode = run {
    val parts = mimicVersionName.split(".")
    require(parts.size == 3) {
        "version must be MAJOR.MINOR.PATCH, got \"$mimicVersionName\""
    }
    val (major, minor, patch) = parts.map {
        it.toIntOrNull() ?: throw IllegalArgumentException(
            "version part \"$it\" is not a number, in \"$mimicVersionName\""
        )
    }
    require(major >= 0 && minor in 0..99 && patch in 0..99) {
        "minor and patch must be 0..99 to stay monotonic, got \"$mimicVersionName\""
    }
    major * 10_000_000 + minor * 100_000 + patch * 1_000
}

android {
    namespace = "com.khimaros.mimic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.khimaros.mimic"
        minSdk = 26
        targetSdk = 34
        versionCode = mimicVersionCode
        versionName = mimicVersionName
    }

    // env-gated release signing; the keystore stays out of the repo. set
    // MIMIC_KEYSTORE (+ MIMIC_KEYSTORE_PASS / MIMIC_KEY_ALIAS / MIMIC_KEY_PASS) to
    // produce a signed release apk; without it, assembleRelease stays unsigned.
    // providers.environmentVariable reads the invoking env even under the daemon.
    fun env(name: String): String? = providers.environmentVariable(name).orNull
    val releaseSigning = env("MIMIC_KEYSTORE")?.let { path ->
        val keystore = file(path)
        if (!keystore.exists()) null
        else signingConfigs.create("release") {
            storeFile = keystore
            storePassword = env("MIMIC_KEYSTORE_PASS")
            keyAlias = env("MIMIC_KEY_ALIAS")
            keyPassword = env("MIMIC_KEY_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // expose versionName to code via BuildConfig.VERSION_NAME so the version has
    // a single source of truth (defaultConfig above).
    buildFeatures {
        buildConfig = true
    }
}

// no external dependencies: json uses the built-in org.json, auth uses
// android.util.Base64 + java.security, and the ui uses the platform framework
// directly. this also keeps the merged manifest to just our own components.
dependencies {
}

// bundle the cli and skill into assets so the host server can serve them for
// bootstrapping/updates. copied from the repo root at build time to stay in sync.
val bootstrapDir = layout.buildDirectory.dir("generated/bootstrap")
val syncBootstrap by tasks.registering(Copy::class) {
    from(rootProject.file("cli/mimic"))
    from(rootProject.file("SKILL.md"))
    into(bootstrapDir)
}
android.sourceSets.getByName("main").assets.srcDir(bootstrapDir)
tasks.named("preBuild") { dependsOn(syncBootstrap) }
