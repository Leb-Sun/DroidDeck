package com.droiddeck.launcher.session

import android.app.GameState
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateHintsTest {
    private val calls = ArrayList<Pair<Boolean, Int>>()
    private val recorded = ArrayList<Map<String, Any?>>()

    private fun hints(executor: Executor = Executor { it.run() }, target: GameStateHints.Target = GameStateHints.Target { loading, mode -> calls += loading to mode }) =
        GameStateHints.Hints(target, executor) { recorded += it }

    private fun GameStateHints.Hints.send(event: String, vararg fields: Pair<String, Any?>) = event(event, mapOf(*fields))

    private val loading = true to GameState.MODE_CONTENT
    private val content = false to GameState.MODE_CONTENT
    private val gameplay = false to GameState.MODE_GAMEPLAY_INTERRUPTIBLE
    private val cleared = false to GameState.MODE_NONE

    @Test fun steamLoadsUntilBigPictureThenFromALaunchUntilTheGameHasFocus() {
        val hints = hints()
        hints.send("session.created", "mode" to "steam")
        hints.send("service.started", "mode" to "steam")
        hints.send("guest.started", "pid" to 42)
        hints.send("session.ready")
        hints.send("focus.changed", "focusedApp" to 769)
        hints.send("steam.game_launch_requested", "gameId" to "570", "generation" to 1)
        // Big Picture keeps focus while the game starts.
        hints.send("focus.changed", "focusedApp" to 769)
        hints.send("focus.changed", "focusedApp" to 570)
        // The game exits.
        hints.send("focus.changed", "focusedApp" to 769)
        hints.send("session.stopping", "status" to 0)
        hints.send("session.stopped", "status" to 0)
        assertEquals(listOf(loading, content, loading, gameplay, content, cleared), calls)
        assertEquals(
            listOf(
                mapOf("loading" to true, "mode" to "content", "after" to "session.created"),
                mapOf("loading" to false, "mode" to "content", "after" to "focus.changed"),
                mapOf("loading" to true, "mode" to "content", "after" to "steam.game_launch_requested", "appId" to 570L),
                mapOf("loading" to false, "mode" to "gameplay_interruptible", "after" to "focus.changed", "appId" to 570L),
                mapOf("loading" to false, "mode" to "content", "after" to "focus.changed"),
                mapOf("loading" to false, "mode" to "none", "after" to "session.stopping"),
            ),
            recorded,
        )
    }

    @Test fun anAgentLaunchAndTheLaunchItQueuesAreOneChange() {
        val hints = hints()
        hints.send("session.created", "mode" to "steam")
        hints.send("focus.changed", "focusedApp" to 769)
        hints.send("agent.launch_requested", "appId" to "570")
        hints.send("steam.game_launch_requested", "gameId" to "570", "generation" to 1)
        hints.send("focus.changed", "focusedApp" to 570L)
        assertEquals(listOf(loading, content, loading, gameplay), calls)
    }

    @Test fun aLaunchDuringTheStartLoadsUntilTheGameNotBigPicture() {
        val hints = hints()
        hints.send("session.created", "mode" to "steam")
        hints.send("steam.game_launch_requested", "gameId" to "570")
        hints.send("focus.changed", "focusedApp" to 769)
        assertEquals(listOf(loading), calls)
        hints.send("focus.changed", "focusedApp" to 570)
        assertEquals(listOf(loading, gameplay), calls)
    }

    @Test fun aGameThatLosesFocusIsNoLongerGameplay() {
        val hints = hints()
        hints.send("session.created", "mode" to "steam")
        hints.send("focus.changed", "focusedApp" to 769)
        hints.send("focus.changed", "focusedApp" to 570)
        hints.send("focus.changed", "focusedApp" to null)
        hints.send("focus.changed", "focusedApp" to 0)
        assertEquals(listOf(loading, content, gameplay, content), calls)
    }

    @Test fun nothingInFrontDuringTheStartKeepsItLoading() {
        val hints = hints()
        hints.send("session.created", "mode" to "steam")
        hints.send("focus.changed", "focusedApp" to 0)
        hints.send("session.ready")
        assertEquals(listOf(loading), calls)
    }

    @Test fun outsideSteamTheStartEndsAtReady() {
        val desktop = hints()
        desktop.send("session.created", "mode" to SessionService.MODE_DESKTOP)
        desktop.send("session.ready")
        desktop.send("session.stopping")
        assertEquals(listOf(loading, content, cleared), calls)

        calls.clear()
        val run = hints()
        run.send("session.created", "mode" to SessionService.MODE_RUN)
        run.send("session.ready")
        // READY again after a resume changes nothing.
        run.send("session.ready")
        assertEquals(listOf(loading, gameplay), calls)
    }

    @Test fun launchesOfTheGameInFrontOfBigPictureOrOfNothingAreIgnored() {
        val hints = hints()
        hints.send("session.created", "mode" to "steam")
        hints.send("focus.changed", "focusedApp" to 570)
        hints.send("steam.game_launch_requested", "gameId" to "570")
        hints.send("steam.game_launch_requested", "gameId" to "769")
        hints.send("agent.launch_requested", "appId" to "not a number")
        hints.send("agent.launch_requested")
        assertEquals(listOf(loading, gameplay), calls)
    }

    @Test fun nothingAfterTheEndUntilTheNextSession() {
        val hints = hints()
        hints.send("session.created", "mode" to "steam")
        hints.send("focus.changed", "focusedApp" to 769)
        hints.send("session.stopping", "status" to 0)
        hints.send("focus.changed", "focusedApp" to 570)
        hints.send("steam.game_launch_requested", "gameId" to "570")
        hints.send("session.failed", "code" to "GUEST_EXIT")
        assertEquals(listOf(loading, content, cleared), calls)
        // A session that starts without SessionEvents.begin (the service's own start) starts it over.
        hints.send("service.started", "mode" to "steam")
        assertEquals(listOf(loading, content, cleared, loading), calls)
    }

    @Test fun aFailingPlatformCallIsRecordedAndNeverReachesTheCaller() {
        val hints = hints(target = GameStateHints.Target { _, _ -> throw SecurityException("not a game") })
        hints.send("session.created", "mode" to "steam")
        assertEquals(1, recorded.size)
        assertEquals("java.lang.SecurityException: not a game", recorded[0]["error"])
        assertEquals(true, recorded[0]["loading"])
    }

    @Test fun theWorkRunsOnTheExecutorNotTheCallersThread() {
        val queued = ArrayList<Runnable>()
        val hints = hints(executor = Executor { queued += it })
        hints.send("session.created", "mode" to "steam")
        hints.send("focus.changed", "focusedApp" to 769)
        // Events no state depends on are not even queued.
        hints.send("frame.first")
        hints.send(GameStateHints.EVENT, "loading" to true)
        assertTrue(calls.isEmpty())
        assertEquals(2, queued.size)
        queued.forEach { it.run() }
        assertEquals(listOf(loading, content), calls)
    }
}
