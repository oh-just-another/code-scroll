import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.codescroll"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation(project(":frontend"))
    implementation(project(":markdown"))
    intellijPlatform {
        val localPath = providers.gradleProperty("localIdePath").orNull
        if (localPath != null) local(localPath)
        else intellijIdea(providers.gradleProperty("platformVersion").getOrElse("2025.3.6.1"))
        pluginVerifier()
    }
}

sourceSets.main { java.setSrcDirs(emptyList<String>()) }
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
tasks.withType<JavaCompile>().configureEach { options.release = 21 }
tasks.processResources { from("LICENSE") { into("META-INF") } }

intellijPlatform {
    splitMode = providers.gradleProperty("splitMode").map(String::toBoolean).getOrElse(false)
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.FRONTEND
    pluginConfiguration {
        name = "Code Scroll"
        ideaVersion { sinceBuild = "253"; untilBuild = provider { null } }
    }
    buildSearchableOptions = true
    pluginVerification {
        ides {
            val paths = providers.gradleProperty("verifyIdePaths").orNull
            if (paths != null) paths.split('|').forEach { local(file(it)) }
            else current()
        }
    }
}

// Optional: ./gradlew runIde -PopenPath=/path/to/project opens that path in the sandbox IDE.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    providers.gradleProperty("openPath").orNull?.let { args(it) }
    // Optional: -PjcefDebugPort=9222 exposes JCEF pages (Markdown preview) to Chrome DevTools.
    providers.gradleProperty("jcefDebugPort").orNull?.let { jvmArgs("-Dide.browser.jcef.debug.port=$it") }
}
