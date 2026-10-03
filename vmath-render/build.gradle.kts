plugins {
    `java-library`
}

dependencies {
    api(project(":vmath-core"))
    api(project(":vmath-geo"))
    api(project(":vmath-scene"))
}

extra["vmathTestJars"] = true // ModuleDescriptorTest and PackageLayeringTest inspect the jars of the whole library
extra["vmathBelow"] = listOf(":vmath-core", ":vmath-geo", ":vmath-scene")
extra["publishDescription"] = "Cameras, mesh processing, the glTF loader and GPU culling data for vmath."
apply(from = rootProject.file("gradle/vmath-module.gradle.kts"))
