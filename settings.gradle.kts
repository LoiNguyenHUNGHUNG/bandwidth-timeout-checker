pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
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
