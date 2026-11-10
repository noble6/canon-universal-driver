plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    // Android ships org.json; the JVM build and tests need the library
    compileOnly("org.json:json:20250517")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("models.json", rootProject.file("../models/ivec_models.json").absolutePath)
    systemProperty("ivec.refs", System.getenv("IVEC_REFS") ?: "${System.getProperty("user.home")}/.cache/canon-ivec-re/and")
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
