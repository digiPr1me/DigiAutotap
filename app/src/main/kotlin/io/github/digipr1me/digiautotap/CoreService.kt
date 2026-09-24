package io.github.digipr1me.digiautotap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import io.github.digipr1me.digiautotap.core.BondTourSkill
import io.github.digipr1me.digiautotap.core.Chain
import io.github.digipr1me.digiautotap.core.Counted
import io.github.digipr1me.digiautotap.core.DirectorLoop
import io.github.digipr1me.digiautotap.core.DungeonSkill
import io.github.digipr1me.digiautotap.core.FarmSkill
import io.github.digipr1me.digiautotap.core.RunnerSkill
import io.github.digipr1me.digiautotap.core.HelperLog
import io.github.digipr1me.digiautotap.core.HelperState
import io.github.digipr1me.digiautotap.core.MainSwitch
import io.github.digipr1me.digiautotap.core.PassiveSkill
import io.github.digipr1me.digiautotap.core.QuestSkill
import io.github.digipr1me.digiautotap.core.Shell
import io.github.digipr1me.digiautotap.core.Skill
import io.github.digipr1me.digiautotap.core.SkillStats
import io.github.digipr1me.digiautotap.core.Stored
import io.github.digipr1me.digiautotap.core.SummonSkill
import io.github.digipr1me.digiautotap.core.Vision
import io.github.digipr1me.digiautotap.core.WorldSearchSkill
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the shell shows: one value, replaced whole, read by the page and the
 * notification. The loop writes it; nothing else does except the service's
 * front-window and gesture counters.
 */
data class Status(
    val screen: String? = null,
    val note: String = "starting",
    /** The skill that has the screen right now, by its log name, or null between two turns. */
    val task: String? = null,
    val frame: String = "",
    val inFront: String? = null,
    val gestures: Int = 0,
    val readMs: Long = 0,
    val at: Long = 0,
) {
    companion object {
        @Volatile var now = Status()
            private set
        private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()

        fun update(f: (Status) -> Status) {
            synchronized(this) { now = f(now) }
            listeners.forEach { it(now) }
        }

        fun listen(l: (Status) -> Unit) { listeners += l }
        fun unlisten(l: (Status) -> Unit) { listeners -= l }
    }
}

/**
 * The foreground service that holds the core (PLAN_ANDROID_5_SHELL.md 3.1):
 * the director's loop (PLAN_ANDROID_3_DIRECTOR.md 3.4), one round after
 * another at the beat each round asks for. The director is built over the
 * accessibility service as its capture, with a stand-in for every skill
 * that only says where it would have worked; the skills themselves arrive
 * with the skill sessions. What the director taps of its own is the Cancel
 * of "Exit the game?" and the Stage Failed banner, after three quiet
 * seconds each, and nothing else.
 *
 * Type specialUse: of the types Android 14 lists, mediaProjection is the
 * only one about the screen and it requires a MediaProjection token, which
 * an accessibility screenshot does not use; nothing else fits, and the
 * documentation's answer for that is specialUse with the reason in the
 * manifest property.
 */
class CoreService : Service() {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var directorFor: DigiAutotapService? = null
    private val onSwitch: (Boolean) -> Unit = { on ->
        HelperLog.line("main switch: " + if (on) "on" else "off")
        notifyStatus()
        // The dot too, and now: a round is as long as the skill inside it,
        // so waiting for the next one leaves the dot lying about the switch
        // for as long as a dungeon run takes.
        Overlay.refresh()
    }
    private var lastShown: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel(this)
        MainSwitch.listen(onSwitch)
        // The supporter code, read here as well as in the Activity. The
        // director asks `Supporter.unlocked` on every round to decide whom a
        // screen may go to, and until this line only the Activity ever
        // loaded it: a core started by the accessibility service alone --
        // after a reboot, or after the app was force-stopped -- answered
        // null, which reads as "not a supporter", and the supporter tasks
        // (Summon and the quest loop, then) were quietly left out of every
        // round. Seen live: the director
        // parked on a Special Summon screen with "Something is in the way
        // that I did not put there", because the one skill that works there
        // was not in the list. It is idempotent and reads off the main
        // thread, so the two callers cost one read between them.
        //
        // And when the answer arrives, whatever was decided without it is
        // dropped: the check is a PBKDF2 at 600_000 rounds and takes about a
        // second, the director's first round takes 300 ms, and a round that
        // asked too early read "don't know yet" as "not a supporter" and
        // parked on the one screen that skill works on. A park is not
        // evidence once the thing it was decided on has changed.
        Supporter.load(this) {
            if (Supporter.unlocked == true) director?.retry()
            notifyStatus()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        live = this
        stoppedByPlayer = false
        startForeground(NOTIFICATION_ID, notification(this),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (running.compareAndSet(false, true)) {
            // One loop per process, however often the service is started:
            // a core that exists twice would, once it can tap, tap twice.
            thread = Thread(::loop, "digiautotap-core").apply { start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        // Before the status goes out, so that whoever it wakes already reads
        // Stopped rather than a state this service no longer stands behind --
        // and only if this is still the service that is running.
        if (live === this) live = null
        // The dot is the core's: a switch over a service that is gone is a
        // switch that does nothing, and the shell already refuses to leave a
        // notification standing over one.
        Overlay.hide()
        thread?.interrupt()
        MainSwitch.unlisten(onSwitch)
        Status.update { it.copy(screen = null, note = "core stopped") }
        HelperLog.line("core stopped")
        super.onDestroy()
    }

    private fun loop() {
        // OpenCV's native loads here, once, on this thread: never in an
        // accessibility callback, where a slow start ends as "the service
        // does not answer" (section 4). The version is the oracle's.
        val loaded = OpenCVLoader.initLocal()
        HelperLog.line("core started; OpenCV loaded=$loaded version ${Core.VERSION}")
        try {
            while (running.get()) {
                val t0 = SystemClock.elapsedRealtime()
                // A round that threw is one round, not the end of the core.
                // The first live hand-over to a skill ended the whole loop on
                // a single "takeScreenshot: called again too soon" -- the app
                // then sat there with its notification up and read nothing
                // until the service was restarted. What a thrown round costs
                // now is the round, said once in the log; what it cost was
                // the day. (The floor itself is kept in
                // DigiAutotapService.grab, which is where it can be.)
                val beat = try {
                    round()
                } catch (e: Throwable) {
                    HelperLog.line("the round ended in ${e::class.java.simpleName}: ${e.message}" +
                        " -- going on at the next beat")
                    show(null, "a round ended in an error; going on")
                    INTERVAL_MS
                }
                SystemClock.sleep(maxOf(0L, beat - (SystemClock.elapsedRealtime() - t0)))
                if (Thread.interrupted()) break
            }
        } catch (e: Throwable) {
            HelperLog.line("core loop ended: $e")
        } finally {
            director?.close()
            director = null
        }
    }

    /** One round of the director. Returns the beat until the next, in ms. */
    private fun round(): Long {
        val svc = DigiAutotapService.instance
        if (svc == null) {
            show(null, "the accessibility service is not running")
            stopSelf()
            return INTERVAL_MS
        }
        val d = directorOver(svc)
        val t0 = SystemClock.elapsedRealtime()
        // The window event arrives when the switch *starts*: the first frame
        // after it was measured half this app, half the game sliding in, and
        // recognise answered on that picture as if it were the game. So the
        // game has to have been in front for a whole beat before it is read.
        // A guard in front of the director's own inFront question, which it
        // asks every round.
        val game = svc.gamePackage
        if (game != null && svc.inFront() == game && t0 - svc.frontSince < INTERVAL_MS) {
            show(null, "the game just came to the front -- letting it settle")
            overlay(svc, null)
            return INTERVAL_MS
        }
        val tick = d.tick()
        val ms = SystemClock.elapsedRealtime() - t0
        // What the shell reads: the park and the three-second clock are
        // the director's, copied after every round and nowhere else.
        Shell.parked = d.parked
        Shell.takeOverAt = d.takeOverIn?.let { t0 + (it * 1000).toLong() } ?: 0L
        val size = d.frameSize?.let { "${it.first} x ${it.second}" } ?: ""
        show(tick.screen, tick.note, size, ms)
        overlay(svc, tick.screen)
        return (tick.beat * 1000).toLong()
    }

    /**
     * The dot, after every round (PLAN_ANDROID_DESIGN.md 3.3). Three things
     * decide it and all three are asked here rather than remembered: whether
     * the player wants an overlay at all, what state the shell is in, and
     * whether the game is in front.
     *
     * The window goes up and stays up while the core runs -- it is only
     * the *drawing* that stops when the game is not in front. The window
     * carries FLAG_KEEP_SCREEN_ON, because a display that goes to sleep
     * takes `takeScreenshot` with it (PLAN_ANDROID_APP.md 3.4), but only
     * while the game is in front and the switch is on (Overlay.DotView).
     */
    private fun overlay(svc: DigiAutotapService, screen: String?) {
        val want = SettingsStore(this).bool(Overlay.KEY_ON, true)
        if (!want) {
            if (Overlay.up) Overlay.hide()
            return
        }
        if (!Overlay.up) Overlay.show(svc)
        Overlay.update(Shell.state(), screen, svc.inFront() == svc.gamePackage)
    }

    /**
     * The director over this accessibility service, built once per service
     * instance: a director over a service that has since reconnected would
     * be tapping through a dead binder.
     *
     * The six skills themselves since the merge session, where they were
     * `LogSkill` stand-ins before (PLAN_ANDROID_3_DIRECTOR.md 5.8). Every one
     * of them takes its settings as functions, asked afresh on every
     * question: the app writes `digiautotap.json` while the director runs, and
     * a copy read once at the start would answer with the switches as they
     * were then. What the key is called and what it falls back to is core's
     * [Stored], so that the page, the director and a test all read the same
     * file the same way.
     *
     * Eight skills over seven rows: the quest loop and the bond tour came in
     * with L7, and with them the last `LogSkill` went. The tour has no row of
     * its own -- it is the Bond token row's one box -- so [Skills.rowFor]
     * is what the director's `included` asks rather than [Skills.row].
     */
    private fun directorOver(svc: DigiAutotapService): DirectorLoop {
        director?.let { if (directorFor === svc) return it else it.close() }
        val store = { SettingsStore(this) }
        val chain = Chain(Stored.chainSteps(store()), Stored.chainRepeat(store()))

        val dungeon = DungeonSkill(svc, { Stored.dungeon(store()) }, keep = ::keep)
        val summon = SummonSkill(svc, { Stored.summon(store()) }, keep = ::keep)
        val world = WorldSearchSkill(svc, Vision(ApkAssets(this)),
                                     { Stored.worldSearch() }, keep = ::keep)
        val farm = FarmSkill(svc,
            dueAt = { Stored.farmDueAt(store()) },
            remember = { at, grow, why -> Stored.rememberFarm(store(), at, grow, why) },
            knownGrowSeconds = { Stored.farmGrowSeconds(store()) },
            keep = ::keep)
        // The Beatbreak runner: its one field asked afresh at the start of
        // every pass, and the frames it keeps go where every skill's go.
        val runner = RunnerSkill(svc, { Stored.runner(store()) }, keep = ::keep,
                                 feverCounted = { Stored.addRunnerFever(store()) })
        val passive = PassiveSkill(svc, flag = { key, default -> store().bool(key, default) },
                                   keep = ::keep)
        // The quest loop, the last of the stand-ins to go. Its settings are
        // the page's four switches -- `passive_auto` used to come with them,
        // and went with the Auto Spend feature -- and its remembered
        // place in the 15-step routine is written back from inside a round
        // (`_save_quest_step`) -- a position that does not survive the round
        // that reached it is not worth keeping.
        //
        // It asks no supporter code since 2026-09-23: the quest loop is
        // everybody's.
        val quest = QuestSkill(svc, { Stored.quest(store()) },
                               saveStep = { step -> Stored.saveQuestStep(store(), step) },
                               // The day's lock, the same way: written the
                               // moment the loop stops for want of tickets,
                               // read back through `Stored.quest` above.
                               lock = { until, why -> Stored.lockQuest(store(), until, why) },
                               keep = ::keep)
        // The bond tour, the round after the passive helper's and started by
        // it: what it asks is `passive.seen`, so it has to run on the round
        // the helper has just finished, and it walks only where that round
        // collected a token. It is the Bond token row's one box
        // (`passive_all_digimon`), asked in `hasBudget`, so it has no row of
        // its own in the Skills list -- see [Skills.rowFor].
        //
        // `supporter` is the one question core cannot answer: [Unlock]
        // decides whether a code holds, and whether one is *saved* is the
        // shell's (Supporter, MainActivity). The Bond token row is
        // everybody's, the box is a supporter's (2026-09-23), so `included`
        // cannot say it and the tour asks for itself.
        val bond = BondTourSkill(svc, passive,
                                 flag = { key, default -> store().bool(key, default) },
                                 supporter = { Supporter.unlocked == true },
                                 keep = ::keep)

        fun counted(skill: Skill, counts: () -> Map<String, Int>): Skill =
            Counted(skill, counts) { key, c -> SkillStats.add(store(), key, c, today()) }

        val questCounted = counted(quest) { quest.lastCounts }
        // The tour counts under the passive helper's name and not its own:
        // it has no row in the Skills list -- it is the Bond token row's one
        // box ([Skills.rowFor]) -- and a card is what the day's numbers are
        // for, so a "bond" key would be a number nothing ever shows. Wrapped
        // by hand for that one reason: `counted` takes the skill's own key.
        val bondCounted = Counted(bond, { bond.lastCounts }) { _, c ->
            SkillStats.add(store(), "passive", c, today())
        }

        val skills: List<Skill> = listOf(
            counted(dungeon) { dungeon.lastCounts },
            counted(summon) { summon.lastCounts },
            counted(world) { world.lastCounts },
            counted(farm) { farm.lastCounts },
            counted(runner) { runner.lastCounts },
            // The quest loop is a chain step of its own ("run until it
            // stops", chain.WAITING) and a round on the main screen: the same
            // skill in both lists, as app.py has it. One counter over both,
            // because what it counts is a pass -- one tick as a round, the
            // whole wait as a chain step -- and QuestSkill clears its own at
            // the start of each.
            questCounted,
        )
        // The order is the order of the evidence: the helper's round decides
        // whether there was a token, the tour reads that, and the quest loop
        // is beside them both. Each gets a fresh frame of its own, so none
        // aims with a picture taken before the one before it tapped.
        val rounds: List<Skill> = listOf(
            counted(passive) { passive.lastCounts },
            bondCounted,
            questCounted,
        )

        HelperLog.line("director: chain " +
            (if (chain.steps.isEmpty()) "empty" else chain.steps.joinToString(" -> ") { it.key }) +
            (if (chain.repeat) ", repeating" else "") + " -- read now; a change takes effect at the next start")
        val d = DirectorLoop(
            cap = svc, skills = skills, rounds = rounds,
            game = { svc.gamePackage },
            mode = { Stored.mode(store()) },
            chain = chain,
            included = { skill -> Skills.included(Skills.rowFor(skill.key), store(), Supporter.unlocked) },
            keep = ::keep,
            // The overlay's status line, at once: a round is as long as the
            // skill inside it, and `show` below runs only when it is over.
            busy = { name ->
                Status.update { it.copy(task = name) }
                Overlay.refresh()
            },
        )
        director = d
        directorFor = svc
        return d
    }

    /** A frame the director wants looked at afterwards, named by the clock, never over an older one. */
    private fun keep(frame: Mat, tag: String) {
        val dir = Paths.debugDir(this, "director")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val png = File(dir, "${tag}_$stamp.png")
        Imgcodecs.imwrite(png.path, frame)
        HelperLog.line("frame kept: ${png.path}")
    }

    private fun show(screen: String?, note: String, frame: String = "", ms: Long = 0) {
        Status.update { it.copy(screen = screen, note = note, frame = frame, readMs = ms,
                                at = System.currentTimeMillis()) }
        // The log and the notification change when what is seen changes,
        // not once a second. The director writes its own sentences to the
        // log as it goes; what is logged here is the screen with the frame
        // behind it, and the service's own notes.
        val shown = screen ?: note
        if (shown != lastShown) {
            lastShown = shown
            if (screen != null) HelperLog.line("screen: $screen ($frame, $ms ms)")
            else if (director == null || !note.startsWith("the game")) HelperLog.line(note)
            notifyStatus()
        }
    }

    private fun notifyStatus() {
        if (!running.get()) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(this))
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL = "digiautotap"

        /**
         * The service that is running, or none. [Shell] asks [alive] to tell
         * Stopped from every other state, and a notification over a service
         * that is gone would be a lie (PLAN_ANDROID_5_SHELL.md 4).
         *
         * The instance and not a flag, because `stopService` returns before
         * `onDestroy` runs: the accessibility service is unbound and rebound
         * often enough -- every `uiautomator dump` does it -- that the old
         * instance's onDestroy landed *after* the new one's onStartCommand,
         * cleared the flag, and left the app saying Stopped over a core that
         * was reading the screen a line at a time in the log beside it.
         * Measured in LDPlayer, three times in one session. DigiAutotapService
         * already guards itself the same way (`gone`).
         */
        @Volatile
        var live: CoreService? = null

        val alive: Boolean get() = live != null

        /**
         * Stop was pressed in the notification. Opening the app then leaves
         * it stopped: a Stop that the next glance at the app quietly undoes
         * is not a Stop. Cleared by Start, and by any fresh start of the
         * service -- it lives for this process, as the loop does.
         */
        @Volatile
        var stoppedByPlayer: Boolean = false

        /**
         * One read a second. The spike measured the system's floor for
         * takeScreenshot; this stays well above it, and a failed call waits
         * out the same beat before the next -- never an immediate retry.
         */
        const val INTERVAL_MS = 1000L

        /** The director, while the loop runs: the shell's "Try again" reaches it here. */
        @Volatile
        var director: DirectorLoop? = null
            private set

        fun channel(c: Context) {
            val ch = NotificationChannel(CHANNEL, "DigiAutotap", NotificationManager.IMPORTANCE_LOW)
            ch.description = "The main switch, and what DigiAutotap sees"
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /**
         * "DigiAutotap · <state>", the pill's own sentence under it, and the
         * three actions (PLAN_ANDROID_DESIGN.md 2.7, 4.1). The first one
         * carries the same word as a tap on the dot does -- Pause, Resume,
         * Try again -- and the second the same as the app's big button
         * while it runs, Stop, so that the notification, the pill and the log
         * never call one thing by two names.
         */
        fun notification(c: Context): Notification {
            val state = Shell.state()
            fun broadcast(code: Int, action: String) = PendingIntent.getBroadcast(
                c, code, Intent(c, ActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
                                                 PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_digiautotap)
                .setColor(Theme.of(c).PRIMARY)
                .setContentTitle("DigiAutotap · ${state.word}")
                .setContentText(Shell.sentence(state))
                .setStyle(Notification.BigTextStyle().bigText(Shell.sentence(state)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(
                    null, state.action, broadcast(1, ActionReceiver.PRESS)).build())
                .addAction(Notification.Action.Builder(
                    null, "Stop", broadcast(2, ActionReceiver.STOP)).build())
                .addAction(Notification.Action.Builder(null, "Open", open).build())
                .build()
        }

        /** Draw it again now, for a change the loop itself did not make. */
        fun refresh(c: Context) {
            if (!alive) return
            c.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(c))
        }
    }
}

/**
 * The notification's two buttons. Neither holds a state of its own: the
 * first writes core's one switch through [press], the same call the dot's
 * tap makes, and the second ends the service through [stop], the same call
 * the app's big button makes, which takes the notification with it.
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PRESS -> press(context)
            STOP -> stop(context, "the notification")
        }
    }

    companion object {
        const val PRESS = "io.github.digipr1me.digiautotap.PRESS"
        const val STOP = "io.github.digipr1me.digiautotap.STOP"

        /**
         * The service ended by the player -- from the notification's Stop or
         * the app's big button -- and kept ended: [CoreService.stoppedByPlayer]
         * is what stops the next rebind from undoing it.
         */
        fun stop(c: Context, from: String) {
            CoreService.stoppedByPlayer = true
            HelperLog.line("stopped from $from")
            c.stopService(Intent(c, CoreService::class.java))
        }

        /**
         * The one thing the dot's tap and the notification's first action
         * do, in one place and against one state -- and the app's big
         * button while it says Start.
         */
        fun press(c: Context) {
            when (Shell.state()) {
                HelperState.STOPPED ->
                    if (DigiAutotapService.instance == null) {
                        HelperLog.line("start: the accessibility service is not running -- " +
                            "switch it on first")
                    } else {
                        CoreService.stoppedByPlayer = false
                        // Start means running: a core stopped while it was
                        // paused would otherwise come back paused, and the
                        // app's button would say Stop over a DigiAutotap
                        // that does nothing.
                        MainSwitch.set(true)
                        DigiAutotapService.startCore(c, "start pressed", force = true)
                    }
                HelperState.PAUSED -> MainSwitch.set(true)
                HelperState.PARKED -> {
                    CoreService.director?.retry()
                    Shell.parked = null
                    MainSwitch.set(true)
                }
                else -> MainSwitch.set(false)
            }
            CoreService.refresh(c)
        }
    }
}
