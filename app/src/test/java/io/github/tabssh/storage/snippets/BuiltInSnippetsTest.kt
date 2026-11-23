package io.github.tabssh.storage.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSnippetsTest {

    @Test
    fun `every built-in has a unique stable id`() {
        val all = BuiltInSnippets.all()
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { BuiltInSnippets.isBuiltIn(it.id) })
        assertFalse(BuiltInSnippets.isBuiltIn("user-snippet"))
    }

    @Test
    fun `no built-in assumes a distro specific log path or a single init system`() {
        val tail = BuiltInSnippets.all().first { it.name == "Tail log" }
        assertFalse(tail.command.contains("/var/log/"))
        val restart = BuiltInSnippets.all().first { it.name == "Restart service" }
        assertTrue(restart.command.contains("service "))
    }

    @Test
    fun `system update script stays brace free so it has no placeholders`() {
        val update = BuiltInSnippets.all().first { it.name == "System update" }
        assertFalse(update.command.contains("{"))
    }
}
