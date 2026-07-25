plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    explicitApi()
}

sourceSets {
    main {
        kotlin.setSrcDirs(listOf("src/commonMain/kotlin"))
    }
}
