import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.kotlin.jvm") version "2.3.0" apply false
}

group = "com.rustamgarifulin.codescroll"
version = "1.0.0"

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
        else intellijIdea(providers.gradleProperty("platformVersion").get())
        pluginVerifier()
    }
}

sourceSets.main { java.setSrcDirs(emptyList<String>()) }
tasks.processResources { from("LICENSE") { into("META-INF") } }

intellijPlatform {
    splitMode = providers.gradleProperty("splitMode").map(String::toBoolean).getOrElse(false)
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.FRONTEND
    pluginConfiguration {
        name = "Code Scroll"
        // 2026.2 (262): the current stable platform, also the base of the current Android Studio.
        ideaVersion { sinceBuild = "262"; untilBuild = provider { null } }
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

// Optional: ./gradlew runIde -PopenPath=/path/to/project[|/path/to/file] opens those paths in the sandbox IDE.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    // argumentProviders, not args: split mode rejects plain arguments.
    providers.gradleProperty("openPath").orNull?.let { paths ->
        argumentProviders.add(CommandLineArgumentProvider { paths.split('|') })
    }
    // Optional: -PtrustProjects=true skips the "Trust project?" dialog in the sandbox (automated smoke tests).
    if (providers.gradleProperty("trustProjects").orNull == "true") jvmArgs("-Didea.trust.disabled=true")
    // Optional: -PsandboxJvmArgs="-Dkey=value ..." passes extra JVM arguments to the sandbox IDE.
    providers.gradleProperty("sandboxJvmArgs").orNull?.let { jvmArgs(it.split(' ').filter(String::isNotBlank)) }
    // Optional: -PcodeScrollDiagnostics=true logs a minimap diagnostics report per editor (also in a split-mode frontend).
    if (providers.gradleProperty("codeScrollDiagnostics").orNull == "true") environment("CODESCROLL_DIAGNOSTICS", "true")
    // Optional: -PjcefDebugPort=9222 exposes JCEF pages (Markdown preview) to Chrome DevTools.
    providers.gradleProperty("jcefDebugPort").orNull?.let { jvmArgs("-Dide.browser.jcef.debug.port=$it") }
}
