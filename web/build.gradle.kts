plugins {
    kotlin("js")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    sourceSets {
        val main by getting {
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
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
