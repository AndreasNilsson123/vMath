plugins {
    `java-library`
    jacoco
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

// Coverage (docs/COVERAGE.md, TD-06). Not on the -Pvalhalla build: the JaCoCo release in use does not read JDK 28 class files.
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    onlyIf { !providers.gradleProperty("valhalla").isPresent }
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    extensions.configure<JacocoTaskExtension> { isEnabled = !providers.gradleProperty("valhalla").isPresent }
    finalizedBy(tasks.jacocoTestReport)
}

// Floors two to three points under what was measured (docs/COVERAGE.md).
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    onlyIf { !providers.gradleProperty("valhalla").isPresent }
    violationRules {
        rule {
            element = "BUNDLE"
            limit { counter = "LINE"; minimum = "0.76".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.62".toBigDecimal() }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
