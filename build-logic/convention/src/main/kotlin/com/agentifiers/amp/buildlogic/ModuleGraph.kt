package com.agentifiers.amp.buildlogic

/** One declared project dependency: `from` names `to` in `configuration`. */
data class ModuleEdge(val from: String, val configuration: String, val to: String) {
    /** Test and debug configurations may also use [ModuleGraphRules.allowedTestAndDebug]. */
    val testOrDebug: Boolean
        get() = TEST_OR_DEBUG.any { configuration.startsWith(it) || configuration.contains(it.replaceFirstChar(Char::uppercase)) }

    fun encode(): String = "$from|$configuration|$to"

    companion object {
        private val TEST_OR_DEBUG = listOf("test", "androidTest", "debug")

        fun decode(line: String): ModuleEdge = line.split('|').let { (from, configuration, to) -> ModuleEdge(from, configuration, to) }
    }
}

/** Every rule an edge set breaks, as one line each. Empty means the graph matches the table. */
fun violations(projects: Collection<String>, edges: Collection<ModuleEdge>): List<String> {
    val unknown = projects.filter { it != ":" && it !in ModuleGraphRules.allowed }
        .map { "$it is not in ModuleGraphRules.allowed" }
    val bad = edges.filter { it.from != it.to }.mapNotNull { edge ->
        val main = ModuleGraphRules.allowed[edge.from] ?: return@mapNotNull null
        val permitted = if (edge.testOrDebug) main + ModuleGraphRules.allowedTestAndDebug[edge.from].orEmpty() else main
        if (edge.to in permitted) null else "${edge.from} -> ${edge.to} (${edge.configuration}) is not an allowed edge"
    }
    return unknown + bad.distinct()
}
