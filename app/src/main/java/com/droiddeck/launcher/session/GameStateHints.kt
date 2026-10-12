package com.droiddeck.launcher.session

import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Performance > Game state hints (off by default): Android is told what the session is doing through
 * GameManager.setGameState (Android 13+), read off the session events SessionPerf takes its
 * milestones from (docs/development/native-env/03-design.md §2.2). A device may answer loading with
 * a CPU boost (the power HAL's GAME_LOADING mode). The states are [GameStateMachine]'s, and each
 * change is a hints.game_state event in the session's events.
 */
object GameStateHints {
    private const val TAG = "GameStateHints"
    const val EVENT = "hints.game_state"

    @Volatile private var current: Hints? = null

    /** One thread for every session's hints, taking events in the order they came; idle, it ends. */
    private val executor = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "game-state-hints").apply { isDaemon = true }
    }

    /** At the session's creation (SessionEvents.begin), before its first event. */
    fun begin(context: Context) {
        current = if (Build.VERSION.SDK_INT >= 33 && SessionPrefs.gameStateHints(context)) {
            Hints(Platform(context.applicationContext), executor)
        } else null
    }

    /** Every session event (SessionEvents.record). */
    fun event(event: String, fields: Map<String, Any?>) {
        current?.event(event, fields)
    }

    /** What Android is told: GameManager.setGameState, or a test's fake. */
    fun interface Target {
        fun set(loading: Boolean, mode: Int)
    }

    @RequiresApi(33)
    private class Platform(context: Context) : Target {
        private val manager: GameManager? = context.getSystemService(GameManager::class.java)
        override fun set(loading: Boolean, mode: Int) {
            manager?.setGameState(GameState(loading, mode))
        }
    }

    /**
     * One session's hints. The machine and the platform call run on [executor], never on the thread
     * that recorded the event, and nothing they throw reaches it. [record] gets each change.
     */
    class Hints(
        private val target: Target,
        private val executor: Executor,
        private val record: (Map<String, Any?>) -> Unit = { SessionEvents.record(EVENT, it) },
    ) {
        private val machine = GameStateMachine()

        fun event(event: String, fields: Map<String, Any?>) {
            if (event !in GameStateMachine.EVENTS) return
            try {
                executor.execute { runCatching { handle(event, fields) }.onFailure { Log.w(TAG, "game state", it) } }
            } catch (e: RejectedExecutionException) {
                Log.w(TAG, "game state after $event not set", e)
            }
        }

        private fun handle(event: String, fields: Map<String, Any?>) {
            val hint = machine.event(event, fields) ?: return
            val failure = runCatching { target.set(hint.loading, hint.mode) }.exceptionOrNull()
            if (failure != null) Log.w(TAG, "setGameState(${hint.loading}, ${hint.mode})", failure)
            val change = linkedMapOf<String, Any?>(
                "loading" to hint.loading, "mode" to GameStateMachine.modeName(hint.mode), "after" to event,
            )
            hint.appId?.let { change["appId"] = it }
            failure?.let { change["error"] = it.toString() }
            record(change)
        }
    }
}

/**
 * The game state a session is in, from its events: loading from the session's creation until Big
 * Picture (app 769) has focus - READY outside a Steam session - and from a game's launch until that
 * game has it; gameplay while a game has focus, content in Big Picture; none once the session ends,
 * which is the app's own menu. [event] answers only when the state changes.
 */
class GameStateMachine {
    data class Hint(val loading: Boolean, val mode: Int, val appId: Long? = null)

    private var active = false
    private var steam = true
    private var run = false
    private var starting = false
    /** The game a launch asked for, until a game has focus. */
    private var launching: Long? = null
    /** The game in front. */
    private var focused: Long? = null
    private var last: Hint? = null

    fun event(event: String, fields: Map<String, Any?>): Hint? {
        val next = when (event) {
            "session.created", "service.started" -> start(fields["mode"]?.toString())
            "session.ready" -> ready()
            "focus.changed" -> focus((fields["focusedApp"] as? Number)?.toLong())
            "steam.game_launch_requested" -> launch(fields["gameId"]?.toString()?.toLongOrNull())
            "agent.launch_requested" -> launch(fields["appId"]?.toString()?.toLongOrNull())
            "session.stopping", "session.stopped", "session.failed" -> end()
            else -> null
        } ?: return null
        if (last?.loading == next.loading && last?.mode == next.mode) return null
        last = next
        return next
    }

    private fun start(mode: String?): Hint {
        active = true
        starting = true
        launching = null
        focused = null
        if (mode != null) {
            steam = mode == SessionService.MODE_STEAM
            run = mode == SessionService.MODE_RUN
        }
        return Hint(true, GameState.MODE_CONTENT)
    }

    /** Outside a Steam session no Big Picture comes: the start is over at READY. */
    private fun ready(): Hint? {
        if (!active || !starting || steam) return null
        starting = false
        return Hint(false, if (run) GameState.MODE_GAMEPLAY_INTERRUPTIBLE else GameState.MODE_CONTENT)
    }

    private fun focus(app: Long?): Hint? {
        if (!active) return null
        return when {
            app == BIG_PICTURE -> {
                focused = null
                // A launch is still loading until its game has focus.
                if (launching != null) return null
                starting = false
                Hint(false, GameState.MODE_CONTENT)
            }
            app != null && app > 0 -> {
                starting = false
                launching = null
                focused = app
                Hint(false, GameState.MODE_GAMEPLAY_INTERRUPTIBLE, app)
            }
            // Nothing of Steam's in front: the game that had focus lost it.
            focused != null -> {
                focused = null
                Hint(launching != null, GameState.MODE_CONTENT)
            }
            else -> null
        }
    }

    private fun launch(app: Long?): Hint? {
        if (!active || app == null || app <= 0 || app == BIG_PICTURE || app == focused) return null
        launching = app
        return Hint(true, GameState.MODE_CONTENT, app)
    }

    private fun end(): Hint? {
        if (!active) return null
        active = false
        starting = false
        launching = null
        focused = null
        return Hint(false, GameState.MODE_NONE)
    }

    companion object {
        /** What GameStateHints.Hints acts on; anything else is not even queued. */
        val EVENTS = setOf(
            "session.created", "service.started", "session.ready", "focus.changed",
            "steam.game_launch_requested", "agent.launch_requested",
            "session.stopping", "session.stopped", "session.failed",
        )
        private const val BIG_PICTURE = 769L

        fun modeName(mode: Int): String = when (mode) {
            GameState.MODE_NONE -> "none"
            GameState.MODE_GAMEPLAY_INTERRUPTIBLE -> "gameplay_interruptible"
            GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE -> "gameplay_uninterruptible"
            GameState.MODE_CONTENT -> "content"
            else -> "unknown"
        }
    }
}
