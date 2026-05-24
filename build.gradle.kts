plugins {
    id("org.jetbrains.intellij.platform") version "2.2.1"
    kotlin("jvm") version "2.0.21"
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
        create("IC", "2024.2")
        bundledPlugin("com.intellij.modules.platform")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Reasonix GUI"
        ideaVersion {
            sinceBuild = "242"    // 2024.2
            untilBuild = ""       // 不限上限
        }
    }

    pluginVerification {
        ides { recommended() }
    }
}
