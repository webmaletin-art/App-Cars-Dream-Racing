import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// versionCode = max(APP_VERSION_CODE, nº de commits): crece solo con cada commit, así cada APK nuevo se instala
// encima del anterior (misma firma + versionCode mayor) sin desinstalar.
val gitCommitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD"); isIgnoreExitValue = true }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)
val appVersionCode = maxOf((project.findProperty("APP_VERSION_CODE") as String).toInt(), gitCommitCount)
val appVersionName = project.findProperty("APP_VERSION_NAME") as String

android {
    namespace = "com.webmaletin.gtaviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.webmaletin.gtaviewer"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
    }

    // Una única clave de firma estable para debug y release: cualquier APK generado (en cualquier equipo o en CI)
    // se instala ENCIMA de la versión anterior sin desinstalar, mientras se suba APP_VERSION_CODE (gradle.properties).
    // Para usar tu propia clave: reemplaza signing/gtaviewer.keystore (o define SIGNING_* en gradle.properties/entorno).
    signingConfigs {
        create("shared") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: (project.findProperty("SIGNING_STORE_FILE") as String? ?: "../signing/gtaviewer.keystore"))
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: (project.findProperty("SIGNING_STORE_PASSWORD") as String? ?: "gtaviewer-sign")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: (project.findProperty("SIGNING_KEY_ALIAS") as String? ?: "gtaviewer")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: (project.findProperty("SIGNING_KEY_PASSWORD") as String? ?: "gtaviewer-sign")
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true; isReturnDefaultValues = true
            // Entornos sin acceso directo a Maven pueden precargar el jar de Robolectric y apuntar ROBOLECTRIC_DIR a esa carpeta.
            all { t ->
                System.getenv("ROBOLECTRIC_DIR")?.let { t.systemProperty("robolectric.offline", "true"); t.systemProperty("robolectric.dependency.dir", it) }
                t.maxHeapSize = "1g"
            }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
