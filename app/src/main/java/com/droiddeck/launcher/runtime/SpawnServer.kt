package com.droiddeck.launcher.runtime

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process trees the guest asks the app to start. A stand-in in the session asks over this socket;
 * the app starts the tree beside the session's proot, not inside it, and the stand-in stays in the
 * session until the tree is over, so the guest keeps its parent, its reaper and its exit status.
 * Flatpak's sandboxes are the one kind served so far (BwrapSpawner); a game's tree in its own
 * Android process and native trees are to come (docs/development/native-env/03-design.md §2.1).
 *
 * The socket is abstract and named for the package, so two installs side by side (the release and
 * a test build) never share it; the guest learns the name from BL_SPAWN_SOCKET, which the guest
 * environments of a session and of the store's commands carry (guestEnv). Anything on the device
 * could connect: only this app's own uid - the uid every program in the runtime already runs as -
 * is served.
 *
 * Every frame is one kind byte, a big-endian 32-bit length, and that many bytes.
 *
 *   stand-in to app
 *     R  the request, JSON: {"kind": "sandbox" | "game" | "native", ...what the kind needs}. One
 *        without a kind is a sandbox (droiddeck-bwrap from before this server). The stand-in's
 *        stdio may come with it as SCM_RIGHTS, for a kind that runs its tree on them.
 *     K  a signal the stand-in was sent, for the tree: its number, in decimal.
 *   app to stand-in
 *     P  the tree's pid, in decimal.
 *     O  output for the stand-in's stdout, raw (a sandbox's stdout and stderr, together).
 *     E  an error for the stand-in's stderr, UTF-8.
 *     X  the exit status, in decimal. The last frame.
 *     F  fall back: the app will not start this tree, and the stand-in runs it itself if it can.
 *        The reason, UTF-8, possibly empty. Only before P, and the last frame.
 *
 * A sandbox answers P, then O as it writes, then X; or E and X 1 when it cannot start. A kind
 * nothing serves gets E and X 1; one that is reserved for a later version gets F. The stand-in
 * going away ends its tree.
 */
object SpawnServer {
    private const val TAG = "SpawnServer"
    /** The guest variable naming the socket, without the abstract namespace's leading NUL. */
    const val ENV = "BL_SPAWN_SOCKET"
    const val SANDBOX = "sandbox"
    const val GAME = "game"
    const val NATIVE = "native"
    /** Kinds a later version serves: until then a stand-in asking for one runs it itself. */
    private val RESERVED = setOf(GAME, NATIVE)
    /** Far past any request (a sandbox's carries its few small files); a longer frame is garbage. */
    internal const val MAX_FRAME = 64 shl 20

    private val handlers = ConcurrentHashMap<String, Handler>()
    private val seq = AtomicInteger()
    @Volatile private var server: LocalServerSocket? = null

    /** A kind of request, and what serves it. */
    interface Handler {
        /** Once the socket is ours, before the first request: what an earlier process left can go. */
        fun prepare(context: Context) {}

        /**
         * Serves [request] until its tree is over, on the request's own thread; the socket is
         * closed after. An exception is answered with E and X 1.
         */
        fun serve(context: Context, request: Request)
    }

    /**
     * One stand-in's request: what it asked for, the stdio it sent along (owned here and closed
     * once the request is over; a kind that keeps one dups it), and both ways of the socket.
     */
    class Request(
        val id: Int,
        val kind: String,
        val body: JSONObject,
        val fds: List<FileDescriptor>,
        val input: DataInputStream,
        val out: DataOutputStream,
    )

    /** One frame on the socket. */
    class Frame(val kind: Char, val payload: ByteArray) {
        val text: String get() = String(payload, Charsets.UTF_8)

        /** The decimal number P, X and K carry; null when the payload is not one. */
        val number: Int? get() = text.toIntOrNull()
    }

    fun socketName(context: Context): String = context.packageName + ".spawn"

    /** The guest environment's line naming the socket. */
    fun guestEnv(context: Context): String = "$ENV=${socketName(context)}"

    /** [handler] serves [kind] from now on. Registered before [start], no request finds it missing. */
    fun register(kind: String, handler: Handler) {
        handlers[kind] = handler
    }

    /** Listens for the rest of the process's life; later calls do nothing. */
    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        val name = socketName(app)
        val socket = try {
            LocalServerSocket(name)
        } catch (e: IOException) {
            Log.e(TAG, "cannot listen on $name", e)
            return
        }
        server = socket
        handlers.values.forEach { it.prepare(app) }
        Thread({
            while (true) {
                val client = try { socket.accept() } catch (e: IOException) { Log.e(TAG, "accept", e); break }
                val id = seq.incrementAndGet()
                Thread({ serve(app, client, id) }, "spawn-$id").apply { isDaemon = true; start() }
            }
        }, "spawn-server").apply { isDaemon = true; start() }
    }

    private fun serve(context: Context, client: LocalSocket, id: Int) {
        client.use {
            val out = DataOutputStream(client.outputStream)
            try {
                val peer = client.peerCredentials
                if (peer.uid != Process.myUid()) {
                    Log.w(TAG, "refused a request for uid ${peer.uid}")
                    return
                }
            } catch (e: IOException) {
                Log.e(TAG, "request $id", e)
                return
            }
            // Ancillary fds arrive with the bytes they were sent with: the request's.
            answer(context, id, DataInputStream(client.inputStream), out) {
                client.ancillaryFileDescriptors?.toList() ?: emptyList()
            }
        }
    }

    /**
     * Reads one request from [input] and has its kind's handler serve it, or answers for it when
     * nothing does. [received] gives the fds that came with the request, once it has been read.
     */
    internal fun answer(context: Context, id: Int, input: DataInputStream, out: DataOutputStream,
                        received: () -> List<FileDescriptor>) {
        var fds = emptyList<FileDescriptor>()
        var kind = "request"
        try {
            val first = read(input) ?: return
            fds = received()
            if (first.kind != 'R') throw ProtocolException("expected a request, not '${first.kind}'")
            val body = JSONObject(first.text)
            kind = body.optString("kind").ifEmpty { SANDBOX }
            val handler = handlers[kind]
            when {
                handler != null -> handler.serve(context, Request(id, kind, body, fds, input, out))
                kind in RESERVED -> {
                    Log.i(TAG, "$kind $id: not served by this version; the stand-in runs it itself")
                    frame(out, 'F', "DroidDeck does not start a $kind here yet".toByteArray())
                }
                else -> throw ProtocolException("unknown request kind '$kind'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "$kind $id", e)
            runCatching {
                frame(out, 'E', (e.message ?: e.toString()).toByteArray())
                frame(out, 'X', "1".toByteArray())
            }
        } finally {
            fds.forEach { runCatching { Os.close(it) } }
        }
    }

    /** Writes one frame, flushed so the stand-in has it at once. */
    fun frame(out: DataOutputStream, kind: Char, payload: ByteArray) {
        out.writeByte(kind.code)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    /**
     * The next frame, or null when the peer closed between frames. A stream that ends inside a
     * frame, or a length no frame has, throws: whatever is on the other end is not a stand-in.
     */
    fun read(input: DataInputStream): Frame? {
        val kind = input.read()
        if (kind < 0) return null
        val size = input.readInt()
        if (size < 0 || size > MAX_FRAME) throw ProtocolException("a '${kind.toChar()}' frame of $size bytes")
        return Frame(kind.toChar(), ByteArray(size).also { input.readFully(it) })
    }
}
