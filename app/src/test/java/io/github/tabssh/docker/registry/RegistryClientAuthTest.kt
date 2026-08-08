package io.github.tabssh.docker.registry

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure auth helpers of [RegistryClient]: WWW-Authenticate challenge parsing
 * (Docker Hub's Bearer challenge, private-registry Basic), the RFC 7617
 * Basic value, and the default pull scope.
 */
class RegistryClientAuthTest {

    @Test
    fun `parses the docker hub bearer challenge`() {
        val challenge = RegistryClient.parseWwwAuthenticate(
            """Bearer realm="https://auth.docker.io/token",service="registry.docker.io",scope="repository:library/nginx:pull""""
        )!!
        assertEquals("Bearer", challenge.scheme)
        assertEquals("https://auth.docker.io/token", challenge.realm)
        assertEquals("registry.docker.io", challenge.service)
        assertEquals("repository:library/nginx:pull", challenge.scope)
    }

    @Test
    fun `parses a basic challenge`() {
        val challenge = RegistryClient.parseWwwAuthenticate("""Basic realm="Registry Realm"""")!!
        assertEquals("Basic", challenge.scheme)
        assertEquals("Registry Realm", challenge.realm)
        assertNull(challenge.service)
    }

    @Test
    fun `parses unquoted parameter values`() {
        val challenge = RegistryClient.parseWwwAuthenticate(
            "Bearer realm=https://r.example/token, service=r.example"
        )!!
        assertEquals("https://r.example/token", challenge.realm)
        assertEquals("r.example", challenge.service)
    }

    @Test
    fun `parameter keys are case-insensitive`() {
        val challenge = RegistryClient.parseWwwAuthenticate(
            """Bearer Realm="https://r.example/token",SERVICE="r.example""""
        )!!
        assertEquals("https://r.example/token", challenge.realm)
        assertEquals("r.example", challenge.service)
    }

    @Test
    fun `scheme-only challenge has empty params`() {
        val challenge = RegistryClient.parseWwwAuthenticate("Negotiate")!!
        assertEquals("Negotiate", challenge.scheme)
        assertEquals(emptyMap(), challenge.params)
    }

    @Test
    fun `blank or malformed headers return null`() {
        assertNull(RegistryClient.parseWwwAuthenticate(null))
        assertNull(RegistryClient.parseWwwAuthenticate(""))
        assertNull(RegistryClient.parseWwwAuthenticate("   "))
        // A parameter list with no scheme is not a challenge.
        assertNull(RegistryClient.parseWwwAuthenticate("""realm="x",service="y""""))
    }

    @Test
    fun `basic auth value is rfc 7617 base64`() {
        // echo -n 'user:pass' | base64 → dXNlcjpwYXNz
        assertEquals("Basic dXNlcjpwYXNz", RegistryClient.basicAuthValue("user", "pass"))
    }

    @Test
    fun `pull scope follows the token spec`() {
        assertEquals("repository:library/nginx:pull", RegistryClient.pullScope("library/nginx"))
    }
}
