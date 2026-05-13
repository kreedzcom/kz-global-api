plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
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
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.flyway)
    implementation(libs.bundles.koin)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.logback)
    implementation(platform(libs.aws.bom))
    implementation(libs.aws.s3)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.koin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

ktor {
    fatJar {
        archiveFileName.set("kz-global-api.jar")
    }
}
