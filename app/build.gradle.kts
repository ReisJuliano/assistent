import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.assistent"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.assistent"
        minSdk = 26
        targetSdk = 35
        // Monotonic version for self-updates: minutes since 2026-01-01 (each build is "newer").
        versionCode = ((System.currentTimeMillis() / 60_000L) - 29_450_880L).toInt().coerceAtLeast(1)
        versionName = "0.1.$versionCode"
    }

    buildTypes {
        release {
            // R8 keeps the sideloadable APK small. Signed with the local debug key so it installs over debug builds;
            // use your own keystore (keystore.properties, git-ignored) for distribution.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        // Compressed native libs: much smaller APK to download/share (extracted at install time).
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.camera.camera2)
    implementation(libs.camera.view)
    implementation(libs.camera.lifecycle)
    implementation(libs.mlkit.face)
    implementation(libs.mlkit.labeling)
    implementation(libs.tflite)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode)

    implementation(libs.vosk.android) { artifact { type = "aar" } }
    implementation(libs.jna) { artifact { type = "aar" } }

    // sherpa-onnx (speaker recognition), fetched by the downloadSherpaOnnx task into app/libs (git-ignored).
    implementation(files("libs/sherpa-onnx.aar"))

    testImplementation(libs.junit)
}


/**
 * `./gradlew publishUpdate` builds the debug APK and publishes it to `<repo>/updates/` with a `latest.json`
 * manifest (version + SHA-256). Serve that folder (e.g. `tools/serve-updates.ps1`) and point the app's
 * "update URL" at `http://<pc-ip>:8318/latest.json`: every device updates itself.
 */
tasks.register("publishUpdate") {
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val out = rootProject.layout.projectDirectory.dir("updates").asFile.apply { mkdirs() }
        val target = File(out, "assistent.apk")
        apk.copyTo(target, overwrite = true)
        val md = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        val code = android.defaultConfig.versionCode
        val name = android.defaultConfig.versionName
        File(out, "latest.json").writeText(
            """{"versionCode":$code,"versionName":"$name","apk":"assistent.apk","sha256":"$sha"}""",
        )
        println("Published update $name ($code) -> ${out.absolutePath}")
    }
}

/**
 * `./gradlew publishRelease` = publishUpdate + a GitHub release (tag `v<versionName>`) with the APK and a
 * `latest.json` pointing at that tag's APK. Devices poll `releases/latest/download/latest.json`, so they update
 * from anywhere without the dev PC. Needs the GitHub CLI (`gh auth login`). Repo: `-PreleaseRepo=owner/name`.
 */
tasks.register("publishRelease") {
    dependsOn("publishUpdate")
    doLast {
        val repo = (findProperty("releaseRepo") as String?) ?: "ReisJuliano/assistent"
        val name = android.defaultConfig.versionName
        val tag = "v$name"
        val out = rootProject.layout.projectDirectory.dir("updates").asFile
        val manifest = File(out, "latest.json")
        manifest.writeText(
            manifest.readText().replace(
                "\"apk\":\"assistent.apk\"",
                "\"apk\":\"https://github.com/$repo/releases/download/$tag/assistent.apk\"",
            ),
        )
        fun run(vararg cmd: String): String {
            val p = ProcessBuilder(*cmd).directory(rootProject.projectDir).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "${cmd.first()} failed: $text" }
            return text.trim()
        }
        val gh = listOf("C:/Program Files/GitHub CLI/gh.exe", "/usr/bin/gh", "/opt/homebrew/bin/gh")
            .firstOrNull { File(it).exists() } ?: "gh"
        val notes = runCatching { run("git", "log", "-1", "--format=%s") }.getOrDefault("Update")
        run(
            gh, "release", "create", tag, File(out, "assistent.apk").path, manifest.path,
            "--repo", repo, "--title", "Assistent $name", "--notes", notes, "--latest",
        )
        println("Released $tag on github.com/$repo")
    }
}

/** Downloads the sherpa-onnx Android AAR (on-device speaker recognition) once; it is too big to commit. */
val downloadSherpaOnnx by tasks.registering {
    val target = file("libs/sherpa-onnx.aar")
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        target.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
        URI(url).toURL().openStream().use { input: java.io.InputStream -> target.outputStream().use { out -> input.copyTo(out) } }
        println("Downloaded ${target.name} (${target.length() / 1_000_000} MB)")
    }
}
tasks.named("preBuild") { dependsOn(downloadSherpaOnnx) }
