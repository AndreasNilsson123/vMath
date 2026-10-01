plugins {
    java
}

// -Pvalhalla: the core library is then compiled with preview features on JDK 28, so its consumers must be too
val valhalla = providers.gradleProperty("valhalla").isPresent

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(if (valhalla) 28 else 25)) }
}

val jmhVersion = "1.37"

dependencies {
    implementation(project(":"))
    compileOnly(project(":vmath-annotations")) // class-retention marker on some core types; not needed at run time
    // Optional SIMD kernels, found through FrustumKernels.best() when present and the incubator module is enabled.
    implementation(project(":vmath-simd"))
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if (valhalla) {
        options.release.set(28)
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
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(if (valhalla) 28 else 25)) })
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
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(if (valhalla) 28 else 25)) })
    jvmArgs((if (valhalla) "--add-modules=jdk.incubator.vector --enable-preview" else "--add-modules=jdk.incubator.vector").split(" "))
}
