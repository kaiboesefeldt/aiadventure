import org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin

plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("js") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
}

// webpack-cli 6 requires commander 12; pin the root npm resolution accordingly.
NpmExtension[rootProject].override("commander", "12.1.0")
// Keep Karma and Kotlin-generated webpack plugins on the same Compilation class.
NpmExtension[rootProject].override("webpack", "5.101.3")
tasks.matching { it.name in setOf("rootPackageJson", "kotlinNpmInstall") }.configureEach {
    inputs.property("webpackOverride", "5.101.3")
}

allprojects {
    project.plugins.withType<NodeJsPlugin> {
        project.the<NodeJsEnvSpec>().version = "22.0.0"
    }
}
