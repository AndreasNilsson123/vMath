// Shared publishing setup, applied by the published modules (vmath, vmath-simd, vmath-annotations) after they set `extra["publishDescription"]`.
//
//   ./gradlew publishAllPublicationsToStagingRepository verifyPublication
//         builds the jar, sources jar, javadoc jar, POM and Gradle module metadata into build/staging-repo and checks them (nothing leaves your machine)
//   ./gradlew publishAllPublicationsToGitHubPackagesRepository      needs GITHUB_ACTOR and GITHUB_TOKEN (a token with write:packages) in the environment
//   signing: set SIGNING_KEY (an ASCII-armoured private key) and SIGNING_PASSWORD, and the publications are signed (Maven Central requires it)
//
// docs/PUBLISHING.md has the whole procedure, including what Maven Central additionally needs. The -Pvalhalla build is never published: its classes use
// preview features and only load on a JDK started with --enable-preview.

apply(plugin = "maven-publish")

val valhallaBuild = providers.gradleProperty("valhalla").isPresent
val publishDescription = project.extra["publishDescription"] as String
// the aggregate lives in the project vmath-all and is published as `vmath`
val publishedName = if (project.extra.has("publishArtifactId")) project.extra["publishArtifactId"] as String else project.name
val repoUrl = "https://github.com/AndreasNilsson123/vMath"

extensions.configure<JavaPluginExtension> {
    withJavadocJar()
}

// the licence travels inside every jar
tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

extensions.configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = publishedName
            pom {
                name.set(publishedName)
                description.set(publishDescription)
                url.set(repoUrl)
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("AndreasNilsson123")
                        name.set("Andreas Nilsson")
                        url.set("https://github.com/AndreasNilsson123")
                    }
                }
                scm {
                    connection.set("scm:git:$repoUrl.git")
                    developerConnection.set("scm:git:ssh://git@github.com/AndreasNilsson123/vMath.git")
                    url.set(repoUrl)
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("$repoUrl/issues")
                }
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/AndreasNilsson123/vMath")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = !valhallaBuild }

// Snapshot publications get a timestamp in their file names, so a second staging run would leave two sets of files side by side: clear this module's staged files first.
val cleanStaging = tasks.register<Delete>("cleanStaging") {
    delete(rootProject.layout.buildDirectory.dir("staging-repo/${project.group.toString().replace('.', '/')}/$publishedName"))
}
tasks.withType<PublishToMavenRepository>().configureEach {
    if (repository.name == "staging") {
        dependsOn(cleanStaging)
    }
}
tasks.withType<PublishToMavenLocal>().configureEach { enabled = !valhallaBuild }

val signingKey = providers.environmentVariable("SIGNING_KEY")
if (signingKey.isPresent) {
    apply(plugin = "signing")
    extensions.configure<SigningExtension> {
        useInMemoryPgpKeys(signingKey.get(), providers.environmentVariable("SIGNING_PASSWORD").orNull)
        sign(extensions.getByType<PublishingExtension>().publications["maven"])
    }
}

// Checks what a staging publication must contain before anything is uploaded anywhere.
tasks.register("verifyPublication") {
    group = "verification"
    description = "Checks the staged artifacts of this module: jars, POM metadata required by Maven Central, licence and module descriptor in the jars."
    dependsOn("publishMavenPublicationToStagingRepository")
    val staging = rootProject.layout.buildDirectory.dir("staging-repo")
    val artifactId = publishedName
    val groupPath = project.group.toString().replace('.', '/')
    val moduleVersion = project.version.toString()
    doLast {
        val dir = staging.get().asFile.resolve("$groupPath/$artifactId/$moduleVersion")
        check(dir.isDirectory) { "nothing staged at $dir" }
        fun one(suffix: String): java.io.File {
            val matches = dir.listFiles { f -> f.name.startsWith("$artifactId-") && f.name.endsWith(suffix) && !f.name.endsWith("$suffix.md5") && !f.name.endsWith("$suffix.sha1") &&
                    !f.name.endsWith("$suffix.sha256") && !f.name.endsWith("$suffix.sha512") }.orEmpty().filter {
                // "-sources.jar" and "-javadoc.jar" also end in ".jar": the plain jar is the one that is neither
                suffix != ".jar" || (!it.name.endsWith("-sources.jar") && !it.name.endsWith("-javadoc.jar"))
            }
            check(matches.size == 1) { "expected exactly one *$suffix in $dir, found ${matches.map { it.name }}" }
            return matches[0]
        }
        val jar = one(".jar")
        val sources = one("-sources.jar")
        val javadoc = one("-javadoc.jar")
        val pom = one(".pom").readText()
        one(".module")
        for (tag in listOf("<name>", "<description>", "<url>", "<licenses>", "<license>", "MIT License", "<developers>", "<scm>", "<connection>", "<inceptionYear>")) {
            check(pom.contains(tag)) { "the POM of $artifactId lacks $tag" }
        }
        fun entries(f: java.io.File): Set<String> = java.util.zip.ZipFile(f).use { z -> z.entries().asSequence().map { it.name }.toSet() }
        for (f in listOf(jar, sources, javadoc)) {
            check("META-INF/LICENSE" in entries(f)) { "${f.name} does not contain META-INF/LICENSE" }
        }
        check("module-info.class" in entries(jar)) { "${jar.name} has no module descriptor" }
        check(entries(sources).any { it.endsWith(".java") }) { "${sources.name} holds no sources" }
        check("index.html" in entries(javadoc)) { "${javadoc.name} holds no documentation" }
        logger.lifecycle("$artifactId $moduleVersion: ${jar.name} (${jar.length() / 1024} KB), sources, javadoc, POM and module metadata are in place")
    }
}
