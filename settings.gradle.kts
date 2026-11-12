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

rootProject.name = "FoldWidgets"
include(":app")
include(":kit")
project(":kit").projectDir = file("kit/kit")
