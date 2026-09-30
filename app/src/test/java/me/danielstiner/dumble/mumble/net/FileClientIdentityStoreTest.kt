package me.danielstiner.dumble.mumble.net

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    /** A store whose first load writes [testIdentity]: these tests are about the file, not RSA. */
    private fun store(file: File) = FileClientIdentityStore(file) { testIdentity }

    @Test fun aSecondStoreOnTheSameFileLoadsTheSameIdentity() = runBlocking {
        val first = store(file()).load()
        val second = FileClientIdentityStore(file()) { error("a second store must read the file, not generate") }.load()
        assertEquals(first.hash, second.hash)
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
    }

    @Test fun loadIsMemoised() = runBlocking {
        val store = store(file())
        assertSame(store.load(), store.load())
    }

    // The real generator on purpose: two racing first loads must settle on one of two distinct
    // identities, in the memo and in the file alike.
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
        val failure = assertThrows(IOException::class.java) { runBlocking { store.load() } }
        assertTrue(failure.message, failure.message!!.contains("identity.p12 does not decode"))
        assertArrayEquals(garbage, file.readBytes())
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }

    @Test fun anEmptyFileIsGeneratedOver() = runBlocking {
        val file = file()
        file.writeBytes(ByteArray(0))
        val identity = store(file).load()
        assertEquals(identity.hash, ClientIdentity.decode(file.readBytes()).hash)
    }

    @Test fun aWriteThatFailsLeavesNoTempFile() {
        // A directory at the temp path makes the write's open throw; the write must clean up
        // after itself and the identity file must not appear.
        val file = file()
        folder.newFolder("identity.p12.tmp")
        assertThrows(IOException::class.java) { runBlocking { store(file).load() } }
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test fun aTempFileLeftByACrashIsOverwritten() = runBlocking {
        val file = file()
        File(file.path + ".tmp").writeBytes(byteArrayOf(1, 2, 3, 4))
        val identity = store(file).load()
        assertEquals(identity.hash, ClientIdentity.decode(file.readBytes()).hash)
        assertEquals(listOf(file.name), folder.root.list()!!.toList())
    }

    @Test fun noIdentityStoreYieldsNull() = runBlocking {
        assertNull(NoClientIdentity.load())
    }
}
