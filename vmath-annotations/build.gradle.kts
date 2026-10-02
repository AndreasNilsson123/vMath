plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

extra["publishDescription"] = "Marker and code-generation annotations of vmath (@Experimental and the codegen markers). Optional for consumers: add it as compileOnly to silence missing-annotation warnings."
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
