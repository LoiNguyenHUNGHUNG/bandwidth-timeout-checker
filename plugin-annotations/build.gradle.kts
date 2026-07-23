plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
}

sourceSets {
    main {
        kotlin.setSrcDirs(listOf("src/commonMain/kotlin"))
    }
}
