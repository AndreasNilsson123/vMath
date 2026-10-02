// Optional SIMD kernels. Kept out of the core module because the Vector API is still an incubator module: using it needs
// `--add-modules jdk.incubator.vector` at run time, and core must stay usable without that flag.

plugins {
    `java-library`
}

// -Pvalhalla: the core library is then compiled with preview features on JDK 28, so its consumers must be too
val valhalla = providers.gradleProperty("valhalla").isPresent

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(if (valhalla) 28 else 25)) }
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
        options.release.set(28)
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
            addStringOption("source", "28")
        }
    }
}
