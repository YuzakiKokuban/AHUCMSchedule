plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    kotlin("plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("com.android.application") version "9.4.1" apply false
}

val releaseProperties = java.util.Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
allprojects { version = releaseProperties.getProperty("versionName") }
extra["androidVersionCode"] = releaseProperties.getProperty("versionCode").toInt()
