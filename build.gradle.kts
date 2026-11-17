plugins {
    // IntelliJ 2025.3 同梱の Kotlin(2.3.x) が読めるのは 2.4 までなので、2.5 系にはしない
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// BetterModel のバージョン（API とテストサーバー用 jar で揃える）
val betterModelVersion = "3.6.0"

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    // BetterModel（サーバー側に導入済みのものを使うので jar には同梱しない）
    compileOnly("io.github.toxicity188:bettermodel-bukkit-api:$betterModelVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G")
        // テストサーバーに BetterModel を自動導入する
        downloadPlugins {
            modrinth("bettermodel", betterModelVersion)
        }
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
