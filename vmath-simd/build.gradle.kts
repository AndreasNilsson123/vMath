// Optional SIMD kernels. Kept out of the core module because the Vector API is still an incubator module: using it needs
// `--add-modules jdk.incubator.vector` at run time, and core must stay usable without that flag.

plugins {
    `java-library`
    jacoco
}

// -Pvalhalla: the core library is then compiled with preview features on JDK 28, so its consumers must be too
val valhalla = providers.gradleProperty("valhalla").isPresent
// the JDK numbers live in gradle.properties (docs/technical-debt.md TD-14)
val jdk = property(if (valhalla) "vmath.valhallaJdk" else "vmath.jdk").toString().toInt()

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(jdk)) }
    withSourcesJar()
}

dependencies {
    api(project(":"))
    // class-retention marker on the SPI types of the core library; not needed at run time
    compileOnly(project(":vmath-annotations"))
    testCompileOnly(project(":vmath-annotations"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "--add-modules=jdk.incubator.vector"))
    if (valhalla) {
        options.release.set(jdk)
        options.compilerArgs.add("--enable-preview")
    }
}

tasks.test {
    useJUnitPlatform()
    // Tests run on the class path, where the incubator module is not resolved by default.
    jvmArgs("--add-modules=jdk.incubator.vector")
    if (valhalla) {
        jvmArgs("--enable-preview")
    }
    dependsOn(tasks.jar)
    systemProperty("vmath.simd.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}

extra["publishDescription"] = "Optional Vector API (SIMD) kernels for vmath: frustum culling and batch matrix products. Needs --add-modules jdk.incubator.vector."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("-add-modules", "jdk.incubator.vector")
        if (valhalla) {
            addBooleanOption("-enable-preview", true)
            addStringOption("source", jdk.toString())
        }
    }
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
            limit { counter = "LINE"; minimum = "0.92".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.90".toBigDecimal() }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
