package com.daniel.dshremote

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

class NotifiedKeysStoreTest {

    private fun newTempStore(): AndroidNotifiedKeysStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "dsh-notified-test-${System.nanoTime()}")
        return AndroidNotifiedKeysStore(dir)
    }

    @Test
    fun add_load_roundtrip() = runBlocking {
        val store = newTempStore()
        store.add("approval:a1")
        store.add("delivery:s1:tk-1")
        assertEquals(setOf("approval:a1", "delivery:s1:tk-1"), store.load())
    }

    @Test
    fun add_is_idempotent() = runBlocking {
        val store = newTempStore()
        store.add("approval:a1")
        store.add("approval:a1")
        assertEquals(setOf("approval:a1"), store.load())
    }

    @Test
    fun remove_deletes_key() = runBlocking {
        val store = newTempStore()
        store.add("approval:a1")
        store.add("question:q1")
        store.remove("approval:a1")
        assertEquals(setOf("question:q1"), store.load())
    }

    @Test
    fun remove_missing_key_noop() = runBlocking {
        val store = newTempStore()
        store.add("approval:a1")
        store.remove("nonexistent")
        assertEquals(setOf("approval:a1"), store.load())
    }

    @Test
    fun load_empty_dir_returns_empty() = runBlocking {
        val store = newTempStore()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun concurrent_adds_do_not_lose_writes() = runBlocking {
        val store = newTempStore()
        coroutineScope {
            (1..50).map { n ->
                async(Dispatchers.IO) { store.add("delivery:s1:tk-$n") }
            }.awaitAll()
        }
        assertEquals((1..50).map { "delivery:s1:tk-$it" }.toSet(), store.load())
    }
}
