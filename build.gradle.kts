plugins {
    id("org.jetbrains.intellij.platform") version "2.16.0"
    kotlin("jvm") version "2.1.0"
}

group = "com.reasonix.gui"
version = "0.1.0"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2024.3")
        bundledPlugin("com.intellij.modules.platform")
    }
}

intellijPlatform {
    instrumentCode = false

    pluginConfiguration {
        name = "RX GUI"
        ideaVersion {
            sinceBuild = "243"
            untilBuild = ""
        }
    }
}
