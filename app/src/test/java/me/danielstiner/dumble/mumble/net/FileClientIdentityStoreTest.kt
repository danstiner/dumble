package me.danielstiner.dumble.mumble.net

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileClientIdentityStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "identity.p12")

    @Test fun firstLoadGeneratesAndPersists() = runBlocking {
        val file = file()
        val identity = FileClientIdentityStore(file).load()
        assertTrue(file.exists())
        assertEquals(identity.hash, ClientIdentity.decode(file.readBytes()).hash)
        assertFalse("temp file must not survive", File(file.path + ".tmp").exists())
    }

    @Test fun aSecondStoreOnTheSameFileLoadsTheSameIdentity() = runBlocking {
        val first = FileClientIdentityStore(file()).load()
        val second = FileClientIdentityStore(file()).load()
        assertEquals(first.hash, second.hash)
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
    }

    @Test fun loadIsMemoised() = runBlocking {
        val store = FileClientIdentityStore(file())
        assertSame(store.load(), store.load())
    }

    @Test fun concurrentFirstLoadsAgree() = runBlocking {
        val file = file()
        val store = FileClientIdentityStore(file)
        val a = async { store.load() }
        val b = async { store.load() }
        assertSame(a.await(), b.await())
        assertEquals(a.await().hash, ClientIdentity.decode(file.readBytes()).hash)
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }

    @Test fun aFileThatCannotBeReadFailsTheLoad() {
        // A directory where the identity file should be makes readBytes() throw, standing in
        // for a disk read failure.
        val store = FileClientIdentityStore(folder.newFolder("identity.p12"))
        assertThrows(IOException::class.java) { runBlocking { store.load() } }
    }

    @Test fun aFileThatDoesNotDecodeFailsTheLoadAndIsKept() {
        val file = file()
        val garbage = byteArrayOf(1, 2, 3, 4)
        file.writeBytes(garbage)
        val store = FileClientIdentityStore(file)
        assertThrows(Exception::class.java) { runBlocking { store.load() } }
        assertArrayEquals(garbage, file.readBytes())
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }

    @Test fun noIdentityStoreYieldsNull() = runBlocking {
        assertNull(NoClientIdentity.load())
    }
}
