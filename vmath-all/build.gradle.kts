// The aggregate module `vmath`: no packages, only a module descriptor that requires the four parts, so that `requires vmath` and the Maven coordinate `vmath` still give the whole library.
// Published as `vmath` (the project is vmath-all because the root project is only the container of the build).

plugins {
    `java-library`
}

val valhalla = providers.gradleProperty("valhalla").isPresent
val valhallaJdk = property("vmath.valhallaJdk").toString().toInt()

dependencies {
    api(project(":vmath-core"))
    api(project(":vmath-geo"))
    api(project(":vmath-scene"))
    api(project(":vmath-render"))
}

base {
    archivesName.set("vmath")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(if (valhalla) valhallaJdk else property("vmath.jdk").toString().toInt()))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-exports", "-Werror"))
    if (valhalla) {
        options.compilerArgs.addAll(listOf("-Xlint:-preview", "--enable-preview"))
        options.release.set(valhallaJdk)
    }
}

extra["publishDescription"] = "Immutable, Valhalla-ready 3D math and graphics foundation for Java: the aggregate of vmath-core, vmath-geo, vmath-scene and vmath-render (vectors, matrices, geometry, culling, cameras, meshes, animation and GPU data layouts)."
extra["publishArtifactId"] = "vmath"
apply(from = rootProject.file("gradle/publishing.gradle.kts"))
