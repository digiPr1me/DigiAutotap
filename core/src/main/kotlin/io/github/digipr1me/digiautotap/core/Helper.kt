package io.github.digipr1me.digiautotap.core

/**
 * Not U+23F8 and U+25B6: both have an emoji presentation, and Android drew
 * the pause on the main button as an orange emoji tile. These two are in no
 * emoji set, so the font that has them is the text font. Above the enum
 * rather than in its companion, which is not initialised yet where the
 * constants of an enum are built.
 */
const val PAUSE_GLYPH = "❚❚"
const val PLAY_GLYPH = "►"

/**
 * What DigiAutotap is doing, in the six words the status pill, the
 * notification and the log all use (PLAN_ANDROID_DESIGN.md 4.1). One word
 * per state and one button per state, so that nothing has to decide twice
 * what to call the same thing.
 *
 * In `core` since the merge session (PLAN_ANDROID_5_SHELL.md 5.6 asked for
 * it): there is no Android class in here, and Waiting, Running and Parked
 * come from the director, which is core's. What stayed in `app` is the one
 * thing that is really the app's -- which colour each state is drawn in
 * (`Theme.kt`, the only file that writes a colour).
 */
enum class HelperState(val word: String, val action: String, val glyph: String) {
    /** The loop is reading and there is nothing on the screen for it. */
    IDLE("Idle", "Pause", PAUSE_GLYPH),

    /** The three-second clock is running: the player has just touched the game. */
    WAITING("Waiting for you", "Pause", PAUSE_GLYPH),

    /** The loop is reading and `recognise` knows the screen. */
    RUNNING("Running", "Pause", PAUSE_GLYPH),

    /** The main switch is off. */
    PAUSED("Paused", "Resume", PLAY_GLYPH),

    /** Something is in the way that DigiAutotap did not put there. */
    PARKED("Parked", "Try again", "↻"),

    /** The service was stopped, from the app or the notification: no loop, no notification. */
    STOPPED("Stopped", "Start", PLAY_GLYPH);
}

/**
 * The one state behind the pill, the big button and the notification. It
 * holds no switch of its own -- [MainSwitch] is the switch
 * (PLAN_ANDROID_5_SHELL.md 3.3) -- and no copy of what the loop saw.
 *
 * Three things it cannot know by itself, and so is told, each by a function
 * and never by a copied value: whether the service is alive, what the last
 * round saw, and what time it is on a clock that does not jump. The app
 * fills them in once (`CoreService`); a test fills them in with its own.
 */
object Shell {

    /** The director's reason for standing still; the service copies it after every round. */
    @Volatile
    var parked: String? = null

    /** The clock reading the three-second wait runs out at; 0 while it is not running. */
    @Volatile
    var takeOverAt: Long = 0L

    /** Is the core's loop running? `CoreService.alive`. */
    @Volatile
    var alive: () -> Boolean = { false }

    /** What the last round saw: the screen `classify` named, or null, and the note beside it. */
    @Volatile
    var seen: () -> Pair<String?, String> = { null to "" }

    /** Milliseconds on a clock that does not jump: `SystemClock.elapsedRealtime`. */
    @Volatile
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /**
     * What DigiAutotap is in. The debug corner could put a state of its own
     * in front of this one until 2026-09-22, so that the pill, the button
     * and the notification could be drawn for a state nothing could reach;
     * the corner went, and with it the second answer to one question.
     */
    fun state(): HelperState {
        val (screen, _) = seen()
        return when {
            !alive() -> HelperState.STOPPED
            !MainSwitch.on -> HelperState.PAUSED
            parked != null -> HelperState.PARKED
            takeOverAt > clock() -> HelperState.WAITING
            screen != null -> HelperState.RUNNING
            else -> HelperState.IDLE
        }
    }

    /** The one sentence under the pill, and the notification's second line. */
    fun sentence(state: HelperState): String {
        val (screen, note) = seen()
        return when (state) {
            HelperState.STOPPED -> "The service is off."
            HelperState.PAUSED -> "Paused. Nothing happens until you resume."
            HelperState.PARKED -> parked ?: "Something unexpected is on the screen."
            HelperState.WAITING -> {
                val left = (takeOverAt - clock() + 999) / 1000
                val what = screen ?: "This screen"
                if (left > 0) "$what. Taking over in $left s unless you touch the game."
                else "$what. Taking over unless you touch the game."
            }
            HelperState.RUNNING -> "Sees: " + (screen ?: note) +
                (if (screen != null && note.isNotEmpty()) " -- " + note else "")
            HelperState.IDLE -> note
        }
    }
}
