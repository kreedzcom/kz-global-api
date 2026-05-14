plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    jacoco
    alias(libs.plugins.sonarqube)
}

group = "kz.global"
version = "0.1.0"

application {
    mainClass.set("kz.global.api.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=${project.ext.has("development")}")
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    runtimeOnly(libs.janino)

    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.flyway)
    implementation(libs.bundles.koin)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.logback)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.prometheus)
    implementation(platform(libs.aws.bom))
    implementation(libs.aws.s3)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.h2)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.mockk)
    testImplementation(libs.koin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.uuid.ExperimentalUuidApi",
        )
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(false)
    }
}

tasks.named("sonar") {
    dependsOn(tasks.jacocoTestReport)
}

sonar {
    properties {
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.sources", "src/main/kotlin")
        property("sonar.tests", "src/test/kotlin")
        property("sonar.java.coveragePlugin", "jacoco")
        val jacocoXml = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile
        property("sonar.coverage.jacoco.xmlReportPaths", jacocoXml.absolutePath.replace('\\', '/'))
        val mainClasses = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        property("sonar.java.binaries", mainClasses.absolutePath.replace('\\', '/'))
    }
}

ktor {
    fatJar {
        archiveFileName.set("kz-global-api.jar")
    }
}
