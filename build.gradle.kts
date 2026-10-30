plugins {
    kotlin("jvm") version "2.5.0-Beta2"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    compileOnly("me.clip:placeholderapi:2.11.6")
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.116.3")
    testImplementation("me.clip:placeholderapi:2.11.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

val paperSmokeJar by tasks.registering(Jar::class) {
    dependsOn(tasks.testClasses)
    archiveClassifier.set("paper-smoke")
    from(sourceSets.test.get().output) {
        include("org/plary/genshin/PaperSmokePlugin*")
    }
    from("src/test/paper")
}

tasks.register<xyz.jpenilla.runpaper.task.RunServer>("runPaperSmoke") {
    description = "Runs combat and lifecycle smoke checks on Paper, then stops the test server."
    group = "verification"
    minecraftVersion("1.21.11")
    runDirectory(layout.buildDirectory.dir("paper-smoke").get().asFile)
    pluginJars(tasks.shadowJar.flatMap { it.archiveFile }, paperSmokeJar.flatMap { it.archiveFile })
    jvmArgs("-Xms512M", "-Xmx2G", "-Dcom.mojang.eula.agree=true", "-Dterminal.jline=false", "-Dterminal.ansi=false")
    doLast {
        val log = runDirectory.file("logs/latest.log").get().asFile.readText()
        check("PAPER_SMOKE_PASSED" in log && "PAPER_SMOKE_FAILED" !in log) {
            "Paper smoke checks failed; see ${runDirectory.get().asFile}/logs/latest.log"
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        filesMatching("META-INF/*.kotlin_module") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
