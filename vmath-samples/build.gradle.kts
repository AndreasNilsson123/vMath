// Samples that use vmath with a real graphics API (LWJGL: GLFW and OpenGL). Part of the build only with -Psamples (settings.gradle.kts), because Gradle downloads LWJGL
// for it and running it needs a graphics driver; docs/SAMPLES.md describes the samples and how to run them.
//
//   ./gradlew -Psamples :vmath-samples:run                                      the launcher with its menu
//   ./gradlew -Psamples :vmath-samples:run --args="--demo city"                 one demo, interactive
//   ./gradlew -Psamples :vmath-samples:run --args="--demo city --frames 600"    a scripted run, the numbers, then exit
//   ./gradlew -Psamples :vmath-samples:smoke                                    every demo for a few frames, checked

plugins {
    application
}

val jdk = property("vmath.jdk").toString().toInt()

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(jdk)) }
}

// the LWJGL natives of the machine that builds it
val os = org.gradle.internal.os.OperatingSystem.current()
val arm = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
val lwjglNatives = when {
    os.isWindows -> if (arm) "natives-windows-arm64" else "natives-windows"
    os.isMacOsX -> if (arm) "natives-macos-arm64" else "natives-macos"
    else -> if (arm) "natives-linux-arm64" else "natives-linux"
}

dependencies {
    implementation(project(":vmath-all"))
    // class-retention marker on some types of the library; not needed at run time
    compileOnly(project(":vmath-annotations"))
    // optional SIMD kernels, found through FrustumKernels.best() when the incubator module is enabled (see applicationDefaultJvmArgs)
    runtimeOnly(project(":vmath-simd"))
    implementation(platform(libs.lwjgl.bom))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

val demoJvmArgs = listOf("--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", "-Xmx2g")

application {
    mainClass.set("vmath.samples.Launcher")
    applicationDefaultJvmArgs = demoJvmArgs
}

tasks.test {
    useJUnitPlatform()
    // the tests of the pieces that need no window; the demos themselves are checked by the smoke task
    jvmArgs("--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED")
    systemProperty("java.awt.headless", "true")
    // docs/DEMOS.md and .run/ are read by the registry test
    systemProperty("vmath.repoRoot", rootProject.projectDir.absolutePath)
}

// Runs every registered demo for a few scripted frames and fails on an OpenGL error, on allocation above the budget of the demo, or on a blank
// frame. Needs a display and an OpenGL 4.5 driver, so it is not part of any other task.
tasks.register<JavaExec>("smoke") {
    group = "verification"
    description = "Runs every demo for a few frames and checks it (needs a display and OpenGL 4.5)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("vmath.samples.Launcher")
    jvmArgs(demoJvmArgs)
    args("--smoke")
}

// Compiles and runs the shaders of every line strategy (vmath.lines) at every GLSL version from 3.30 to what the driver gives and compares the pixels with the reference of the
// library; --bench adds the cost of each strategy. Needs a display and an OpenGL 4.6 driver (docs/LINES.md).
//
//   ./gradlew -Psamples :vmath-samples:lineCheck
//   ./gradlew -Psamples :vmath-samples:lineCheck --args="--bench"
tasks.register<JavaExec>("mapCheck") {
    group = "verification"
    description = "Runs the map shaders (symbols, areas) on the OpenGL driver and compares them with their CPU models (needs a display and OpenGL 4.6)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("vmath.samples.verify.MapGpuCheck")
    jvmArgs(demoJvmArgs)
}

tasks.register<JavaExec>("lineCheck") {
    group = "verification"
    description = "Runs the line shaders on the OpenGL driver and compares them with the reference (needs a display and OpenGL 4.6)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("vmath.samples.verify.LineGpuCheck")
    jvmArgs(demoJvmArgs)
}
