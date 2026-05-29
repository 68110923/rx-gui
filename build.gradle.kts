plugins {
    id("org.jetbrains.intellij.platform") version "2.16.0"
    kotlin("jvm") version "2.3.0"
}

group = "com.reasonix.gui"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        local("/Applications/PyCharm.app")
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

val vmOptionsFile = rootProject.file("/Users/liugensheng/Library/Application Support/JetBrains/PyCharm2026.1/pycharm.vmoptions")

tasks {
    runIde {
        jvmArgs(vmOptionsFile.readLines().filter {
            it.isNotBlank() && !it.startsWith("#") && !it.startsWith("-D")
        })
        systemProperty("rx-gui.dev-resources", project.projectDir.resolve("src/main/resources").absolutePath)
    }
}