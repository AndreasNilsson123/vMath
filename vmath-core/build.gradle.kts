plugins {
    `java-library`
}

dependencies {
}

extra["vmathBelow"] = emptyList<String>()
extra["publishDescription"] = "The value types of vmath: vectors, quaternions and matrices in float and double, plus memory allocators, colour and texture formats."
apply(from = rootProject.file("gradle/vmath-module.gradle.kts"))
