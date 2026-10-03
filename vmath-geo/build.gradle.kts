plugins {
    `java-library`
}

dependencies {
    api(project(":vmath-core"))
}

extra["vmathBelow"] = listOf(":vmath-core")
extra["publishDescription"] = "Shapes, intersection tests, convex geometry, signed distance fields, vertex packing and physics math for vmath."
apply(from = rootProject.file("gradle/vmath-module.gradle.kts"))
