package io.github.tabssh.docker.registry

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Image reference parsing against the docker/distribution grammar subset the
 * update checker needs — table-driven over the common shapes.
 */
class ImageRefTest {

    private data class Case(
        val raw: String,
        val registry: String,
        val repository: String,
        val tag: String,
        val digest: String? = null
    )

    @Test
    fun `parses the reference table`() {
        val digest = "sha256:0000000000000000000000000000000000000000000000000000000000000000"
        val cases = listOf(
            // Bare Hub name → implicit library/ namespace + latest.
            Case("nginx", "docker.io", "library/nginx", "latest"),
            Case("nginx:1.27", "docker.io", "library/nginx", "1.27"),
            // Namespaced Hub name.
            Case("casjaysdev/go", "docker.io", "casjaysdev/go", "latest"),
            Case("casjaysdev/go:latest", "docker.io", "casjaysdev/go", "latest"),
            // Explicit registry host.
            Case("ghcr.io/tabssh/android", "ghcr.io", "tabssh/android", "latest"),
            Case("ghcr.io/tabssh/android:build", "ghcr.io", "tabssh/android", "build"),
            // Registry with port — the ':' before '/' is not a tag.
            Case("registry.local:5000/team/app", "registry.local:5000", "team/app", "latest"),
            Case("registry.local:5000/team/app:v2", "registry.local:5000", "team/app", "v2"),
            Case("localhost/app:dev", "localhost", "app", "dev"),
            // Digest references pin over the tag.
            Case("nginx@$digest", "docker.io", "library/nginx", "latest", digest),
            Case("ghcr.io/tabssh/android:build@$digest", "ghcr.io", "tabssh/android", "build", digest),
            // Hub aliases all normalize to docker.io.
            Case("index.docker.io/library/nginx", "docker.io", "library/nginx", "latest"),
            Case("registry-1.docker.io/library/nginx:1.27", "docker.io", "library/nginx", "1.27"),
            // A dotless first segment is a namespace, never a registry.
            Case("mycompany/tool:1.0", "docker.io", "mycompany/tool", "1.0")
        )
        for (case in cases) {
            val ref = ImageRef.parse(case.raw)
                ?: throw AssertionError("parse returned null for '${case.raw}'")
            assertEquals(case.registry, ref.registryHost, "registry of '${case.raw}'")
            assertEquals(case.repository, ref.repository, "repository of '${case.raw}'")
            assertEquals(case.tag, ref.tag, "tag of '${case.raw}'")
            assertEquals(case.digest, ref.digest, "digest of '${case.raw}'")
        }
    }

    @Test
    fun `hub references use the registry-1 api host`() {
        assertEquals("registry-1.docker.io", ImageRef.parse("nginx")!!.apiHost)
        assertEquals("ghcr.io", ImageRef.parse("ghcr.io/a/b")!!.apiHost)
    }

    @Test
    fun `manifest reference prefers digest over tag`() {
        val digest = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
        assertEquals("1.27", ImageRef.parse("nginx:1.27")!!.manifestReference)
        assertEquals(digest, ImageRef.parse("nginx:1.27@$digest")!!.manifestReference)
    }

    @Test
    fun `canonical repository includes the registry host`() {
        assertEquals("docker.io/library/nginx", ImageRef.parse("nginx")!!.canonicalRepository)
        assertEquals("ghcr.io/tabssh/android", ImageRef.parse("ghcr.io/tabssh/android")!!.canonicalRepository)
    }

    @Test
    fun `malformed references return null`() {
        assertNull(ImageRef.parse(""))
        assertNull(ImageRef.parse("   "))
        // Non-sha256 digest algorithm is rejected.
        assertNull(ImageRef.parse("nginx@md5:abc"))
        // Empty name before a digest.
        assertNull(ImageRef.parse("@sha256:abc"))
        // Registry host with nothing after it.
        assertNull(ImageRef.parse("ghcr.io/"))
        // Empty tag.
        assertNull(ImageRef.parse("nginx:"))
    }
}
