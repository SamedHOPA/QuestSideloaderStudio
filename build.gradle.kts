import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.compose") version "1.6.0"
}

group = "com.sideloader"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("net.dongliu:apk-parser:2.6.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}

compose.desktop {
    application {
        mainClass = "MainScreenKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "QuestSideloaderStudio"
            packageVersion = "1.0.0"
            windows {
                menuGroup = "Quest Sideloader Studio"
                shortcut = true
            }
        }
    }
}
