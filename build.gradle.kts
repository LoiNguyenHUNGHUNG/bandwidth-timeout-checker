plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.buildconfig) apply false
}

allprojects {
    group = "io.github.loinguyen.bandwidth"
    version = "0.1.0-SNAPSHOT"
}
