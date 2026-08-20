package io.github.tabssh.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trip tests for the type-tagged SharedPreferences wire codec.
 *
 * The defect this codec exists to prevent: backup/sync used to flatten every
 * preference value to a String, so the next typed read (`getStringSet` on
 * `cluster_commands`, `getBoolean` on `dash_ungrouped_collapsed`) threw
 * ClassCastException after a restore. Every supported type must survive
 * encode → decode with its Kotlin type intact.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPrefsCodecTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun prefs(name: String) =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun `every supported type survives a round trip`() {
        val source = prefs("codec_source")
        source.edit()
            .putString("a_string", "hello")
            .putBoolean("a_bool", true)
            .putInt("an_int", 42)
            .putLong("a_long", 9_000_000_000L)
            .putFloat("a_float", 1.5f)
            .putStringSet("a_set", setOf("uptime", "df -h"))
            .commit()

        val encoded = SharedPrefsCodec.encodeAll(source)
        assertEquals(6, encoded.size)

        val target = prefs("codec_target")
        val editor = target.edit()
        encoded.forEach { (k, v) -> assertTrue(SharedPrefsCodec.decodeInto(editor, k, v)) }
        editor.commit()

        assertEquals("hello", target.getString("a_string", null))
        assertEquals(true, target.getBoolean("a_bool", false))
        assertEquals(42, target.getInt("an_int", 0))
        assertEquals(9_000_000_000L, target.getLong("a_long", 0L))
        assertEquals(1.5f, target.getFloat("a_float", 0f))
        assertEquals(setOf("uptime", "df -h"), target.getStringSet("a_set", null))
    }

    @Test
    fun `an empty string set round trips as an empty set not null`() {
        val source = prefs("codec_empty_source")
        source.edit().putStringSet("a_set", emptySet()).commit()

        val target = prefs("codec_empty_target")
        val editor = target.edit()
        SharedPrefsCodec.encodeAll(source).forEach { (k, v) ->
            SharedPrefsCodec.decodeInto(editor, k, v)
        }
        editor.commit()

        assertEquals(emptySet<String>(), target.getStringSet("a_set", null))
    }

    @Test
    fun `malformed entries are skipped rather than written wrong-typed`() {
        val editor = prefs("codec_malformed").edit()

        // Not an object at all.
        assertFalse(SharedPrefsCodec.decodeInto(editor, "k", JsonPrimitive("bare")))
        // Missing the type tag.
        assertFalse(
            SharedPrefsCodec.decodeInto(
                editor, "k", buildJsonObject { put("v", JsonPrimitive("x")) }
            )
        )
        // Missing the value.
        assertFalse(
            SharedPrefsCodec.decodeInto(
                editor, "k", buildJsonObject { put("t", JsonPrimitive("string")) }
            )
        )
        // Unknown type tag.
        assertFalse(
            SharedPrefsCodec.decodeInto(
                editor, "k",
                buildJsonObject {
                    put("t", JsonPrimitive("blob"))
                    put("v", JsonPrimitive("x"))
                }
            )
        )
        // Well-formed tag, unparseable value.
        assertFalse(
            SharedPrefsCodec.decodeInto(
                editor, "k",
                buildJsonObject {
                    put("t", JsonPrimitive("int"))
                    put("v", JsonPrimitive("not-a-number"))
                }
            )
        )
    }
}
