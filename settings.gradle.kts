rootProject.name = "vmath"

include("vmath-annotations", "vmath-codegen", "vmath-validator", "vmath-all", "vmath-core", "vmath-geo", "vmath-scene", "vmath-render", "vmath-simd", "vmath-bench")

// The samples need LWJGL (OpenGL and GLFW bindings), which Gradle downloads, and a graphics driver to run, so the library build never needs either: on the command line they are part
// of the build only when asked for (./gradlew -Psamples :vmath-samples:run). Inside IntelliJ they are always part of it: the IDE sets idea.active (and idea.sync.active while it
// imports the project) for the Gradle it starts, so the module shows up after a sync and its run configurations work without a flag (docs/IDE.md, docs/SAMPLES.md).
val startedByIdea = providers.systemProperty("idea.active").isPresent || providers.systemProperty("idea.sync.active").isPresent
if (providers.gradleProperty("samples").isPresent || startedByIdea) {
    include("vmath-samples")
}
