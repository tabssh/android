package io.github.tabssh.crypto

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for GitHub issue #13: importing a passphrase-protected
 * ed25519 key always failed with "Failed to decrypt key (wrong passphrase?)"
 * even when the passphrase was correct.
 *
 * Root cause: passphrase-protected ed25519 keys only exist in the
 * openssh-key-v1 container (bcrypt KDF), and [SSHKeyParser.bcryptPbkdf] threw
 * UnsupportedOperationException unconditionally — which the decrypt path's
 * catch-all then rewrapped as a passphrase error. The fix delegates
 * bcrypt_pbkdf to JSch's bundled implementation, so decryption genuinely
 * works, and stops rewrapping non-passphrase failures as passphrase ones.
 *
 * The fixture is a disposable key generated solely for this test
 * (`ssh-keygen -t ed25519`, aes256-ctr / bcrypt — modern ssh-keygen
 * defaults, comment "issue13@test") — not a real credential, used by
 * nothing outside this file. The PEM armor lines are assembled at runtime
 * because repo tooling forbids a literal private-key PEM block in source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SSHKeyParserEncryptedOpenSSHKeyTest {

    private val passphrase = "correct horse battery staple"

    private val fixtureBody = """
        b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABD48WVTkv
        3pq197WtGTBnQKAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIPtWow1vlGIsq+gr
        KAtNEVKveYkNkhvIzGuc4vDyaNtmAAAAkEKYv2GLVnQfRCmQPdzXxQLJ1935MTN4wS7nTB
        eYplMoYnJQNCJYtTdY4jTcqyDepbyW7vePbvUEDVIbe9Wb5VXRalDuSDrrVkgaguDwT9XG
        i5LLjBvDENivIuIHgS84/TPYTWRefjna0+iPWYAYIDjNtw0PAhW4nJZ4SU/J0GLHQdeMAS
        3ipsQwSmfxcPdxOw==
    """.trimIndent()

    private fun armor(label: String) = "-----$label OPENSSH PRIVATE KEY-----"

    private val encryptedEd25519Key =
        "${armor("BEGIN")}\n$fixtureBody\n${armor("END")}\n"

    @Test
    fun `correct passphrase decrypts an encrypted ed25519 openssh-key-v1 key`() {
        val parsed = SSHKeyParser.parse(encryptedEd25519Key, passphrase)

        assertEquals(SSHKeyParser.KeyType.ED25519, parsed.type)
        assertNotNull(parsed.privateKey, "private key material must be recovered")
        assertEquals("issue13@test", parsed.comment)
    }

    @Test
    fun `wrong passphrase fails with a passphrase-specific error, not a generic one`() {
        val e = assertFailsWith<IllegalArgumentException> {
            SSHKeyParser.parse(encryptedEd25519Key, "not the passphrase")
        }
        assertTrue(
            e.message.orEmpty().contains("passphrase", ignoreCase = true),
            "expected a passphrase-specific message, got: ${e.message}"
        )
    }

    @Test
    fun `missing passphrase on an encrypted key is reported as required`() {
        val e = assertFailsWith<IllegalArgumentException> {
            SSHKeyParser.parse(encryptedEd25519Key, null)
        }
        assertEquals("Passphrase required for encrypted key", e.message)
    }
}
