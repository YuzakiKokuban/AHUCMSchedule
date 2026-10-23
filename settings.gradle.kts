import java.util.Properties

pluginManagement {
    val localSettings = java.util.Properties().apply {
        val config = file("local.properties")
        if (config.exists()) config.inputStream().use { load(it) }
    }
    repositories {
        if (localSettings.getProperty("ahu.repositories.mirror") == "aliyun") {
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/google")
        } else { google(); mavenCentral() }
        gradlePluginPortal()
    }
}
val local = Properties().apply {
    val config = file("local.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral { content { includeGroup("io.github.libxposed") } }
        if (local.getProperty("ahu.repositories.mirror") == "aliyun") {
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/google") {
                content {
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google\\.android.*")
                }
            }
        } else { google(); mavenCentral() }
    }
}
rootProject.name = "AHUCMSchedule"
include(":core", ":composeApp")
val sdkAvailable = local.getProperty("sdk.dir") != null ||
    System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
val androidEnabled = providers.gradleProperty("androidEnabled").orNull?.toBoolean() ?: sdkAvailable
gradle.extra["androidEnabled"] = androidEnabled
if (androidEnabled) include(":androidApp")
