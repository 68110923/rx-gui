plugins {
    id("org.jetbrains.intellij") version "1.17.4"
    kotlin("jvm") version "1.9.22"
}

group = "com.reasonix.gui"
version = "0.1.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.2")
    type.set("IC")                   // Community Edition, 兼容所有 JetBrains IDE
    plugins.set(listOf("com.intellij.modules.platform"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("232")        // 2023.2
        untilBuild.set("")           // 不限上限
    }
}
