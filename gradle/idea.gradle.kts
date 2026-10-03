// What IntelliJ needs on top of the build, applied by build.gradle.kts only when the IDE runs Gradle (idea.active or idea.sync.active), so that the command line never resolves the plugin.
//
// After every Gradle sync the IDE runs the generator of the four library modules. The float and double types (Vec3d, Mat4d, the *Bulk classes) are generated into build/generated, and the IDE can
// only resolve them once they exist; without this a fresh checkout, or a `gradlew clean`, shows red code until generateSources has been run by hand.

import org.jetbrains.gradle.ext.ProjectSettings
import org.jetbrains.gradle.ext.TaskTriggersConfig

buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("gradle.plugin.org.jetbrains.gradle.plugin.idea-ext:gradle-idea-ext:1.1.10")
    }
}

apply(plugin = "idea")
// by class: the classpath above belongs to this script, not to the project, so the plugin id is not known to the project
apply<org.jetbrains.gradle.ext.IdeaExtPlugin>()

val generators = listOf(":vmath-core", ":vmath-geo", ":vmath-scene", ":vmath-render").map { project(it).tasks.named("generateSources") }

extensions.configure<org.gradle.plugins.ide.idea.model.IdeaModel>("idea") {
    project {
        (this as ExtensionAware).extensions.configure<ProjectSettings>("settings") {
            (this as ExtensionAware).extensions.configure<TaskTriggersConfig>("taskTriggers") {
                afterSync(*generators.toTypedArray())
            }
        }
    }
}
