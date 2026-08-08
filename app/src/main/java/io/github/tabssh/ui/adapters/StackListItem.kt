package io.github.tabssh.ui.adapters

import io.github.tabssh.docker.transport.ComposeLsEntry
import io.github.tabssh.storage.database.entities.ComposeStack

/**
 * One row of the compose-stacks list: either a Room-tracked [ComposeStack]
 * or a project discovered via `docker compose ls` that has no Room row yet
 * (TODO.AI.md § D external-stack discovery).
 */
sealed class StackListItem {

    abstract val listKey: String
    abstract val name: String
    abstract val statusLine: String?

    data class Tracked(val stack: ComposeStack) : StackListItem() {
        override val listKey: String get() = "tracked:${stack.id}"
        override val name: String get() = stack.name
        override val statusLine: String get() = stack.remotePath
    }

    data class External(val entry: ComposeLsEntry) : StackListItem() {
        override val listKey: String get() = "external:${entry.name}"
        override val name: String get() = entry.name
        override val statusLine: String get() = entry.primaryConfigFile
    }
}
