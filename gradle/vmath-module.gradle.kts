// The build of one part of the library (vmath-core, vmath-geo, vmath-scene, vmath-render), applied by each of them after it sets
//
//   extra["vmathBelow"]          the projects whose templates and structs it uses, as a list of project names (":vmath-core", ...)
//   extra["publishDescription"]  the description in the POM
//
// and declares its `api` dependencies on those projects. What it does: the Java toolchain and compiler flags, the generator run for the float templates and test templates of the module (the
// families and the @GpuStruct records of the modules below are read, not generated), the Valhalla profile, the value-type validator, the tests (a test lives in the lowest module that has
// everything it uses; the tests of a module see the test classes of the modules below), the javadoc lint and the publication. The root project merges the coverage of all of them.
//
//   extra["vmathTestJars"] = true   (optional) the tests need the jars of the whole library: ModuleDescriptorTest and PackageLayeringTest

apply(plugin = "java-library")

val valhalla = providers.gradleProperty("valhalla").isPresent
val baselineJdk = property("vmath.jdk").toString().toInt()
val valhallaJdk = property("vmath.valhallaJdk").toString().toInt()

@Suppress("UNCHECKED_CAST")
val belowPaths = project.extra["vmathBelow"] as List<String>
belowPaths.forEach { evaluationDependsOn(it) }
val below = belowPaths.map { rootProject.project(it) }
val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(if (valhalla) valhallaJdk else baselineJdk))
    withSourcesJar()
}

val codegen = configurations.create("codegen") {
    isCanBeConsumed = false
}

dependencies {
    // Class-retention markers only: nothing from here reaches the run time (the modules say `requires static`).
    add("compileOnly", project(":vmath-annotations"))
    // Checks the identity rules of the value types on the attributed syntax tree while the sources compile (docs/CODEGEN.md)
    add("annotationProcessor", project(":vmath-validator"))
    add("codegen", project(":vmath-codegen"))

    // the tests: JUnit, JOML as the oracle of the math tests, the annotations of the sample @GpuStruct records, the validator, and the test classes of the modules below
    add("testImplementation", platform(catalog.findLibrary("junit-bom").get()))
    add("testImplementation", catalog.findLibrary("junit-jupiter").get())
    add("testImplementation", catalog.findLibrary("joml").get())
    add("testRuntimeOnly", catalog.findLibrary("junit-launcher").get())
    add("testCompileOnly", project(":vmath-annotations"))
    add("testAnnotationProcessor", project(":vmath-validator"))
    below.forEach { add("testImplementation", it.the<SourceSetContainer>()["test"].output) }
}

// JOML's jar (the test oracle) is built for class-file version 46 and uses type annotations that javac reports with a [classfile] warning for every JOML class a test touches
tasks.named<JavaCompile>("compileTestJava") {
    options.compilerArgs.add("-Xlint:-classfile")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -exports is off because vmath.annotations.Experimental has class retention on purpose (japicmp reads it) while the annotations module is "requires static": with the lint on,
    // javac reports one warning per @Experimental class and nothing else. -processing is off because the validating processor supports every annotation without claiming any.
    // -implicit:class: Gradle's incremental compilation hands javac only the changed files, and the others it reads as sources are then "implicitly compiled", which javac reports
    // with a warning that -Werror turned into a failed build after a change in a module below (the class files are produced either way; the policy only names that).
    // -Werror: the library compiles without a single other warning, and it should stay that way.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-exports", "-Xlint:-processing", "-implicit:class", "-Werror"))
    if (valhalla) {
        options.compilerArgs.add("-Xlint:-preview")
        options.release.set(valhallaJdk)
        options.compilerArgs.add("--enable-preview")
    }
}

// ---------------------------------------------------------------- code generation

val generatedMain = layout.buildDirectory.dir("generated/sources/vmath/main")
val generatedTest = layout.buildDirectory.dir("generated/sources/vmath/test")

// local values only (no script-level members inside the lambdas), so that the configuration cache can store the task
val generateSources = run {
    val isValhalla = valhalla
    val jdk = baselineJdk
    val templates = layout.projectDirectory.dir("src/template/java")
    val handWritten = layout.projectDirectory.dir("src/main/java")
    val belowTemplates = below.map { it.layout.projectDirectory.dir("src/template/java") }
    val belowSources = below.map { it.layout.projectDirectory.dir("src/main/java") }
    val outMain = generatedMain
    val outTest = generatedTest
    val testTemplates = layout.projectDirectory.dir("src/testTemplate/java")
    val handWrittenTests = layout.projectDirectory.dir("src/test/java")
    val belowTestTemplates = below.map { it.layout.projectDirectory.dir("src/testTemplate/java") }
    val renames = rootProject.layout.projectDirectory.file("codegen-renames.properties")
    val launcher = extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(jdk)) }
    tasks.register<JavaExec>("generateSources") {
        group = "build"
        description = "Generates the float and double types from this module's templates."
        javaLauncher.set(launcher)
        classpath = codegen
        mainClass.set("vmath.codegen.Codegen")

        inputs.files(fileTree(templates))
        inputs.property("valhalla", isValhalla)
        // @GpuStruct records are found by scanning the hand-written sources of this module, and the ones of the modules below are read so that structs can refer to them
        inputs.dir(handWritten)
        belowTemplates.forEach { inputs.files(fileTree(it)) }
        belowSources.forEach { inputs.dir(it) }
        inputs.files(fileTree(testTemplates))
        inputs.files(fileTree(handWrittenTests))
        inputs.file(renames)
        belowTestTemplates.forEach { inputs.files(fileTree(it)) }
        outputs.dir(outMain)
        outputs.dir(outTest)

        argumentProviders.add(CommandLineArgumentProvider {
            buildList {
                addAll(listOf("--templates", templates.asFile.path, "--out", outMain.get().asFile.path))
                belowTemplates.forEach { addAll(listOf("--family-templates", it.asFile.path)) }
                belowSources.forEach { addAll(listOf("--gpu-register", it.asFile.path)) }
                addAll(listOf("--gpu", handWritten.asFile.path + "=" + outMain.get().asFile.path))
                addAll(listOf("--test-templates", testTemplates.asFile.path, "--test-out", outTest.get().asFile.path, "--renames", renames.asFile.path))
                belowTestTemplates.forEach { addAll(listOf("--family-templates", it.asFile.path)) }
                addAll(listOf("--gpu", handWrittenTests.asFile.path + "=" + outTest.get().asFile.path))
                if (isValhalla) {
                    addAll(listOf("--sources", handWritten.asFile.path, "--valhalla"))
                }
            }
        })
    }
}

extensions.configure<SourceSetContainer> {
    named("main") {
        if (valhalla) {
            // Hand-written sources pass through the generator too, so @ValueType becomes `value` there as well.
            java.setSrcDirs(listOf(files(generatedMain).builtBy(generateSources)))
        } else {
            java.srcDir(files(generatedMain).builtBy(generateSources))
        }
    }
    named("test") {
        java.srcDir(files(generatedTest).builtBy(generateSources))
    }
}

// IntelliJ: the generated roots are marked as generated (not edited by hand, left out of the version control views); the IDE itself runs generateSources after each sync (gradle/idea.gradle.kts)
apply(plugin = "idea")
extensions.configure<org.gradle.plugins.ide.idea.model.IdeaModel> {
    module {
        generatedSourceDirs.add(generatedMain.get().asFile)
        generatedSourceDirs.add(generatedTest.get().asFile)
    }
}

// ---------------------------------------------------------------- tests

apply(plugin = "jacoco")
extensions.configure<JacocoPluginExtension> {
    toolVersion = catalog.findVersion("jacoco").get().requiredVersion
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // a skipped test (the module jars, jdeps, a glslang compiler or the JIT is missing) is listed with its reason; CI also sets -Dvmath.requireEnvironment=true (vmath.Environment)
    testLogging {
        events("skipped")
        showStandardStreams = false
    }
    // a pattern such as --tests vmath.geo.* matches in one module only: the others must not fail on it
    filter.isFailOnNoMatchingTests = false
    // JOML's Unsafe fast path segfaults on heap buffers (Matrix3d.get(int, DoubleBuffer)); the oracle doesn't need it.
    systemProperty("joml.nounsafe", "true")
    // AllocationContractTest relaxes the paths that cross a non-inlined call with a value record on the -Pvalhalla build (see Alloc.assertNoAllocationPerElement)
    if (valhalla) {
        systemProperty("vmath.valhalla", "true")
        jvmArgs("--enable-preview")
    }
    // Extra JVM flags for the test JVM, e.g. -Pvmath.testJvmArgs="-XX:TieredStopAtLevel=1" to see which JIT settings the allocation contract tolerates.
    providers.gradleProperty("vmath.testJvmArgs").orNull?.let { jvmArgs(it.trim().split(Regex("\\s+"))) }
    // Forward -Dvmath.seed / -Dvmath.trials from the command line, e.g. a nightly job with a fresh seed.
    listOf("vmath.seed", "vmath.trials", "vmath.writeAssets", "vmath.writeDocs", "vmath.verbose", "vmath.alloc.force", "vmath.docs.all", "vmath.glslang", "vmath.requireEnvironment").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    // the coverage is merged over all modules by the root project (docs/COVERAGE.md); the Valhalla class files are not read by the JaCoCo release in use
    extensions.configure<JacocoTaskExtension> { isEnabled = !valhalla }
}

if (project.extra.has("vmathTestJars")) {
    // ModuleDescriptorTest and PackageLayeringTest inspect the real jars: the module path is what consumers use. The list is "name=path" pairs, the aggregate and then the parts.
    val parts = listOf(":vmath-core", ":vmath-geo", ":vmath-scene", ":vmath-render")
    evaluationDependsOn(":vmath-all")
    val moduleJars: List<Pair<String, TaskProvider<Jar>>> = (listOf(":vmath-all") + parts).map { it.trimStart(':') to rootProject.project(it).tasks.named<Jar>("jar") }
    tasks.withType<Test>().configureEach {
        dependsOn(moduleJars.map { it.second })
        val jarFiles = moduleJars.map { (name, jar) -> name to jar.flatMap { it.archiveFile } }
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Dvmath.jars=" + jarFiles.joinToString(File.pathSeparator) { (name, file) -> name + "=" + file.get().asFile.absolutePath })
        })
    }
}

if (valhalla) {
    tasks.withType<JavaExec>().configureEach {
        if (name != "generateSources") {
            jvmArgs("--enable-preview")
        }
    }
}

// ---------------------------------------------------------------- javadoc

// Javadoc is linted as part of `check`: broken references, bad HTML and malformed tags fail the build. Missing comments and missing @param tags on
// record components are not checked yet (docs/ROADMAP.md INF-5): "-missing" keeps the lint to what is wrong rather than what is absent.
tasks.withType<Javadoc>().configureEach {
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

tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))
