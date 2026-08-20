package io.github.tabssh.containers

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stack membership detection — the single rule that keeps the dashboard count
 * and the Containers list from disagreeing: a stack member is counted but not
 * listed (IDEA.md § Container host management).
 */
class ComposeMembershipTest {

    private fun identity(name: String, labels: Map<String, String> = emptyMap()) =
        ComposeMembership.ContainerIdentity(name, labels)

    @Test
    fun `compose project label wins over the name`() {
        val labels = mapOf(ComposeMembership.LABEL_COMPOSE_PROJECT to "media")
        assertEquals("media", ComposeMembership.projectOf("anything", labels, emptySet()))
    }

    @Test
    fun `blank compose project label falls through to the name`() {
        val labels = mapOf(ComposeMembership.LABEL_COMPOSE_PROJECT to "   ")
        assertEquals("media", ComposeMembership.projectOf("media-web-1", labels, setOf("media")))
    }

    @Test
    fun `v2 and v1 naming conventions are both recognised`() {
        assertTrue(ComposeMembership.isStackMember("media-web-1", emptyMap(), setOf("media")))
        assertTrue(ComposeMembership.isStackMember("media_web_1", emptyMap(), setOf("media")))
    }

    @Test
    fun `leading slash from the engine is ignored`() {
        assertEquals("media", ComposeMembership.projectFromName("/media-web-1", setOf("media")))
    }

    @Test
    fun `a container is only a member of a known project`() {
        assertNull(ComposeMembership.projectFromName("media-web-1", emptySet()))
        assertFalse(ComposeMembership.isStackMember("media-web-1", emptyMap(), setOf("blog")))
    }

    @Test
    fun `standalone containers are never members`() {
        assertFalse(ComposeMembership.isStackMember("postgres", emptyMap(), setOf("media")))
        assertFalse(ComposeMembership.isStackMember("media", emptyMap(), setOf("media")))
        assertFalse(ComposeMembership.isStackMember("media-web", emptyMap(), setOf("media")))
    }

    @Test
    fun `the longest matching project wins`() {
        val projects = setOf("media", "media-lab")
        assertEquals("media-lab", ComposeMembership.projectFromName("media-lab-web-1", projects))
    }

    @Test
    fun `a label makes a member of a container the name convention would miss`() {
        val labels = mapOf(ComposeMembership.LABEL_COMPOSE_PROJECT to "media")
        assertTrue(ComposeMembership.isStackMember("legacy_app", labels, emptySet()))
        assertEquals("media", ComposeMembership.projectOf("legacy_app", labels, emptySet()))
    }

    @Test
    fun `labelled members are dropped from the standalone list`() {
        val all = listOf(
            identity("postgres"),
            identity("legacy_app", mapOf(ComposeMembership.LABEL_COMPOSE_PROJECT to "media")),
            identity("media-web-1")
        )

        val listed = ComposeMembership.standaloneOnly(all, setOf("media")) { it }

        assertEquals(listOf("postgres"), listed.map { it.name })
    }

    @Test
    fun `three standalone containers plus two stacks of two list only the standalone ones`() {
        val all = listOf(
            identity("postgres"),
            identity("redis"),
            identity("nginx"),
            identity("media-web-1"),
            identity("media-db-1"),
            identity("blog-web-1"),
            identity("blog-db-1")
        )
        val projects = setOf("media", "blog")
        val listed = ComposeMembership.standaloneOnly(all, projects) { it }
        assertEquals(listOf("postgres", "redis", "nginx"), listed.map { it.name })
        assertEquals(7, all.size)
        assertEquals(2, projects.size)
    }
}
