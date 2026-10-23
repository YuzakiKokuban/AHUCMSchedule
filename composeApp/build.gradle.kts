import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}
kotlin {
    jvmToolchain(21)
    jvm("desktop")
    sourceSets {
        getByName("desktopMain") {
            kotlin.srcDir("src/jvmSharedMain/kotlin")
            dependencies {
                implementation(project(":core"))
                implementation("net.java.dev.jna:jna-platform:5.18.1")
                implementation(compose.desktop.currentOs)
                implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
                implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
            }
        }
        getByName("desktopTest") {
            dependencies {
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.desktop.uiTestJUnit4)
                implementation(kotlin("test-junit"))
                implementation("org.apache.poi:poi:5.4.1")
            }
        }
    }
}
compose.desktop {
    application {
        mainClass = "cn.edu.ahtcm.schedule.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "AHUCMSchedule"
            packageVersion = project.version.toString()
            windows {
                menuGroup = "AHUCMSchedule"
                shortcut = true
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
            }
            modules("java.sql", "java.naming", "jdk.unsupported", "java.net.http")
        }
    }
}
