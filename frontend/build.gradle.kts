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
    // Each module declares the platform itself: resolving the root project's platform from here breaks IDE sync.
    intellijPlatform {
        val localPath = providers.gradleProperty("localIdePath").orNull
        if (localPath != null) local(localPath) else intellijIdea(providers.gradleProperty("platformVersion").get())
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The IDE test bootstrap loads JUnit 4 rules even for Jupiter-only tests.
    testRuntimeOnly("junit:junit:4.13.2")
}

// The IntelliJ Platform Gradle Plugin picks the JVM target the platform requires (Java 25 for 2026.2).
kotlin {
    compilerOptions {
        // The platform's own Kotlin stdlib is used at runtime; stay within what 2026.2 ships.
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
        // Keep platform interfaces' default methods where they are, instead of generating stubs that call
        // (and so depend on) every deprecated or experimental default method of the interface.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}
tasks.test { useJUnitPlatform(); systemProperty("java.awt.headless", "true") }
