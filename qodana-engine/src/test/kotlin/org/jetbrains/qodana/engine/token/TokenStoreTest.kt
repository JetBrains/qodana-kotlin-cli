package org.jetbrains.qodana.engine.token

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenStoreTest {

    @Test
    fun `EnvTokenStore loads from environment`() {
        val env = mapOf("QODANA_TOKEN" to "test-token-123")
        val store = EnvTokenStore { env[it] }
        assertEquals("test-token-123", store.load("QODANA_TOKEN"))
    }

    @Test
    fun `EnvTokenStore returns null for missing key`() {
        val store = EnvTokenStore { null }
        assertNull(store.load("QODANA_TOKEN"))
    }

    @Test
    fun `EnvTokenStore returns null for blank value`() {
        val store = EnvTokenStore { "  " }
        assertNull(store.load("QODANA_TOKEN"))
    }

    @Test
    fun `EnvTokenStore save is no-op`() {
        val store = EnvTokenStore { null }
        store.save("KEY", "value") // Should not throw
    }

    @Test
    fun `EnvTokenStore delete is no-op`() {
        val store = EnvTokenStore { null }
        store.delete("KEY") // Should not throw
    }

    @Test
    fun `FileTokenStore save and load`(@TempDir dir: Path) {
        val store = FileTokenStore(dir)
        store.save("QODANA_TOKEN", "my-secret-token")
        assertEquals("my-secret-token", store.load("QODANA_TOKEN"))
    }

    @Test
    fun `FileTokenStore returns null for missing key`(@TempDir dir: Path) {
        val store = FileTokenStore(dir)
        assertNull(store.load("NONEXISTENT"))
    }

    @Test
    fun `FileTokenStore delete removes token`(@TempDir dir: Path) {
        val store = FileTokenStore(dir)
        store.save("QODANA_TOKEN", "token-to-delete")
        assertEquals("token-to-delete", store.load("QODANA_TOKEN"))
        store.delete("QODANA_TOKEN")
        assertNull(store.load("QODANA_TOKEN"))
    }

    @Test
    fun `FileTokenStore trims whitespace`(@TempDir dir: Path) {
        val store = FileTokenStore(dir)
        store.save("TOKEN", "  token-with-spaces  ")
        // save stores as-is, but load trims
        val loaded = store.load("TOKEN")
        assertEquals("token-with-spaces", loaded)
    }

    @Test
    fun `FileTokenStore returns null for blank file`(@TempDir dir: Path) {
        val store = FileTokenStore(dir)
        store.save("TOKEN", "   ")
        assertNull(store.load("TOKEN"))
    }

    @Test
    fun `FileTokenStore multiple keys`(@TempDir dir: Path) {
        val store = FileTokenStore(dir)
        store.save("TOKEN_A", "value-a")
        store.save("TOKEN_B", "value-b")
        assertEquals("value-a", store.load("TOKEN_A"))
        assertEquals("value-b", store.load("TOKEN_B"))
    }

    @Test
    fun `FileTokenStore overwrite existing`(@TempDir dir: Path) {
        val store = FileTokenStore(dir)
        store.save("TOKEN", "old-value")
        store.save("TOKEN", "new-value")
        assertEquals("new-value", store.load("TOKEN"))
    }
}
