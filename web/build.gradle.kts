plugins {
    kotlin("js")
    kotlin("plugin.serialization")
}

import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

repositories {
    mavenCentral()
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    port = 8088
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        val main by getting {
            kotlin.srcDir("src/jsMain/kotlin")
            resources.srcDir("src/jsMain/resources")
            dependencies {
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react:2025.12.2-19.2.1")
                implementation("org.jetbrains.kotlin-wrappers:kotlin-react-dom:2025.12.2-19.2.1")
                implementation("io.ktor:ktor-client-core-js:3.4.1")
                implementation("io.ktor:ktor-client-js:3.4.1")
                implementation("io.ktor:ktor-client-content-negotiation:3.4.1")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.1")
                implementation("io.insert-koin:koin-core:4.1.1")
            }
        }
        val test by getting {
            kotlin.srcDir("src/jsTest/kotlin")
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
