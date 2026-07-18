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
    implementation("io.insert-koin:koin-core:4.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-cli-jvm:0.3.6")
    implementation("org.apache.lucene:lucene-core:10.4.0")
    implementation("org.apache.lucene:lucene-analysis-common:10.4.0")
    implementation(platform("ai.djl:bom:0.36.0"))
    implementation("ai.djl:api")
    implementation("ai.djl:model-zoo")
    implementation("ai.djl.onnxruntime:onnxruntime-engine")
    implementation("ai.djl.huggingface:tokenizers")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
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

tasks.test {
    useJUnitPlatform()
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
