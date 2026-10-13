plugins {
    java
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

java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
tasks.withType<JavaCompile>().configureEach { options.release = 21 }
tasks.processResources { from("LICENSE") { into("META-INF") } }

tasks.register<Exec>("testJavaScript") {
    workingDir(projectDir)
    commandLine("node", "--test", "src/test/js/minimap.test.cjs")
}
