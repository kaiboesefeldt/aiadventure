import org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension

plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("js") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
}

// webpack-cli 6 requires commander 12; pin the root npm resolution accordingly.
NpmExtension[rootProject].override("commander", "12.1.0")
