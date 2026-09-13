package io.github.tabssh.utils

/**
 * One tappable segment of a breadcrumb path bar: [label] is the text shown
 * for this segment, [path] is the full absolute path to navigate to when
 * this segment is tapped.
 */
data class PathBreadcrumbSegment(val label: String, val path: String)

/**
 * Splits an absolute filesystem path into tappable breadcrumb segments, so
 * a long path can be shown as a horizontally scrollable, per-segment
 * navigable strip instead of being hard-truncated from the start.
 *
 * "/storage/emulated/0/Download" splits into:
 * [("/", "/"), ("storage", "/storage"), ("emulated", "/storage/emulated"),
 *  ("0", "/storage/emulated/0"), ("Download", "/storage/emulated/0/Download")]
 *
 * Pure function — no Android framework dependency — so it's testable with a
 * plain JUnit test instead of Robolectric. The equivalent for a
 * Storage-Access-Framework [androidx.documentfile.provider.DocumentFile]
 * tree (which has no filesystem path) is built separately in SFTPActivity
 * by walking `DocumentFile.parentFile`, since that needs the live
 * DocumentFile objects rather than a string.
 */
fun splitPathBreadcrumb(path: String): List<PathBreadcrumbSegment> {
    val normalized = path.trim().ifEmpty { "/" }
    val root = PathBreadcrumbSegment("/", "/")
    if (normalized == "/") return listOf(root)

    val parts = normalized.trim('/').split('/').filter { it.isNotEmpty() }
    val segments = mutableListOf(root)
    var accumulated = ""
    for (part in parts) {
        accumulated += "/$part"
        segments.add(PathBreadcrumbSegment(part, accumulated))
    }
    return segments
}
