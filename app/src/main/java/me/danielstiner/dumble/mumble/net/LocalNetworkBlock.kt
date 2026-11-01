package me.danielstiner.dumble.mumble.net

import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket
import java.net.SocketAddress
import kotlin.time.Duration.Companion.milliseconds

/** Android dropped this connect because the app lacks ACCESS_LOCAL_NETWORK. */
class LocalNetworkBlockedException : IOException("Android blocked this connect: no local network access")

/**
 * Connects, throwing [LocalNetworkBlockedException] if Android blocks the connect. Android blocks
 * nothing below API 37, so there this is a plain connect.
 *
 * Android's check takes a descriptor number, and [ParcelFileDescriptor.fromSocket] is the only
 * public way to get one.
 */
internal suspend fun Socket.connectUnlessBlocked(address: SocketAddress, timeoutMs: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return connect(address, timeoutMs)
    // A new socket has no descriptor until an option is touched, and fromSocket would return null.
    // Android's Network.bindSocket does this same read.
    getReuseAddress()
    ParcelFileDescriptor.fromSocket(this).use { duplicate ->
        connectUnlessBlocked(
            isBlocked = { LocalNetworkBlockNative.isBlocked(duplicate.fd) },
            connect = { connect(address, timeoutMs) },
            abort = { runCatching { close() } },
        )
    }
}

private object LocalNetworkBlockNative {
    init { System.loadLibrary("dumble") }

    /** Whether Android has blocked this socket since the last call: reading the reason clears it. */
    external fun isBlocked(fd: Int): Boolean
}

/**
 * Runs the blocking [connect], calling [abort] as soon as [isBlocked] reports a block. Java sees a
 * blocked connect as a timeout, the same as a dead server's, so without this it would take the
 * whole connect timeout and still not say why. Measured: the reason is readable within 60 ms of
 * the first SYN and again on each retransmit.
 */
internal suspend fun connectUnlessBlocked(isBlocked: () -> Boolean, connect: () -> Unit, abort: () -> Unit) = coroutineScope {
    var blocked = false
    val watch = launch(Dispatchers.IO) {
        while (!isBlocked()) delay(100.milliseconds)
        blocked = true
        abort()
    }
    val failure = runCatching(connect).exceptionOrNull()
    // Joined so that blocked is final. The scope alone keeps the descriptor open until the watch ends.
    watch.cancelAndJoin()
    if (blocked) throw LocalNetworkBlockedException()
    if (failure != null) throw failure
}
