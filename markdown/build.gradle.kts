import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    java
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    compileOnly(project(":frontend"))
    intellijPlatform { bundledPlugin("org.intellij.plugins.markdown") }
}

// The IntelliJ Platform Gradle Plugin picks the JVM target the platform requires (Java 25 for 2026.2).
kotlin {
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
        // Keep platform interfaces' default methods where they are, instead of generating stubs that call
        // (and so depend on) every deprecated or experimental default method of the interface.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}
tasks.processResources { from("LICENSE") { into("META-INF") } }

tasks.register<Exec>("testJavaScript") {
    workingDir(projectDir)
    commandLine("node", "--test", "src/test/js/minimap.test.cjs")
}
