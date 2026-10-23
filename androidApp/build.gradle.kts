import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

// Local signing is supported for existing installations; CI supplies environment
// variables. A missing release key never falls back to a debug signature.
val localProps = rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
} ?: Properties()

android {
    namespace = "com.yuzakikokuban.ahucm"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        applicationId = "com.yuzakikokuban.ahucm"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = rootProject.extra["androidVersionCode"] as Int
        versionName = project.version.toString()
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].kotlin.directories.add("../composeApp/src/jvmSharedMain/kotlin")
    buildFeatures { compose = true }
    signingConfigs {
        val environmentStore = providers.environmentVariable("AHUCM_KEYSTORE_PATH").orNull
        val keystorePath = environmentStore ?: localProps.getProperty("ahucm.release.storeFile")
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("AHUCM_KEYSTORE_PASSWORD").orNull
                    ?: requireNotNull(localProps.getProperty("ahucm.release.storePassword"))
                keyAlias = providers.environmentVariable("AHUCM_KEYSTORE_ALIAS").orNull
                    ?: requireNotNull(localProps.getProperty("ahucm.release.keyAlias"))
                keyPassword = if (environmentStore != null) storePassword
                    else localProps.getProperty("ahucm.release.keyPassword") ?: storePassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            // Keep the module entry and META-INF/xposed declarations intact.
            isMinifyEnabled = false
        }
    }
    packaging.resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")

}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.github.libxposed:api:102.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
                implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
