package com.droiddeck.launcher.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private fun encode(vararg frames: Pair<Char, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    val out = DataOutputStream(bytes)
    frames.forEach { (kind, payload) -> SpawnServer.frame(out, kind, payload) }
    return bytes.toByteArray()
}

private fun decode(bytes: ByteArray): List<SpawnServer.Frame> {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    return generateSequence { SpawnServer.read(input) }.toList()
}

/** The frames on SpawnServer's socket, as droiddeck-bwrap writes and reads them (struct ">I"). */
class SpawnServerFramingTest {
    @Test fun aFrameIsItsKindABigEndianLengthAndThePayload() {
        assertArrayEquals(byteArrayOf('K'.code.toByte(), 0, 0, 0, 2, '1'.code.toByte(), '5'.code.toByte()),
            encode('K' to "15".toByteArray()))
        assertArrayEquals(byteArrayOf('F'.code.toByte(), 0, 0, 0, 0), encode('F' to ByteArray(0)))
    }

    @Test fun everyFrameRoundTrips() {
        val raw = byteArrayOf(0, -1, 10, 13, 'X'.code.toByte())
        val frames = decode(encode(
            'R' to """{"kind":"sandbox","argv":["/app/bin/game"]}""".toByteArray(),
            'P' to "4242".toByteArray(),
            'O' to raw,
            'O' to ByteArray(0),
            'E' to "sandbox source /ü is missing".toByteArray(),
            'K' to "15".toByteArray(),
            'F' to "DroidDeck does not start a game here yet".toByteArray(),
            'F' to ByteArray(0),
            'X' to "143".toByteArray(),
        ))
        assertEquals(listOf('R', 'P', 'O', 'O', 'E', 'K', 'F', 'F', 'X'), frames.map { it.kind })
        assertEquals("""{"kind":"sandbox","argv":["/app/bin/game"]}""", frames[0].text)
        assertEquals(4242, frames[1].number)
        assertArrayEquals(raw, frames[2].payload)
        assertEquals(0, frames[3].payload.size)
        assertEquals("sandbox source /ü is missing", frames[4].text)
        assertEquals(15, frames[5].number)
        assertEquals("DroidDeck does not start a game here yet", frames[6].text)
        assertEquals("", frames[7].text)
        assertEquals(143, frames[8].number)
    }

    @Test fun theStreamEndingBetweenFramesIsTheEnd() {
        assertNull(SpawnServer.read(DataInputStream(ByteArrayInputStream(ByteArray(0)))))
        assertEquals(1, decode(encode('X' to "0".toByteArray())).size)
    }

    @Test fun aStreamThatEndsInsideAFrameThrows() {
        val whole = encode('O' to "hello".toByteArray())
        for (cut in 1 until whole.size) {
            try {
                decode(whole.copyOf(cut))
                fail("$cut of ${whole.size} bytes read as a frame")
            } catch (e: EOFException) { /* expected */ }
        }
    }

    @Test fun aLengthNoFrameHasThrowsBeforeAnythingIsAllocated() {
        val negative = byteArrayOf('R'.code.toByte(), -1, -1, -1, -1)
        val huge = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply { writeByte('R'.code); writeInt(SpawnServer.MAX_FRAME + 1) }
        }.toByteArray()
        for (garbage in listOf(negative, huge)) {
            try {
                decode(garbage)
                fail("a frame of garbage length was read")
            } catch (e: ProtocolException) { /* expected */ }
        }
    }

    @Test fun aNumberThatIsNotOneIsNull() {
        for (payload in listOf("", "abc", "1.5", " 15", "99999999999")) {
            assertNull(payload, SpawnServer.Frame('K', payload.toByteArray()).number)
        }
        assertEquals(-1, SpawnServer.Frame('X', "-1".toByteArray()).number)
    }
}

/** What SpawnServer answers for a request, before and around its kind's handler. */
@RunWith(RobolectricTestRunner::class)
class SpawnServerDispatchTest {
    private class Recorder(private val reply: (SpawnServer.Request) -> Unit = {}) : SpawnServer.Handler {
        val requests = ArrayList<SpawnServer.Request>()
        override fun serve(context: android.content.Context, request: SpawnServer.Request) {
            requests += request
            reply(request)
        }
    }

    private fun answer(request: ByteArray, fds: List<FileDescriptor> = emptyList()): List<SpawnServer.Frame> {
        val bytes = ByteArrayOutputStream()
        SpawnServer.answer(RuntimeEnvironment.getApplication(), 7, DataInputStream(ByteArrayInputStream(request)),
            DataOutputStream(bytes)) { fds }
        return decode(bytes.toByteArray())
    }

    private fun request(json: String) = encode('R' to json.toByteArray())

    @Test fun aRequestGoesToItsKindsHandlerWithTheFdsThatCameWithIt() {
        val handler = Recorder { SpawnServer.frame(it.out, 'X', "0".toByteArray()) }
        SpawnServer.register("test-echo", handler)
        val stdin = FileDescriptor()
        val frames = answer(request("""{"kind":"test-echo","argv":["/bin/true"]}"""), listOf(stdin))
        val got = handler.requests.single()
        assertEquals(7, got.id)
        assertEquals("test-echo", got.kind)
        assertEquals("/bin/true", got.body.getJSONArray("argv").getString(0))
        assertSame(stdin, got.fds.single())
        assertEquals(listOf('X'), frames.map { it.kind })
    }

    @Test fun aRequestWithoutAKindIsASandbox() {
        val handler = Recorder()
        SpawnServer.register(SpawnServer.SANDBOX, handler)
        answer(request("""{"argv":["/app/bin/game"],"ops":[]}"""))
        assertEquals(SpawnServer.SANDBOX, handler.requests.single().kind)
    }

    @Test fun anUnknownKindIsAnsweredWithAnErrorAndStatus1() {
        val frames = answer(request("""{"kind":"teleport"}"""))
        assertEquals(listOf('E', 'X'), frames.map { it.kind })
        assertTrue(frames[0].text, frames[0].text.contains("unknown request kind 'teleport'"))
        assertEquals(1, frames[1].number)
    }

    @Test fun aReservedKindIsToldToFallBack() {
        for (kind in listOf(SpawnServer.GAME, SpawnServer.NATIVE)) {
            val frames = answer(request("""{"kind":"$kind","argv":["/bin/true"]}"""))
            assertEquals(kind, listOf('F'), frames.map { it.kind })
            assertTrue(frames[0].text, frames[0].text.contains(kind))
        }
    }

    @Test fun aHandlerThatFailsIsAnsweredWithItsMessageAndStatus1() {
        SpawnServer.register("test-broken", Recorder { throw IllegalStateException("no runtime installed") })
        val frames = answer(request("""{"kind":"test-broken"}"""))
        assertEquals(listOf('E', 'X'), frames.map { it.kind })
        assertEquals("no runtime installed", frames[0].text)
        assertEquals(1, frames[1].number)
    }

    @Test fun anythingButARequestFirstIsAnError() {
        for (first in listOf(encode('K' to "15".toByteArray()), encode('R' to "not json".toByteArray()))) {
            val frames = answer(first)
            assertEquals(listOf('E', 'X'), frames.map { it.kind })
            assertEquals(1, frames[1].number)
        }
    }

    @Test fun aPeerThatClosesAtOnceGetsNothing() {
        var asked = false
        val bytes = ByteArrayOutputStream()
        SpawnServer.answer(RuntimeEnvironment.getApplication(), 7, DataInputStream(ByteArrayInputStream(ByteArray(0))),
            DataOutputStream(bytes)) { asked = true; emptyList() }
        assertEquals(0, bytes.size())
        assertFalse(asked)
    }
}
