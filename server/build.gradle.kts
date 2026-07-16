import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.4.1")
    implementation("io.ktor:ktor-server-netty:3.4.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.4.1")
    implementation("io.ktor:ktor-server-cors:3.4.1")
    implementation("io.ktor:ktor-server-call-id:3.4.1")
    implementation("io.ktor:ktor-server-status-pages:3.4.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.1")
    implementation("dev.langchain4j:langchain4j:1.17.2")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.4.1")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.4.1")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

application {
    mainClass.set("de.aidadventure.server.ApplicationKt")
}

tasks.processResources {
    dependsOn(":web:browserProductionWebpack")
    from(project(":web").layout.buildDirectory.dir("processedResources/js/main")) {
        into("ui")
    }
    from(project(":web").layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) {
        into("ui")
    }
}
