pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "handys"

include(
    "app-api",
    "app-batch",
    "module-common",
    "module-property",
    "module-inventory",
    "module-booking",
    "module-checkin",
    "module-channel",
)

project(":module-common").projectDir = file("modules/common")
project(":module-property").projectDir = file("modules/property")
project(":module-inventory").projectDir = file("modules/inventory")
project(":module-booking").projectDir = file("modules/booking")
project(":module-checkin").projectDir = file("modules/checkin")
project(":module-channel").projectDir = file("modules/channel")
