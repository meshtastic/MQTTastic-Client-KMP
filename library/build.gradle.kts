plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // TODO: Re-enable when atomicfu is compatible with android.kotlin.multiplatform.library
    // alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

group = "org.meshtastic"
version = "0.1.0"

kotlin {
    applyDefaultHierarchyTemplate()

    // Compile to JVM 11 bytecode for maximum compatibility
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    androidLibrary {
        namespace = "org.meshtastic.mqtt"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}
    }

    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        // Custom intermediate source set for all non-browser targets (TCP transport).
        // Not part of the default hierarchy template — must be wired manually.
        val nonWebMain by creating {
            dependsOn(commonMain.get())
        }
        val nonWebTest by creating {
            dependsOn(commonTest.get())
        }

        jvmMain.get().dependsOn(nonWebMain)
        androidMain.get().dependsOn(nonWebMain)
        nativeMain.get().dependsOn(nonWebMain)

        jvmTest.get().dependsOn(nonWebTest)
        nativeTest.get().dependsOn(nonWebTest)

        // Wire Android host test to nonWebTest for shared transport tests
        findByName("androidHostTest")?.dependsOn(nonWebTest)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        nonWebMain.dependencies {
            implementation(libs.ktor.network)
            implementation(libs.ktor.network.tls)
        }

        wasmJsMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.websockets)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "mqtt-client", version.toString())

    pom {
        name = "MQTTtastic Client"
        description = "A fully-featured MQTT 5.0 client library for Kotlin Multiplatform."
        inceptionYear = "2025"
        url = "https://github.com/meshtastic/MQTTtastic-Client-KMP"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "meshtastic"
                name = "Meshtastic"
                url = "https://meshtastic.org"
            }
        }
        scm {
            url = "https://github.com/meshtastic/MQTTtastic-Client-KMP"
            connection = "scm:git:git://github.com/meshtastic/MQTTtastic-Client-KMP.git"
            developerConnection = "scm:git:ssh://github.com/meshtastic/MQTTtastic-Client-KMP.git"
        }
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
}
