import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseProperties = Properties().apply {
    val configuration = rootProject.file(".signing/release.properties")
    if (configuration.isFile) configuration.inputStream().use { load(it) }
}
fun releaseValue(key: String, environment: String): String? =
    providers.environmentVariable(environment).orNull ?: releaseProperties.getProperty(key)
val releaseStore = releaseValue("storeFile", "APK_PUSH_KEYSTORE")

android {
    namespace = "dev.tvapkpush"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.tvapkpush"
        minSdk = 29
        targetSdk = 34
        versionCode = 26
        versionName = "1.12.1"
        testInstrumentationRunner = "dev.tvapkpush.DeviceSmokeRunner"
    }

    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("tmp/keys/debug.keystore")
    }
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseStore)
            storePassword = releaseValue("storePassword", "APK_PUSH_STORE_PASSWORD")
            keyAlias = releaseValue("keyAlias", "APK_PUSH_KEY_ALIAS")
            keyPassword = releaseValue("keyPassword", "APK_PUSH_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
    packaging.resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
}

val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    doLast {
        require(releaseStore != null && rootProject.file(releaseStore).isFile &&
            listOf("storePassword" to "APK_PUSH_STORE_PASSWORD", "keyAlias" to "APK_PUSH_KEY_ALIAS",
                "keyPassword" to "APK_PUSH_KEY_PASSWORD").all { (key, env) -> !releaseValue(key, env).isNullOrBlank() }) {
            "Configure release signing in .signing/release.properties or APK_PUSH_* environment variables."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }

tasks.register<Copy>("packageReleaseApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("artifacts"))
    rename { "APK-Push-1.12.1-release.apk" }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.register<Copy>("packageDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("artifacts"))
    rename { "APK-Push-1.12.1-debug.apk" }
}

val prepareDebugKeystore = tasks.register("prepareDebugKeystore") {
    val keyFile = rootProject.file("tmp/keys/debug.keystore")
    outputs.file(keyFile)
    onlyIf { !keyFile.exists() }
    doLast {
        keyFile.parentFile.mkdirs()
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        project.exec {
            commandLine(keytool, "-genkeypair", "-keystore", keyFile.absolutePath,
                "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-storetype", "JKS",
                "-dname", "CN=Android Debug,O=Android,C=US")
        }
    }
}

tasks.matching { it.name == "validateSigningDebug" }.configureEach {
    dependsOn(prepareDebugKeystore)
}

dependencies {
    implementation("com.auroraoss:gplayapi:4.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    val temporaryDirectory = rootProject.file("tmp/tests")
    systemProperty("java.io.tmpdir", temporaryDirectory.absolutePath)
    doFirst { temporaryDirectory.mkdirs() }
}
