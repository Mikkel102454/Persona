plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.0-rc3"
    id("xyz.jpenilla.run-paper") version "3.1.0"
    `maven-publish`
}

group = "nu.miguel"
version = "2.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.citizensnpcs.co/repo")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("net.citizensnpcs:citizens-main:2.0.43-SNAPSHOT") {
        isTransitive = false
    }
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:26.2.build.+")
    testImplementation("net.citizensnpcs:citizens-main:2.0.43-SNAPSHOT") { isTransitive = false }
    testImplementation("org.mockito:mockito-core:5.19.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

publishing {
    publications { create<MavenPublication>("persona") { from(components["java"]); artifact(tasks.shadowJar) { classifier = "all" } } }
}

tasks {
    test { useJUnitPlatform() }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
    }

    build { dependsOn(shadowJar) }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.2")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
