package com.agentifiers.amp.buildlogic

import com.agentifiers.amp.buildlogic.ModuleGraphRules.BanKind
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/** One banned use: [line] is 1-based. */
data class BanHit(val file: File, val line: Int, val ban: ModuleGraphRules.SourceBan)

/** Scans [text] for every ban that allows neither [module] nor [file]'s path. */
fun banHits(module: String, file: File, text: String): List<BanHit> {
    val path = file.invariantSeparatorsPath
    val active = ModuleGraphRules.bans.filter { module !in it.allowedIn && it.allowedPaths?.containsMatchIn(path) != true }
    if (active.isEmpty()) return emptyList()
    return text.lineSequence().withIndex().flatMap { (index, line) ->
        active.filter { ban -> matches(ban, line) }.map { BanHit(file, index + 1, it) }
    }.toList()
}

private fun matches(ban: ModuleGraphRules.SourceBan, line: String): Boolean = when (ban.kind) {
    BanKind.Literal -> ban.text in line
    BanKind.ImportPrefix -> {
        val prefix = Regex.escape(ban.text)
        Regex("""^\s*import\s+$prefix""").containsMatchIn(line) || Regex("""\b$prefix(?:[a-z_]\w*\.)*[A-Z]""").containsMatchIn(line)
    }
    BanKind.Pattern -> Regex(ban.text).containsMatchIn(line)
}

/** `ampSourceBans`: the AmpSourceBans rule for one module, wired before compilation by the convention plugins. */
abstract class AmpSourceBans : DefaultTask() {
    @get:Input abstract val modulePath: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun scan() {
        val hits = sources.files.filter { it.isFile }.flatMap { banHits(modulePath.get(), it, it.readText()) }
        if (hits.isNotEmpty()) {
            throw GradleException("AmpSourceBans in ${modulePath.get()}:\n" + hits.joinToString("\n") {
                val where = it.ban.allowedIn.sorted().joinToString().ifEmpty { "no module" } + (it.ban.allowedPaths?.let { paths -> " and files matching $paths" } ?: "")
                "  ${it.file}:${it.line}: \"${it.ban.text}\" is allowed only in $where (${it.ban.why})"
            })
        }
    }
}

internal fun registerSourceBans(project: org.gradle.api.Project, layout: ProjectLayout = project.layout) =
    project.tasks.register("ampSourceBans", AmpSourceBans::class.java) {
        group = "verification"
        description = "Fails on a banned literal or import (ModuleGraphRules.bans)."
        modulePath.set(project.path)
        sources.from(layout.projectDirectory.dir("src").asFileTree.matching {
            include("**/*.kt", "**/*.kts", "**/*.java", "**/*.xml", "**/*.json", "**/*.js", "**/*.html")
        })
    }
