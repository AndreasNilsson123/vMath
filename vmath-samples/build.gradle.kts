// Samples that use vmath with a real graphics API (LWJGL: GLFW and OpenGL). Part of the build only with -Psamples (settings.gradle.kts), because Gradle downloads LWJGL
// for it and running it needs a graphics driver; docs/SAMPLES.md describes the samples and how to run them.
//
//   ./gradlew -Psamples :vmath-samples:run                           the million-instance city, interactive
//   ./gradlew -Psamples :vmath-samples:run --args="--frames 600"     a scripted flight over the city, then the numbers, then exit

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
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

application {
    mainClass.set("vmath.samples.MillionInstances")
    applicationDefaultJvmArgs = listOf("--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", "-Xmx2g")
}
