plugins {
    java
}

// -Pvalhalla: the core library is then compiled with preview features on JDK 28, so its consumers must be too
val valhalla = providers.gradleProperty("valhalla").isPresent
// the JDK numbers live in gradle.properties (docs/technical-debt.md TD-14)
val jdk = property(if (valhalla) "vmath.valhallaJdk" else "vmath.jdk").toString().toInt()

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(jdk)) }
}

dependencies {
    implementation(project(":"))
    compileOnly(project(":vmath-annotations")) // class-retention marker on some core types; not needed at run time
    // Optional SIMD kernels, found through FrustumKernels.best() when present and the incubator module is enabled.
    implementation(project(":vmath-simd"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator.annprocess)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if (valhalla) {
        options.release.set(jdk)
        options.compilerArgs.add("--enable-preview")
    }
}

// ./gradlew :vmath-bench:jmh                                   all benchmarks
// ./gradlew :vmath-bench:jmh -Pjmh.args="-prof gc Mat4Bench"   allocation profile of one class
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the JMH benchmarks. Pass JMH arguments with -Pjmh.args."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdk)) })
    // The SIMD kernels need the Vector API. JMH benchmarks run in forked JVMs, which do not inherit this task's
    // jvmArgs, so pass it to the forks as well (and preview features when the classes were compiled with them).
    val vmArgs = if (valhalla) "--add-modules=jdk.incubator.vector --enable-preview" else "--add-modules=jdk.incubator.vector"
    jvmArgs(vmArgs.split(" "))
    val extra = providers.gradleProperty("jmh.args").orElse("").get()
    val forkArgs = listOf("-jvmArgsAppend", vmArgs)
    args(forkArgs + extra.split(" ").filter { it.isNotBlank() })
}

// ./gradlew :vmath-bench:sample   headless sample: cull 1M instances, write the instance buffer and an indirect draw command
tasks.register<JavaExec>("sample") {
    group = "application"
    description = "Runs CullAndDrawSample."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("vmath.bench.sample.CullAndDrawSample")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdk)) })
    jvmArgs((if (valhalla) "--add-modules=jdk.incubator.vector --enable-preview" else "--add-modules=jdk.incubator.vector").split(" "))
}

// benchmarks are not API; its javadoc is not generated (the published modules keep a warning-free javadoc build, docs/technical-debt.md TD-23)
tasks.javadoc {
    enabled = false
}
