package com.agentifiers.amp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskAction

/** Root plugin: registers `checkModuleGraph` and `checkDpBudget`, hangs both on `check`, and runs the budget with every `test`. */
class ModuleGraphPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "amp.module-graph applies to the root project" }
        target.pluginManager.apply("base")
        val task = target.tasks.register("checkModuleGraph", CheckModuleGraph::class.java) {
            group = "verification"
            description = "Fails on any project dependency not in ModuleGraphRules."
        }
        target.tasks.named("check") { dependsOn(task) }
        val dpBudget = target.tasks.register("checkDpBudget", Exec::class.java) {
            group = "verification"
            description = "Fails when raw N.dp literals in feature/ and app/ exceed scripts/dp-budget.txt."
            workingDir = target.rootDir
            commandLine("bash", "scripts/dp-budget.sh")
        }
        target.tasks.named("check") { dependsOn(dpBudget) }
        target.subprojects { tasks.matching { it.name == "test" }.configureEach { dependsOn(dpBudget) } }
        target.gradle.projectsEvaluated {
            // :core and :feature are path containers with no build file, not modules.
            val projects = target.rootProject.allprojects.filter { it == target || it.buildFile.exists() }
            task.configure {
                this.projects.set(projects.map { it.path })
                edges.set(projects.flatMap { project ->
                    project.configurations.flatMap { configuration ->
                        configuration.dependencies.withType(ProjectDependency::class.java)
                            .map { ModuleEdge(project.path, configuration.name, it.path).encode() }
                    }
                })
            }
        }
    }
}

abstract class CheckModuleGraph : DefaultTask() {
    @get:Input abstract val projects: ListProperty<String>
    @get:Input abstract val edges: ListProperty<String>

    @TaskAction
    fun check() {
        val found = violations(projects.get(), edges.get().map(ModuleEdge::decode))
        if (found.isNotEmpty()) throw GradleException("Module graph breaks ModuleGraphRules:\n" + found.joinToString("\n") { "  $it" })
        logger.lifecycle("checkModuleGraph: ${edges.get().size} declared edges across ${projects.get().size} projects match ModuleGraphRules")
    }
}
