package io.github.tabssh.containers

/**
 * Decides whether a container belongs to a compose stack.
 *
 * The Containers tab hides stack members — they are managed from the Stacks
 * tab — while the dashboard still counts them, so "3 standalone containers
 * plus 2 stacks of 2" reads as 2 stacks and 7 containers (IDEA.md § Container
 * host management). Both surfaces call in here so the two views can never
 * disagree about what a member is.
 *
 * Detection is label-first: Docker and Podman stamp [LABEL_COMPOSE_PROJECT]
 * onto every container compose creates, and that label is authoritative even
 * when the compose file pins an arbitrary `container_name`. When the engine
 * listing carries no labels the name convention is the fallback — compose
 * names its containers `{project}-{service}-{index}` (v2) or
 * `{project}_{service}_{index}` (v1) — and it is only ever applied against
 * project names the host actually reported, so an unrelated container called
 * `backup-db-1` is not mistaken for a stack member unless a project named
 * `backup` really exists.
 *
 * Everything here is pure: no Android types, no transport, no I/O.
 */
object ComposeMembership {

    /** Compose project label; set by Docker Compose and Podman Compose alike. */
    const val LABEL_COMPOSE_PROJECT = "com.docker.compose.project"

    /**
     * The `{service}{sep}{index}` remainder that follows the project name in a
     * compose-generated container name. Service names may themselves contain
     * hyphens, so only the trailing numeric index is pinned down.
     */
    private val MEMBER_SUFFIX = Regex("^.+[-_]\\d+$")

    /** Separators compose puts between project, service and index. */
    private const val SEPARATORS = "-_"

    /** A container reduced to the two fields membership is decided from. */
    data class ContainerIdentity(
        val name: String,
        val labels: Map<String, String> = emptyMap()
    )

    /**
     * The compose project [containerName] belongs to, or null when it is a
     * standalone container. [knownProjects] are the project names the host
     * reported (compose ls plus the app's own tracked stacks).
     */
    fun projectOf(
        containerName: String,
        labels: Map<String, String>,
        knownProjects: Set<String>
    ): String? {
        val labelled = labels[LABEL_COMPOSE_PROJECT]?.trim()
        if (!labelled.isNullOrEmpty()) return labelled
        return projectFromName(containerName, knownProjects)
    }

    /**
     * Name-convention fallback for engine listings that carry no labels. The
     * longest matching project wins, so a host running both `app` and
     * `app-staging` attributes `app-staging-web-1` to `app-staging`.
     */
    fun projectFromName(containerName: String, knownProjects: Set<String>): String? {
        val name = normalize(containerName)
        if (name.isEmpty()) return null
        return knownProjects
            .asSequence()
            .filter { it.isNotBlank() && isMemberName(name, it) }
            .maxByOrNull { it.length }
    }

    /** True when the container is part of a compose stack. */
    fun isStackMember(
        containerName: String,
        labels: Map<String, String>,
        knownProjects: Set<String>
    ): Boolean = projectOf(containerName, labels, knownProjects) != null

    /**
     * Every item of [items] that is NOT a compose stack member, in the input
     * order — this is what the Containers list renders.
     */
    fun <T> standaloneOnly(
        items: List<T>,
        knownProjects: Set<String>,
        identity: (T) -> ContainerIdentity
    ): List<T> = items.filter {
        val id = identity(it)
        !isStackMember(id.name, id.labels, knownProjects)
    }

    /** Engine listings prefix names with a slash; compare on the bare name. */
    private fun normalize(containerName: String): String =
        containerName.trim().removePrefix("/")

    private fun isMemberName(name: String, project: String): Boolean {
        if (name.length <= project.length + 1) return false
        if (!name.startsWith(project)) return false
        if (name[project.length] !in SEPARATORS) return false
        return MEMBER_SUFFIX.matches(name.substring(project.length + 1))
    }
}
