package me.danielstiner.dumble.mumble.net

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        val store = FileClientIdentityStore(file())
        val a = async { store.load() }
        val b = async { store.load() }
        assertSame(a.await(), b.await())
        assertEquals(a.await().hash, ClientIdentity.decode(file().readBytes()).hash)
    }

    @Test fun aCorruptFileIsMovedAsideAndReplaced() = runBlocking {
        val file = file()
        val garbage = byteArrayOf(1, 2, 3, 4)
        file.writeBytes(garbage)
        val identity = FileClientIdentityStore(file).load()
        assertArrayEquals(garbage, File(file.path + ".corrupt").readBytes())
        assertEquals(identity.hash, ClientIdentity.decode(file.readBytes()).hash)
    }

    @Test fun aReplacedIdentityIsANewOne() = runBlocking {
        val file = file()
        val original = FileClientIdentityStore(file).load()
        file.writeBytes(byteArrayOf(9))
        val replacement = FileClientIdentityStore(file).load()
        assertNotEquals(original.hash, replacement.hash)
    }

    @Test fun noIdentityStoreYieldsNull() = runBlocking {
        assertNull(NoClientIdentity.load())
    }
}
