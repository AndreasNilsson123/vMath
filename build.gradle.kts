// vmath: immutable, Valhalla-ready 3D math.
//
//   ./gradlew build                 latest JDK, plain records (default)
//   ./gradlew build -Pvalhalla      JDK 28 (early access), real `value record`s, --enable-preview
//
// Float templates in src/template/java and src/testTemplate/java are the single source of truth. The
// vmath-codegen tool turns them into the float and double types under build/generated/ at build time.

plugins {
    `java-library`
    jacoco
}

// Maven coordinates: the group is the Central-verifiable GitHub namespace (docs/PUBLISHING.md); the JPMS module name stays `vmath`.
// The next release is 0.2.0 because v0.1.0 is already tagged (docs/VERSIONING.md).
group = "io.github.andreasnilsson123"
version = "0.2.0-SNAPSHOT"

val valhalla = providers.gradleProperty("valhalla").isPresent
val baselineJdk = 25
val valhallaJdk = 28

allprojects {
    group = rootProject.group
    version = rootProject.version
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
    testImplementation(libs.joml)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(if (valhalla) valhallaJdk else baselineJdk))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -exports: vmath.annotations.Experimental has class retention on purpose (japicmp reads it), but the annotations module is "requires static", so javac would warn on every use
    // -Werror: the library and its tests compile without a single warning, and it should stay that way
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-preview", "-Xlint:-exports", "-Werror"))
}

// JOML's jar (the test oracle) is built for class-file version 46 and uses type annotations that javac reports with a [classfile] warning for every JOML class a test touches
tasks.compileTestJava {
    options.compilerArgs.add("-Xlint:-classfile")
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
    listOf("vmath.seed", "vmath.trials", "vmath.writeAssets", "vmath.writeDocs").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// ---------------------------------------------------------------- coverage (JaCoCo)
//
//   ./gradlew test jacocoTestReport     build/reports/jacoco/test/html/index.html and jacocoTestReport.xml
//   ./gradlew coverageSummary           the line and branch coverage per package, printed (needs the XML report)
//   ./gradlew jacocoTestCoverageVerification   fails if a package falls below its floor (docs/COVERAGE.md says how the floors were set)
// Not applied to the -Pvalhalla build: the JaCoCo release in use does not read JDK 28 class files.

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    onlyIf { !valhalla }
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.test {
    extensions.configure<JacocoTaskExtension> { isEnabled = !valhalla }
    finalizedBy(tasks.jacocoTestReport)
}

// Floors per package, set 2 to 3 points under what was measured (see docs/COVERAGE.md) so that untested new code is noticed without the build failing on noise.
// Line coverage first, then branch coverage.
val coverageFloors = mapOf(
    "vmath/anim" to (0.94 to 0.90), "vmath/bulk" to (0.90 to 0.80), "vmath/camera" to (0.97 to 0.89), "vmath/color" to (0.94 to 0.85),
    "vmath/core" to (0.96 to 0.93), "vmath/geo" to (0.95 to 0.90), "vmath/gl" to (0.90 to 0.85), "vmath/gltf" to (0.94 to 0.88),
    "vmath/gpucull" to (0.93 to 0.89), "vmath/mem" to (0.84 to 0.80), "vmath/mesh" to (0.95 to 0.90), "vmath/occlusion" to (0.95 to 0.85),
    "vmath/pack" to (0.93 to 0.90), "vmath/spatial" to (0.92 to 0.82), "vmath/tex" to (0.95 to 0.85)
)

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    onlyIf { !valhalla }
    violationRules {
        rule {
            element = "BUNDLE"
            limit { counter = "LINE"; minimum = "0.94".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.87".toBigDecimal() }
        }
        coverageFloors.forEach { (pkg, floors) ->
            rule {
                element = "PACKAGE"
                includes = listOf(pkg.replace('/', '.'))
                limit { counter = "LINE"; minimum = floors.first.toBigDecimal() }
                limit { counter = "BRANCH"; minimum = floors.second.toBigDecimal() }
            }
        }
    }
}

tasks.register("coverageSummary") {
    group = "verification"
    description = "Prints line and branch coverage per package from the JaCoCo XML report (run test jacocoTestReport first)."
    dependsOn(tasks.jacocoTestReport)
    val xml = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
    doLast {
        val file = xml.get().asFile
        if (!file.exists()) {
            logger.lifecycle("no coverage report (the Valhalla profile does not produce one)")
            return@doLast
        }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val doc = factory.newDocumentBuilder().parse(file)
        fun pct(el: org.w3c.dom.Element, type: String): String {
            val counters = el.childNodes
            for (i in 0 until counters.length) {
                val c = counters.item(i)
                if (c is org.w3c.dom.Element && c.tagName == "counter" && c.getAttribute("type") == type) {
                    val missed = c.getAttribute("missed").toDouble()
                    val covered = c.getAttribute("covered").toDouble()
                    return "%5.1f%% (%d/%d)".format(100 * covered / (missed + covered), covered.toInt(), (missed + covered).toInt())
                }
            }
            return "    n/a"
        }
        val packages = doc.getElementsByTagName("package")
        for (i in 0 until packages.length) {
            val p = packages.item(i) as org.w3c.dom.Element
            logger.lifecycle("%-18s line %s   branch %s".format(p.getAttribute("name"), pct(p, "LINE"), pct(p, "BRANCH")))
        }
        val bundle = doc.documentElement
        logger.lifecycle("%-18s line %s   branch %s".format("TOTAL", pct(bundle, "LINE"), pct(bundle, "BRANCH")))
    }
}

// ---------------------------------------------------------------- mutation testing (PIT)
//
//   ./gradlew mutationTest -Pmutation.classes=vmath.core.Morton -Pmutation.tests='vmath.core.*'
//
// PIT changes the compiled code (flips a comparison, drops an addition, ...) and re-runs the tests; a mutant that no test notices is a hole in the tests.
// It is slow, so it is a task you run on the classes you changed or want to audit (docs/COVERAGE.md has the results so far), not part of `check`.
// -Pmutation.classes and -Pmutation.tests take PIT patterns (comma separated, * wildcards); the report is build/reports/pitest/index.html.
// Not available on the -Pvalhalla build.

val pitest = configurations.create("pitest") {
    isCanBeConsumed = false
}

dependencies {
    pitest(libs.pitest.command.line)
    pitest(libs.pitest.junit5.plugin)
}

tasks.register<JavaExec>("mutationTest") {
    group = "verification"
    description = "Runs PIT mutation testing on the classes given by -Pmutation.classes with the tests given by -Pmutation.tests."
    dependsOn(tasks.testClasses, tasks.jar)
    onlyIf { !valhalla }
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(baselineJdk)) })
    classpath = pitest
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
    val classes = providers.gradleProperty("mutation.classes")
    val tests = providers.gradleProperty("mutation.tests").orElse("vmath.*")
    val threads = providers.gradleProperty("mutation.threads").orElse("4")
    val report = layout.buildDirectory.dir("reports/pitest")
    val testClasspath = sourceSets.test.get().runtimeClasspath
    val mainClasses = sourceSets.main.get().output.classesDirs
    doFirst {
        if (!classes.isPresent) {
            throw GradleException("pass the classes to mutate, for example -Pmutation.classes=vmath.core.Morton")
        }
        report.get().asFile.mkdirs()
    }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--reportDir", report.get().asFile.path,
            "--targetClasses", classes.get(),
            "--mutableCodePaths", mainClasses.files.filter { it.exists() }.joinToString(",") { it.absolutePath },   // only production classes: tests in the same package are not mutated
            "--targetTests", tests.get(),
            "--classPath", testClasspath.files.filter { it.exists() }.joinToString(",") { it.absolutePath },
            "--sourceDirs", listOf("src/main/java", "src/test/java", generatedMain.get().asFile.path, generatedTest.get().asFile.path).joinToString(",") { file(it).absolutePath },
            "--threads", threads.get(),
            "--outputFormats", "HTML,XML",
            "--timestampedReports", "false",
            "--jvmArgs", "-Djoml.nounsafe=true,-Dvmath.jar=" + tasks.jar.get().archiveFile.get().asFile.absolutePath
        )
    })
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
    japicmpCli("${libs.japicmp.get().module}:${libs.versions.japicmp.get()}:jar-with-dependencies")
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
    // The baseline is pinned: gradle/baseline-commits.txt records "<tag> <commit>", and a tag that has been moved to another commit stops the build instead of silently changing what the API is compared with.
    doFirst {
        val pin = layout.projectDirectory.file("gradle/baseline-commits.txt").asFile
        val expected = if (pin.exists()) pin.readLines().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size == 2 && it[0] == japicmpTag.get() }?.get(1) else null
        if (expected != null) {
            val actual = providers.exec {
                commandLine("git", "rev-parse", "--verify", "${japicmpTag.get()}^{commit}")
            }.standardOutput.asText.get().trim()
            check(actual == expected) {
                "The baseline tag ${japicmpTag.get()} points at $actual but gradle/baseline-commits.txt pins $expected. If the tag was moved on purpose, update the file and say why in the commit."
            }
        }
    }
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
            addAll(listOf("--exclude", "@vmath.annotations.Experimental")) // docs/VERSIONING.md
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
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// ---------------------------------------------------------------- publishing (docs/PUBLISHING.md)

extra["publishDescription"] = "Immutable, Valhalla-ready 3D math and graphics foundation for Java: vectors, matrices, geometry, culling, cameras, meshes, animation and GPU data layouts."
apply(from = "gradle/publishing.gradle.kts")
