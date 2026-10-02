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
    options.compilerArgs.add("-Xlint:all")
}

tasks.test {
    useJUnitPlatform()
}

// the code generator is a build tool, not published API; its javadoc is not generated (the published modules keep a warning-free javadoc build, docs/technical-debt.md TD-23)
tasks.javadoc {
    enabled = false
}

// Coverage and its floor (gradle/module-coverage.gradle.kts)
extra["coverageLineFloor"] = "0.76"
extra["coverageBranchFloor"] = "0.62"
apply(from = rootProject.file("gradle/module-coverage.gradle.kts"))
