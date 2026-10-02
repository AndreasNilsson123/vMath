// Coverage for a published or tool module (docs/COVERAGE.md): the report after `test` and a floor in `check`. Applied by vmath-simd and vmath-codegen after they set
// extra["coverageLineFloor"] and extra["coverageBranchFloor"] (two to three points under what was measured). Not on the -Pvalhalla build: the JaCoCo release in use
// does not read JDK 28 class files.

apply(plugin = "jacoco")

val noCoverage = providers.gradleProperty("valhalla").isPresent
val lineFloor = project.extra["coverageLineFloor"] as String
val branchFloor = project.extra["coverageBranchFloor"] as String

extensions.configure<JacocoPluginExtension> {
    toolVersion = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("jacoco").get().requiredVersion
}

val jacocoTestReport = tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn("test")
    enabled = !noCoverage
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named<Test>("test") {
    extensions.configure<JacocoTaskExtension> { isEnabled = !noCoverage }
    finalizedBy(jacocoTestReport)
}

val verification = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("test")
    enabled = !noCoverage
    violationRules {
        rule {
            element = "BUNDLE"
            limit { counter = "LINE"; minimum = lineFloor.toBigDecimal() }
            limit { counter = "BRANCH"; minimum = branchFloor.toBigDecimal() }
        }
    }
}

tasks.named("check") {
    dependsOn(verification)
}
