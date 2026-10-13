plugins {
    java
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The 253 IDE test bootstrap loads JUnit 4 rules even for Jupiter-only tests.
    testRuntimeOnly("junit:junit:4.13.2")
}

java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
tasks.withType<JavaCompile>().configureEach { options.release = 21 }
sourceSets {
    main {
        java.setSrcDirs(listOf("../src/main/java"))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
    test { java.setSrcDirs(listOf("../src/test/java")) }
}
tasks.test { useJUnitPlatform(); systemProperty("java.awt.headless", "true") }
