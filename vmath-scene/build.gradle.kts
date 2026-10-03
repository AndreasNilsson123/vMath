plugins {
    `java-library`
}

dependencies {
    api(project(":vmath-core"))
    api(project(":vmath-geo"))
}

extra["vmathBelow"] = listOf(":vmath-core", ":vmath-geo")
extra["publishDescription"] = "Bulk containers and kernels, spatial structures and culling, occlusion, animation, GPU data layouts and utilities for vmath."
apply(from = rootProject.file("gradle/vmath-module.gradle.kts"))
