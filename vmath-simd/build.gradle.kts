// Optional SIMD kernels. Kept out of the core module because the Vector API is still an incubator module: using it needs
// `--add-modules jdk.incubator.vector` at run time, and core must stay usable without that flag.

plugins {
    `java-library`
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
    api(project(":vmath-scene"))
    // class-retention marker on the SPI types of the core library; not needed at run time
    compileOnly(project(":vmath-annotations"))
    testCompileOnly(project(":vmath-annotations"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -Werror as in the other modules; -incubating silences the notice that the incubator module itself causes
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-exports", "-Xlint:-processing", "-Xlint:-incubating", "-Werror", "--add-modules=jdk.incubator.vector"))
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
        // as in gradle/vmath-module.gradle.kts, so broken references and malformed tags fail the build; -Xwerror is left out because javadoc cannot silence the
        // "using incubating module(s)" notice that the incubator module causes (it has no -Xlint:-incubating), and it would turn that notice into a failure
        addBooleanOption("Xdoclint:all,-missing", true)
        addStringOption("-add-modules", "jdk.incubator.vector")
        if (valhalla) {
            addBooleanOption("-enable-preview", true)
            addStringOption("source", jdk.toString())
        }
    }
}

// Coverage and its floor (gradle/module-coverage.gradle.kts)
extra["coverageLineFloor"] = "0.92"
extra["coverageBranchFloor"] = "0.90"
apply(from = rootProject.file("gradle/module-coverage.gradle.kts"))

tasks.named("check") {
    dependsOn(tasks.javadoc)
}
