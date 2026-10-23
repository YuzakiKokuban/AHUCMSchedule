plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(21) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
dependencies {
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("org.apache.poi:poi:5.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    runtimeOnly("org.apache.logging.log4j:log4j-to-slf4j:2.24.3")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    val live = System.getenv("AHUCM_LIVE_ACCOUNT") != null && System.getenv("AHUCM_LIVE_PASSWORD") != null
    val sample = System.getenv("AHUCM_SAMPLE_XLS") != null
    inputs.property("liveVerificationEnabled", live)
    inputs.property("privateSampleEnabled", sample)
    outputs.upToDateWhen { !live && !sample }
}
