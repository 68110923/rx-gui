plugins {
    id("org.jetbrains.intellij.platform") version "2.16.0"
    kotlin("jvm") version "2.1.0"
}

group = "com.reasonix.gui"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        create("PY", "2024.3")
        bundledPlugin("com.intellij.modules.platform")
    }
    implementation("com.google.code.gson:gson:2.11.0")
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

tasks {
    runIde {
        systemProperty("rx-gui.dev-resources", project.projectDir.resolve("src/main/resources").absolutePath)
    }
}
