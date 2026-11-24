import java.util.Properties

plugins {
    id("com.android.application") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "1.9.22"
}

val defaultKVideoUrl = providers
    .gradleProperty("kvideoUrl")
    .orElse("")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

// 版本号唯一来源：android-tv/version.properties（`./gradlew bumpVersion` 自动 +1），
// 不要再在 app/build.gradle.kts 里手写两行 —— 以前每次都要改两个数字，容易漏一个。
val verProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersionCode = (verProps.getProperty("versionCode") ?: "1").toInt()
val appVersionName = verProps.getProperty("versionName") ?: "0.0.0"

tasks.register("bumpVersion") {
    doLast {
        val f = rootProject.file("version.properties")
        val p = Properties().apply { if (f.exists()) f.inputStream().use { load(it) } }
        val cur = p.getProperty("versionName") ?: "0.0.0"
        val parts = cur.split(".")
        val next = if (parts.size == 3) "${parts[0]}.${parts[1]}.${parts[2].toInt() + 1}" else "1.0.0"
        val nextCode = (p.getProperty("versionCode") ?: "0").toInt() + 1
        p.setProperty("versionName", next)
        p.setProperty("versionCode", nextCode.toString())
        f.outputStream().use { p.store(it, null) }
        println("version -> $next ($nextCode)")
    }
}

android {
    namespace = "com.kvideo.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kvideo.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "DEFAULT_KVIDEO_URL", "\"$defaultKVideoUrl\"")
    }

    // 不依赖 Gradle 自动生成的默认 debug key：显式指向仓库提供的固定密钥，
    // 否则每次构建签名都变，Android 会拒绝覆盖安装。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Compose + Media3 走 R8 要额外 keep 规则，2.0 先不压
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
