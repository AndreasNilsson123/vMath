// vmath: immutable, Valhalla-ready 3D math.
//
//   ./gradlew build                 latest JDK, plain records (default)
//   ./gradlew build -Pvalhalla      JDK 28 (early access), real `value record`s, --enable-preview
//
// The library is four modules (vmath-core, vmath-geo, vmath-scene, vmath-render; each is built by gradle/vmath-module.gradle.kts) and the aggregate module `vmath` (the project vmath-all).
// Each module has its own sources, float templates, tests and test templates; the vmath-codegen tool turns the templates into the float and double types under build/generated/ of the
// module. This root project has no sources: it holds what spans the modules: the merged coverage, the mutation tests and the API compatibility check.

plugins {
    base
    `java-base`
    jacoco
}

// Maven coordinates: the group is the Central-verifiable GitHub namespace (docs/PUBLISHING.md); the JPMS module name stays `vmath`.
// The next release is 0.2.0 because v0.1.0 is already tagged (docs/VERSIONING.md).
group = "io.github.andreasnilsson123"
version = "0.2.0-SNAPSHOT"

// the parts, bottom to top (the order is the layering of docs/ROADMAP.md INF-6)
val parts = listOf(":vmath-core", ":vmath-geo", ":vmath-scene", ":vmath-render")
parts.forEach { evaluationDependsOn(it) }

// Inside IntelliJ (it sets these properties for the Gradle it starts) the project also gets what the IDE needs: gradle/idea.gradle.kts runs the generator after each sync.
// The command line never reads that script, so it never needs the plugin it uses.
if (providers.systemProperty("idea.active").isPresent || providers.systemProperty("idea.sync.active").isPresent) {
    apply(from = "gradle/idea.gradle.kts")
}

val valhalla = providers.gradleProperty("valhalla").isPresent
val baselineJdk = property("vmath.jdk").toString().toInt()
val valhallaJdk = property("vmath.valhallaJdk").toString().toInt()

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

// ---------------------------------------------------------------- coverage (JaCoCo)
//
//   ./gradlew test jacocoTestReport     build/reports/jacoco/test/html/index.html and jacocoTestReport.xml
//   ./gradlew coverageSummary           the line and branch coverage per package, printed (needs the XML report)
//   ./gradlew jacocoTestCoverageVerification   fails if a package falls below its floor (docs/COVERAGE.md says how the floors were set)
// Not applied to the -Pvalhalla build: the JaCoCo release in use does not read JDK 28 class files.

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// The classes measured are those of the four parts (this project has none of its own), and the execution data is merged over the tests of all four: a test lives in the lowest module
// that has everything it uses, but it covers code in the modules below too.
val partClasses = files(parts.map { project(it).the<SourceSetContainer>()["main"].output.classesDirs })
val partSources = files(parts.map { project(it).the<SourceSetContainer>()["main"].java.srcDirs })
val partTests = parts.map { project(it).tasks.named("test") }
val partExecutionData = files(parts.map { project(it).layout.buildDirectory.file("jacoco/test.exec") })

val jacocoTestReport = tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Merges the coverage of the tests of all modules into build/reports/jacoco/test."
    dependsOn(partTests)
    executionData.setFrom(partExecutionData)
    classDirectories.setFrom(partClasses)
    sourceDirectories.setFrom(partSources)
    enabled = !valhalla
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/test/html"))
        csv.required.set(false)
    }
}

// Floors per package, set 2 to 3 points under what was measured (see docs/COVERAGE.md) so that untested new code is noticed without the build failing on noise.
// Line coverage first, then branch coverage.
val coverageFloors = mapOf(
    "vmath/anim" to (0.94 to 0.90), "vmath/bulk" to (0.90 to 0.80), "vmath/camera" to (0.97 to 0.89), "vmath/lighting" to (0.97 to 0.86), "vmath/sky" to (0.97 to 0.96), "vmath/map" to (0.92 to 0.80), "vmath/color" to (0.94 to 0.85),
    "vmath/core" to (0.96 to 0.93), "vmath/geo" to (0.95 to 0.90), "vmath/gl" to (0.90 to 0.85), "vmath/lines" to (0.90 to 0.80), "vmath/gltf" to (0.94 to 0.88),
    "vmath/gpucull" to (0.93 to 0.89), "vmath/mem" to (0.84 to 0.80), "vmath/mesh" to (0.95 to 0.90), "vmath/occlusion" to (0.95 to 0.85),
    "vmath/pack" to (0.93 to 0.90), "vmath/physics" to (0.95 to 0.86), "vmath/spatial" to (0.92 to 0.82), "vmath/tex" to (0.95 to 0.85), "vmath/util" to (0.97 to 0.92)
)

val jacocoTestCoverageVerification = tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    group = "verification"
    description = "Fails if a package of the merged coverage falls below its floor."
    dependsOn(partTests)
    executionData.setFrom(partExecutionData)
    classDirectories.setFrom(partClasses)
    sourceDirectories.setFrom(partSources)
    enabled = !valhalla
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
    dependsOn(jacocoTestReport)
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
    dependsOn(parts.map { project(it).tasks.named("testClasses") })
    enabled = !valhalla
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(baselineJdk)) })
    classpath = pitest
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
    val classes = providers.gradleProperty("mutation.classes")
    val tests = providers.gradleProperty("mutation.tests").orElse("vmath.*")
    val threads = providers.gradleProperty("mutation.threads").orElse("4")
    val report = layout.buildDirectory.dir("reports/pitest")
    val testClasspath = files(parts.map { project(it).the<SourceSetContainer>()["test"].runtimeClasspath })
    val mainClasses = partClasses
    val partJars = parts.map { project(it).tasks.named<Jar>("jar").flatMap { jar -> jar.archiveFile } }
    val projectDir = layout.projectDirectory
    val sourceDirs = partSources.files + parts.map { project(it).layout.projectDirectory.dir("src/test/java").asFile }
    val generatedDirs = parts.flatMap { listOf(project(it).layout.buildDirectory.dir("generated/sources/vmath/main"), project(it).layout.buildDirectory.dir("generated/sources/vmath/test")) }
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
            "--sourceDirs", (sourceDirs + generatedDirs.map { it.get().asFile }).joinToString(",") { it.absolutePath },
            "--threads", threads.get(),
            "--outputFormats", "HTML,XML",
            "--timestampedReports", "false",
            "--jvmArgs", "-Djoml.nounsafe=true"
        )
    })
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

// The tasks below use only local values (providers, files, flags), never script-level members, so that the configuration cache can store them.
run {
    val tag = providers.gradleProperty("japicmp.baselineTag").orElse("v0.1.0")
    val baselineOverride = providers.gradleProperty("japicmp.baseline")
    val allowBreak = providers.gradleProperty("japicmp.allowBreak").isPresent
    val isValhalla = valhalla
    val baselineDir = layout.buildDirectory.dir("baseline")
    val pinFile = layout.projectDirectory.file("gradle/baseline-commits.txt")
    val windows = org.gradle.internal.os.OperatingSystem.current().isWindows
    val offline = gradle.startParameter.isOffline

    // Whether the baseline tag exists in this checkout (false when git is missing or this is a source archive).
    val tagExists = providers.exec {
        commandLine("git", "rev-parse", "--verify", "--quiet", "refs/tags/${tag.get()}")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.isNotBlank() }.orElse(false)
    val usesBuiltBaseline = baselineOverride.map { false }.orElse(!isValhalla)
    val buildBaseline = tagExists.zip(usesBuiltBaseline) { exists, built -> exists && built }

    val export = tasks.register<Exec>("exportBaselineSource") {
        group = "verification"
        description = "Exports the baseline tag's sources with git archive."
        val enabled = buildBaseline
        onlyIf { enabled.get() }
        val tar = baselineDir.map { it.file("src.tar") }
        outputs.file(tar)
        val tagName = tag
        // The baseline is pinned: gradle/baseline-commits.txt records "<tag> <commit>", and a tag that has been moved to another commit stops the build instead of silently changing what the API is compared with.
        val expectedCommit = providers.fileContents(pinFile).asText.map { text ->
            text.lines().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size == 2 && it[0] == tagName.get() }?.get(1) ?: ""
        }.orElse("")
        val actualCommit = enabled.flatMap {
            if (it) providers.exec { commandLine("git", "rev-parse", "--verify", "${tagName.get()}^{commit}"); isIgnoreExitValue = true }.standardOutput.asText.map { s -> s.trim() }
            else providers.provider { "" }
        }
        doFirst {
            tar.get().asFile.parentFile.mkdirs()
            val expected = expectedCommit.get()
            if (expected.isNotEmpty()) {
                check(actualCommit.get() == expected) {
                    "The baseline tag ${tagName.get()} points at ${actualCommit.get()} but gradle/baseline-commits.txt pins $expected. If the tag was moved on purpose, update the file and say why in the commit."
                }
            }
        }
        commandLine("git", "archive", "--format=tar", "--output=${tar.get().asFile.absolutePath}", tagName.get())
    }

    val unpack = tasks.register<Sync>("unpackBaselineSource") {
        val enabled = buildBaseline
        onlyIf { enabled.get() }
        dependsOn(export)
        from(tarTree(baselineDir.map { it.file("src.tar") }))
        into(baselineDir.map { it.dir("src") })
    }

    val buildJar = tasks.register<Exec>("buildBaselineJar") {
        group = "verification"
        description = "Builds the jar of the baseline tag in a separate Gradle build."
        val enabled = buildBaseline
        onlyIf { enabled.get() }
        dependsOn(unpack)
        val src = baselineDir.map { it.dir("src").asFile }
        val out = baselineDir.map { it.dir("out").asFile.absolutePath }
        // The nested build must not write into this build's output directory, whatever vmath.buildRoot says.
        val extra = if (offline) listOf("--offline") else emptyList()
        doFirst {
            val dir = src.get()
            workingDir(dir)
            val wrapper = if (windows) listOf("cmd", "/c", File(dir, "gradlew.bat").absolutePath)
            else listOf("sh", File(dir, "gradlew").absolutePath)
            commandLine(wrapper + listOf("jar", "-Pvmath.buildRoot=${out.get()}", "--console=plain") + extra)
        }
    }

    val stage = tasks.register<Copy>("stageBaselineJar") {
        val enabled = buildBaseline
        onlyIf { enabled.get() }
        dependsOn(buildJar)
        from(baselineDir.map { it.dir("out/vmath/libs") }) {
            include("vmath-*.jar")
            exclude("*-sources.jar")
        }
        into(baselineDir)
        rename { "vmath-baseline.jar" }
    }

    tasks.register<JavaExec>("japicmp") {
        group = "verification"
        description = "Fails on binary-incompatible changes to the public API since the baseline."
        // the baseline is one jar of the whole library; the new version is the jars of the four parts together
        val partJars = parts.map { project(it).tasks.named<Jar>("jar") }
        dependsOn(partJars)
        dependsOn(stage) // a no-op unless the baseline is built from the tag
        classpath = configurations["japicmpCli"]
        mainClass.set("japicmp.JApiCmp")
        val report = layout.buildDirectory.dir("reports/japicmp")
        val jarFiles = partJars.map { jar -> jar.flatMap { it.archiveFile } }
        val baselinePath = baselineOverride.map { layout.projectDirectory.file(it) }.orElse(baselineDir.map { it.file("vmath-baseline.jar") })
        val tagName = tag
        onlyIf {
            val ok = !isValhalla && baselinePath.get().asFile.exists()
            if (!ok) {
                logger.lifecycle("japicmp skipped: no baseline (tag '${tagName.get()}' missing or no -Pjapicmp.baseline jar)" +
                        if (isValhalla) ", and the Valhalla profile changes record kinds" else "")
            }
            ok
        }
        doFirst { report.get().asFile.mkdirs() }
        argumentProviders.add(CommandLineArgumentProvider {
            buildList {
                addAll(listOf("--old", baselinePath.get().asFile.absolutePath))
                addAll(listOf("--new", jarFiles.joinToString(";") { it.get().asFile.absolutePath }))
                addAll(listOf("--only-modified", "-a", "public"))
                addAll(listOf("--exclude", "@vmath.annotations.Experimental")) // docs/VERSIONING.md
                addAll(listOf("--html-file", report.get().file("index.html").asFile.absolutePath))
                if (!allowBreak) {
                    addAll(listOf("--error-on-binary-incompatibility", "--error-on-source-incompatibility"))
                }
            }
        })
    }
}

tasks.check {
    dependsOn("japicmp")
    dependsOn(jacocoTestCoverageVerification)
}
