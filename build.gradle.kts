// vmath: immutable, Valhalla-ready 3D math.
//
//   ./gradlew build                 latest JDK, plain records (default)
//   ./gradlew build -Pvalhalla      JDK 28 (early access), real `value record`s, --enable-preview
//
// Float templates in src/template/java and src/testTemplate/java are the single source of truth. The
// vmath-codegen tool turns them into the float and double types under build/generated/ at build time.

plugins {
    `java-library`
}

group = "vmath"
version = "0.1.0-SNAPSHOT"

val valhalla = providers.gradleProperty("valhalla").isPresent
val baselineJdk = 25
val valhallaJdk = 28

allprojects {
    repositories {
        mavenCentral()
    }
    // Optional: keep build output outside a cloud-synced checkout (OneDrive locks files in build/ mid-build),
    // e.g. ./gradlew build -Pvmath.buildRoot=C:/tmp/vmath-build
    providers.gradleProperty("vmath.buildRoot").orNull?.let { root ->
        layout.buildDirectory.set(file(root).resolve(project.name))
    }
}

val codegen = configurations.create("codegen") {
    isCanBeConsumed = false
}

dependencies {
    // Source-retention annotations only: nothing from here reaches the runtime classpath.
    compileOnly(project(":vmath-annotations"))
    codegen(project(":vmath-codegen"))

    // The test sources declare sample @GpuStruct records
    testCompileOnly(project(":vmath-annotations"))

    // JOML is only the test oracle; nothing in main depends on it.
    testImplementation("org.joml:joml:1.10.8")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(if (valhalla) valhallaJdk else baselineJdk))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-preview"))
}

// Javadoc is linted as part of `check`: broken references, bad HTML and malformed tags fail the build. Missing comments and missing @param tags on
// record components are not checked yet (see docs/ROADMAP.md INF-5): "-missing" keeps the lint to what is wrong rather than what is absent.
tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Xwerror", true)
        if (valhalla) {
            addBooleanOption("-enable-preview", true)
            addStringOption("source", valhallaJdk.toString())
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // JOML's Unsafe fast path segfaults on heap buffers (Matrix3d.get(int, DoubleBuffer)); the oracle doesn't need it.
    systemProperty("joml.nounsafe", "true")
    // AllocationContractTest relaxes the paths that cross a non-inlined call with a value record on the -Pvalhalla build (see Alloc.assertNoAllocationPerElement)
    if (valhalla) {
        systemProperty("vmath.valhalla", "true")
    }
    // ModuleDescriptorTest inspects the real jar: the module path is what consumers use.
    dependsOn(tasks.jar)
    systemProperty("vmath.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    // Forward -Dvmath.seed / -Dvmath.trials from the command line, e.g. a nightly job with a fresh seed.
    listOf("vmath.seed", "vmath.trials").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// ---------------------------------------------------------------- code generation

val generatedMain = layout.buildDirectory.dir("generated/sources/vmath/main")
val generatedTest = layout.buildDirectory.dir("generated/sources/vmath/test")

val generateSources = tasks.register<JavaExec>("generateSources") {
    group = "build"
    description = "Generates the float and double types (and their tests) from the templates."
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(baselineJdk)) }
    javaLauncher.set(launcher)
    classpath = codegen
    mainClass.set("vmath.codegen.Codegen")

    val templates = layout.projectDirectory.dir("src/template/java")
    val testTemplates = layout.projectDirectory.dir("src/testTemplate/java")
    val handWritten = layout.projectDirectory.dir("src/main/java")
    val handWrittenTests = layout.projectDirectory.dir("src/test/java")
    val renames = layout.projectDirectory.file("codegen-renames.properties")

    inputs.dir(templates)
    inputs.dir(testTemplates)
    inputs.file(renames)
    inputs.property("valhalla", valhalla)
    // @GpuStruct records are found by scanning the hand-written sources (main and test)
    inputs.dir(handWritten)
    inputs.dir(handWrittenTests)
    outputs.dir(generatedMain)
    outputs.dir(generatedTest)

    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            addAll(listOf("--templates", templates.asFile.path, "--test-templates", testTemplates.asFile.path))
            addAll(listOf("--out", generatedMain.get().asFile.path, "--test-out", generatedTest.get().asFile.path))
            addAll(listOf("--renames", renames.asFile.path))
            addAll(listOf("--gpu", handWritten.asFile.path + "=" + generatedMain.get().asFile.path))
            addAll(listOf("--gpu", handWrittenTests.asFile.path + "=" + generatedTest.get().asFile.path))
            if (valhalla) {
                addAll(listOf("--sources", handWritten.asFile.path, "--valhalla"))
            }
        }
    })
}

sourceSets {
    main {
        if (valhalla) {
            // Hand-written sources pass through the generator too, so @ValueType becomes `value` there as well.
            java.setSrcDirs(listOf(files(generatedMain).builtBy(generateSources)))
        } else {
            java.srcDir(files(generatedMain).builtBy(generateSources))
        }
    }
    test {
        java.srcDir(files(generatedTest).builtBy(generateSources))
    }
}

// ---------------------------------------------------------------- Valhalla mode

if (valhalla) {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(valhallaJdk)
        options.compilerArgs.add("--enable-preview")
    }
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-preview")
    }
    tasks.withType<JavaExec>().configureEach {
        if (name != "generateSources") {
            jvmArgs("--enable-preview")
        }
    }
}

// ---------------------------------------------------------------- API compatibility (japicmp)
//
// The public API is compared with a baseline jar built from a git tag (default v0.1.0, see docs/API-COMPAT.md).
//   ./gradlew japicmp                              compare against the tag's jar (built on demand)
//   ./gradlew japicmp -Pjapicmp.baseline=<jar>     compare against a jar you already have
//   ./gradlew japicmp -Pjapicmp.baselineTag=v0.2.0 use another tag
//   ./gradlew japicmp -Pjapicmp.allowBreak         report breaking changes without failing the build
// Without a baseline (no tag, no jar) the task is skipped with a message, so fresh clones still build.

val japicmpCli = configurations.create("japicmpCli") {
    isCanBeConsumed = false
}

dependencies {
    japicmpCli("com.github.siom79.japicmp:japicmp:0.23.1:jar-with-dependencies")
}

val japicmpTag = providers.gradleProperty("japicmp.baselineTag").orElse("v0.1.0")
val japicmpBaselineOverride = providers.gradleProperty("japicmp.baseline")
val japicmpAllowBreak = providers.gradleProperty("japicmp.allowBreak").isPresent
val baselineDir = layout.buildDirectory.dir("baseline")

/** Whether the baseline tag exists in this checkout (false when git is missing or this is a source archive). */
val baselineTagExists: Boolean by lazy {
    try {
        providers.exec {
            commandLine("git", "rev-parse", "--verify", "--quiet", "refs/tags/${japicmpTag.get()}")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().isNotBlank()
    } catch (e: Exception) {
        false
    }
}

val baselineJar = baselineDir.map { it.file("vmath-baseline.jar").asFile }
val usesBuiltBaseline = !japicmpBaselineOverride.isPresent && !valhalla

val exportBaselineSource = tasks.register<Exec>("exportBaselineSource") {
    group = "verification"
    description = "Exports the baseline tag's sources with git archive."
    onlyIf { usesBuiltBaseline && baselineTagExists }
    val tar = baselineDir.map { it.file("src.tar") }
    outputs.file(tar)
    doFirst { baselineDir.get().asFile.mkdirs() }
    commandLine("git", "archive", "--format=tar", "--output=${tar.get().asFile.absolutePath}", japicmpTag.get())
}

val unpackBaselineSource = tasks.register<Sync>("unpackBaselineSource") {
    onlyIf { usesBuiltBaseline && baselineTagExists }
    dependsOn(exportBaselineSource)
    from(tarTree(baselineDir.map { it.file("src.tar") }))
    into(baselineDir.map { it.dir("src") })
}

val buildBaselineJar = tasks.register<Exec>("buildBaselineJar") {
    group = "verification"
    description = "Builds the jar of the baseline tag in a separate Gradle build."
    onlyIf { usesBuiltBaseline && baselineTagExists }
    dependsOn(unpackBaselineSource)
    val src = baselineDir.map { it.dir("src").asFile }
    val out = baselineDir.map { it.dir("out").asFile.absolutePath }
    val windows = org.gradle.internal.os.OperatingSystem.current().isWindows
    // The nested build must not write into this build's output directory, whatever vmath.buildRoot says.
    val extra = if (gradle.startParameter.isOffline) listOf("--offline") else emptyList()
    doFirst {
        val dir = src.get()
        workingDir(dir)
        val wrapper = if (windows) listOf("cmd", "/c", File(dir, "gradlew.bat").absolutePath)
        else listOf("sh", File(dir, "gradlew").absolutePath)
        commandLine(wrapper + listOf("jar", "-Pvmath.buildRoot=${out.get()}", "--console=plain") + extra)
    }
}

val stageBaselineJar = tasks.register<Copy>("stageBaselineJar") {
    onlyIf { usesBuiltBaseline && baselineTagExists }
    dependsOn(buildBaselineJar)
    from(baselineDir.map { it.dir("out/vmath/libs") }) {
        include("vmath-*.jar")
        exclude("*-sources.jar")
    }
    into(baselineDir)
    rename { "vmath-baseline.jar" }
}

val japicmp = tasks.register<JavaExec>("japicmp") {
    group = "verification"
    description = "Fails on binary-incompatible changes to the public API since the baseline."
    dependsOn(tasks.jar)
    if (usesBuiltBaseline) {
        dependsOn(stageBaselineJar)
    }
    classpath = japicmpCli
    mainClass.set("japicmp.JApiCmp")
    val report = layout.buildDirectory.dir("reports/japicmp")
    val baselinePath = japicmpBaselineOverride.map { file(it) }.orElse(baselineJar)
    onlyIf {
        val ok = !valhalla && baselinePath.get().exists()
        if (!ok) {
            logger.lifecycle("japicmp skipped: no baseline (tag '${japicmpTag.get()}' missing or no -Pjapicmp.baseline jar)" +
                    if (valhalla) ", and the Valhalla profile changes record kinds" else "")
        }
        ok
    }
    doFirst { report.get().asFile.mkdirs() }
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            addAll(listOf("--old", baselinePath.get().absolutePath))
            addAll(listOf("--new", tasks.jar.get().archiveFile.get().asFile.absolutePath))
            addAll(listOf("--only-modified", "-a", "public"))
            addAll(listOf("--html-file", report.get().file("index.html").asFile.absolutePath))
            if (!japicmpAllowBreak) {
                addAll(listOf("--error-on-binary-incompatibility", "--error-on-source-incompatibility"))
            }
        }
    })
}

tasks.check {
    dependsOn(japicmp)
    dependsOn(tasks.javadoc)
}
