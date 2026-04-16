pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MQTTtastic-Client-KMP"
include(":library")
include(":sample")
include(":sample:androidApp")
