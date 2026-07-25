pluginManagement {
    val kotlinVersionOverride = providers.gradleProperty("kotlinVersion").orNull

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    resolutionStrategy {
        eachPlugin {
            if (
                kotlinVersionOverride != null &&
                requested.id.id.startsWith("org.jetbrains.kotlin.")
            ) {
                useVersion(kotlinVersionOverride)
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "bandwidth-timeout-checker"

include("compiler-plugin")
include("checker-core")
include("gradle-plugin")
include("integration-tests")
include("plugin-annotations")
include("runtime")
