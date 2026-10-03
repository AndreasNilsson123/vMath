plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(property("vmath.jdk").toString().toInt())) }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}

// a build tool that runs inside javac, not published API (like vmath-codegen); its javadoc is not generated
tasks.javadoc {
    enabled = false
}

// Coverage and its floor (gradle/module-coverage.gradle.kts)
extra["coverageLineFloor"] = "0.96"
extra["coverageBranchFloor"] = "0.84"
apply(from = rootProject.file("gradle/module-coverage.gradle.kts"))
