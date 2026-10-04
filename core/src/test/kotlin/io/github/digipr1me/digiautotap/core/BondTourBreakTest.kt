package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bond tour that breaks off, and the turns after it (PLAN_ABSCHLUSS_1_3.md
 * A5d, K4). On the Poco on 2026-10-02 two tours ended "stopped with 3 raised
 * instead of the Digimon you started with (1)" (18:32:38) and "5 instead of
 * (4)" (19:11:32), and the player's partner stayed another Digimon: the
 * tour's way out went home and forgot where it had been. The first of them
 * came off a screen without a nav bar after the token, called the main
 * screen the Digimon page and tapped its sub-tab row there; the second lost
 * its frames to the app in front.
 *
 * The game is a small model ([Game]) behind the tour's seams: its screens,
 * the Digimon raised, what a tap and the back key do on each -- the sub-tab
 * row's place on the main screen is the HUD's gear card, and a tap there
 * opens its "Equipped" window, as on LDPlayer instance 0 at 1080 x 2320 on
 * 2026-10-03. The tour's steps are the real ones -- [BondTour.enter],
 * `raise`, `goHome`, `attempt`, the main screen asked over a few seconds --
 * and so is the token's round: [TourCollect] over a real [PassiveSkill] on
 * [PaintPassive]'s main screen, every other screen a dark frame it does not
 * take for the main one. Each case is counted over the turns of
 * [BondTourSkill] that follow one another on the main screen: the one that
 * breaks, the one after it, and one more that must do nothing.
 *
 * Since A5h (the player's answer to question 18, 2026-10-03) the turn after
 * the break goes on with the tour as with one the main switch stopped: the
 * Digimon it had not reached, the one it began with last. A5d's turn raised
 * only that first one again, and does so still where the all-Digimon box
 * has been unticked since.
 */
class BondTourBreakTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    enum class S { MAIN, PAGE, PARTNER, SELECTED, PROMPT, NO_NAV, EQUIPPED, TITLE_PROMPT, APP }

    /** The game as the tour meets it. */
    class Game(val total: Int = 5, var raised: Int = 1) {
        var s = S.MAIN
        private var selected = -1

        /** Grabs of the main screen still shown after the Digimon tab's tap: the page comes late. */
        var pageLate = 0
        private var pageIn: Int? = null

        /** The HUD's gear cards read as a sub-tab row, as on 57 of 450 corpus main screens. */
        var hudReadsAsSubtab = false

        /** The Digimon tab's tap opens nothing. */
        var tabDead = false

        /** Back on the main screen by the globe or the back key, with the Raises behind it. */
        var cameHome: (Int) -> Unit = {}

        /** Raise pressed on this Digimon, its prompt now open. */
        var pressedRaise: (Int) -> Unit = {}

        /** What the game does by itself: after so many grabs, this screen. */
        private val script = ArrayDeque<Pair<Int, S>>()

        val raises = ArrayList<Int>()
        var gearWindows = 0
        var titlePrompts = 0
        /** OK on "Return to the title screen?": the session is over. */
        var titleOks = 0
        var cancels = 0
        var grabs = 0
        private val shown = HashMap<Long, S>()

        fun then(grabs: Int, screen: S) {
            script.addLast(grabs to screen)
        }

        fun grab(): Mat {
            grabs += 1
            script.firstOrNull()?.let { (n, screen) ->
                if (n <= 0) {
                    script.removeFirst()
                    s = screen
                } else {
                    script[0] = n - 1 to screen
                }
            }
            if (s == S.APP) throw CaptureError("the game is not in front (io.github.digipr1me.digiautotap)")
            var now = s
            pageIn?.let { n ->
                if (n > 0) {
                    pageIn = n - 1
                    now = S.MAIN
                } else {
                    pageIn = null
                    s = S.PAGE
                    now = S.PAGE
                }
            }
            val img = if (now == S.MAIN) PaintPassive.mainScreen() else PaintPassive.blank(Paint.W, Paint.H)
            shown[img.nativeObj] = now
            return img
        }

        /** The screen [img] was taken of. */
        fun on(img: Mat): S = shown[img.nativeObj] ?: s

        fun autoButton(img: Mat) = if (on(img) == S.MAIN) Dungeon.Button(0.369, 0.764, 0.048, 0.028) else null
        fun homeButton(img: Mat) = if (on(img) in NAV) Dungeon.Button(GLOBE.first, GLOBE.second, 0.048, 0.029) else null
        fun digimonTab(img: Mat) = if (on(img) in NAV) Explore.NavTab(TAB.first, TAB.second, 0.072, 970) else null
        fun partnerSubtab(img: Mat): Bond.Subtab? = when (on(img)) {
            S.PAGE -> Bond.Subtab(SUBTAB.first, SUBTAB.second, 0.11, false)
            S.PARTNER, S.SELECTED -> Bond.Subtab(SUBTAB.first, SUBTAB.second, 0.16, true)
            // The corpus's main screens read it at fx 0.1696 to 0.2890, white over it on some.
            S.MAIN -> if (hudReadsAsSubtab) Bond.Subtab(0.1857, 0.8779, 0.0523, true) else null
            else -> null
        }
        fun gridButton(img: Mat) = if (on(img) in GRID) Bond.Disc(DISC.first, DISC.second, 0.06, 0.03, true) else null
        fun cells(img: Mat) = if (on(img) in GRID) (0 until total).map { cell(it) } else null
        fun raisedCell(img: Mat) = if (on(img) in GRID) raised else null
        fun raiseButton(img: Mat) =
            if (on(img) == S.SELECTED) Bond.Raise(RAISE.first, RAISE.second, 0.22, 0.04, 0.9) else null
        // "Raise <name>?" and "Return to the title screen?" wear one face to the pixel.
        fun recognise(img: Mat): Dungeon.Recognition =
            if (on(img) == S.PROMPT || on(img) == S.TITLE_PROMPT) {
                Dungeon.Recognition(Dungeon.EXIT, null, null, null, null, emptyList(), emptyList(), emptyList(), null,
                                    exitOk = Dungeon.Button(OK.first, OK.second, 0.2, 0.04), exitKind = "party",
                                    anchor = Dungeon.Anchor.MIDDLE)
            } else {
                Dungeon.Recognition(Dungeon.UNKNOWN, null, null, null, null, emptyList(), emptyList(), emptyList(), null)
            }

        fun tap(fx: Double, fy: Double) {
            fun at(p: Pair<Double, Double>) = abs(fx - p.first) < 0.02 && abs(fy - p.second) < 0.02
            when (s) {
                S.MAIN -> when {
                    at(TAB) -> if (tabDead) {} else if (pageLate > 0) pageIn = pageLate else s = S.PAGE
                    // The sub-tab row's place on the main screen is the HUD's gear card.
                    at(SUBTAB) -> {
                        s = S.EQUIPPED
                        gearWindows += 1
                        pageIn = null
                    }
                }
                S.PAGE -> when {
                    at(SUBTAB) -> s = S.PARTNER
                    at(GLOBE) -> home()
                }
                S.PARTNER, S.SELECTED -> {
                    val cell = (0 until total).firstOrNull { at(cell(it)) }
                    when {
                        at(GLOBE) -> home()
                        at(SUBTAB) -> s = S.PARTNER
                        cell != null -> if (cell != raised) {
                            selected = cell
                            s = S.SELECTED
                        }
                        s == S.SELECTED && at(RAISE) -> {
                            s = S.PROMPT
                            pressedRaise(selected)
                        }
                    }
                }
                S.PROMPT -> when {
                    at(OK) -> {
                        raised = selected
                        raises += selected
                        s = S.PARTNER
                    }
                    at(CANCEL) -> {
                        cancels += 1
                        s = S.SELECTED
                    }
                }
                S.TITLE_PROMPT -> when {
                    at(OK) -> titleOks += 1
                    at(CANCEL) -> {
                        cancels += 1
                        s = S.MAIN
                    }
                }
                else -> {}
            }
        }

        fun back() {
            when (s) {
                S.EQUIPPED, S.PAGE, S.PARTNER, S.SELECTED -> home()
                S.PROMPT -> s = S.SELECTED
                S.TITLE_PROMPT -> s = S.MAIN
                // With no window open the back key asks "Return to the title screen?".
                S.MAIN -> {
                    s = S.TITLE_PROMPT
                    titlePrompts += 1
                }
                // A screen the key does not close, and the app in front, which takes it.
                else -> {}
            }
        }

        private fun home() {
            s = S.MAIN
            cameHome(raises.size)
        }

        companion object {
            val NAV = setOf(S.MAIN, S.PAGE, S.PARTNER, S.SELECTED)
            val GRID = setOf(S.PARTNER, S.SELECTED)
            val GLOBE = 0.477 to 0.943
            val TAB = 0.242 to 0.943
            val SUBTAB = 0.18 to 0.878
            val DISC = 0.89 to 0.80
            val RAISE = 0.50 to 0.42
            val OK = 0.66 to 0.62
            /** Mirrored to OK, as on the game's prompts. */
            val CANCEL = 0.34 to 0.62
            fun cell(i: Int) = (0.15 + 0.17 * (i % 5)) to (0.55 + 0.07 * (i / 5))
        }
    }

    /** The real tour, its readers answered by the [Game]. */
    class Walk(private val game: Game, cap: Capture, log: (String) -> Unit, sleep: (Double) -> Unit,
               now: () -> Double, on: () -> Boolean = { true }) :
        BondTour(cap, log = log, on = on, sleep = sleep, now = now) {
        override fun grab(): Mat = game.grab()
        override fun tap(img: Mat, fx: Double, fy: Double, anchor: Dungeon.Anchor) = game.tap(fx, fy)
        override fun gridButton(img: Mat) = game.gridButton(img)
        override fun raiseButton(img: Mat) = game.raiseButton(img)
        override fun cells(img: Mat) = game.cells(img)
        override fun raisedCell(img: Mat) = game.raisedCell(img)
        override fun partnerSubtab(img: Mat) = game.partnerSubtab(img)
        override fun digimonTab(img: Mat) = game.digimonTab(img)
        override fun partnerMenu(img: Mat): Dungeon.Button? = null
        override fun autoButton(img: Mat) = game.autoButton(img)
        override fun homeButton(img: Mat) = game.homeButton(img)
        override fun recognise(img: Mat) = game.recognise(img)
    }

    /** The game, the passive helper and the tour on one clock that moves only when somebody waits. */
    class World(total: Int = 5, raised: Int = 1) {
        var clock = 1000.0
        val log = ArrayList<String>()
        val game = Game(total, raised)

        /** The main switch. */
        var on = true

        /** The all-Digimon box. */
        var box = true
        val cap = DirectorTest.FakeCapture().also { c ->
            c.scene = { game.grab() }
            c.onBack = { game.back() }
        }
        val passive = PassiveSkill(cap, { _, d -> d }, log = { log += it }, now = { clock }, on = { on })
        val skill = BondTourSkill(cap, passive, { _, _ -> box }, log = { log += it }, on = { on },
                                  sleep = { clock += it }, now = { clock },
                                  walker = { Walk(game, cap, { log += it }, { clock += it }, { clock }, { on }) })

        /** A token taken on the main screen: the round's word that starts a tour. */
        fun tokenTaken() {
            val bubble = PaintPassive.mainScreen(bubble = true)
            try {
                passive.work(bubble)
            } finally {
                bubble.release()
            }
            assertEquals(PassiveSkill.DID_BOND, passive.seen.did, "the figure was not tapped: $log")
            val t = clock
            while (passive.seen.did != PassiveSkill.DID_BOND_GONE) {
                assertTrue(clock < t + 30, "the token never counted: $log")
                clock += Director.BEAT_WATCH
                val img = PaintPassive.mainScreen()
                try {
                    passive.work(img)
                } finally {
                    img.release()
                }
            }
        }

        /** One turn of the bond tour's round on the main screen, as the director gives it. */
        fun turn(): Outcome {
            assertEquals(S.MAIN, game.s, "a turn is given on the main screen")
            val img = game.grab()
            try {
                return skill.work(img)
            } finally {
                img.release()
            }
        }

        /**
         * The director's turn on a screen a round names as its own
         * ([Skill.opened], DirectorLoop's `ownWindow`): the frame goes to
         * the round's `work`.
         */
        fun ownTurn(): Outcome {
            val screen = when (game.s) {
                S.PROMPT, S.TITLE_PROMPT -> Director.PROMPT
                S.PARTNER, S.SELECTED -> Director.PARTNER_PAGE
                else -> game.s.name
            }
            assertTrue(skill.opened(screen), "the tour does not name the $screen as its own -- ${broke()}")
            val img = game.grab()
            try {
                return skill.work(img)
            } finally {
                img.release()
            }
        }

        /** The line a broken tour leaves, for a failure's message. */
        fun broke(): String = log.firstOrNull { it.contains("stopped with") }
            ?: log.takeLast(12).joinToString("\n")

        /** The Digimon the last turn visited, as the Bond token card counts them. */
        fun visited(): Int = skill.lastCounts["visited"] ?: 0
    }

    // ------------------------------------------------------------------
    // A screen without a nav bar after the token
    // ------------------------------------------------------------------
    /**
     * After the third Raise the token's wait meets a screen without a nav
     * bar that the back key does not close -- a window under the player's
     * hand -- and it outlasts the tour. The next turn on the main screen,
     * when it has gone, goes on with the tour, with its line: the two
     * Digimon it had not reached, the one it began with last. The visits of
     * the two turns add up to the tour's five; the turn after that does
     * nothing.
     */
    @Test
    fun `a tour that cannot get home after the third token goes on to the end on the next turn`() {
        val w = World()
        var once = true
        w.game.cameHome = { n ->
            if (n == 3 && once) {
                once = false
                w.game.then(3, S.NO_NAV)
            }
        }
        w.tokenTaken()
        w.turn()
        assertEquals(listOf(2, 3, 4), w.game.raises, "the tour's three Raises: ${w.log}")
        assertEquals(3, w.visited())
        assertTrue(w.log.any { it.contains("stopped with 4 raised instead of the Digimon you started with (1)") },
                   "the line names the Digimon the page last showed raised: ${w.broke()}")

        w.game.s = S.MAIN
        w.turn()
        assertEquals(listOf(2, 3, 4, 0, 1), w.game.raises,
                     "the second turn walks the Digimon the tour had not reached, the first last -- ${w.broke()}")
        assertEquals(1, w.game.raised, "the tour did not end on the Digimon it started with")
        assertTrue(w.log.any { it.contains("going on with the tour that broke off at Digimon 4: 2 left") },
                   "${w.log}")
        assertEquals(2, w.visited(), "the visits of the second turn")

        w.turn()
        assertEquals(5, w.game.raises.size, "the third turn raises nothing")
        assertEquals(0, w.visited())
        assertEquals(0, w.game.titlePrompts, "no back key on the main screen")
        assertEquals(0, w.game.gearWindows)
    }

    /**
     * The same with the app in front from the end of the third token on:
     * no frames, the back key withheld. The tour breaks off; the next turn
     * on the main screen, the game in front again, walks it to the end.
     */
    @Test
    fun `a tour that lost the game to the app goes on to the end when the game is back`() {
        val w = World()
        var once = true
        w.game.cameHome = { n ->
            if (n == 3 && once) {
                once = false
                w.game.then(2, S.APP)
            }
        }
        w.tokenTaken()
        w.turn()
        assertEquals(listOf(2, 3, 4), w.game.raises, "the tour's three Raises: ${w.log}")
        assertEquals(3, w.visited())

        w.game.s = S.MAIN
        w.turn()
        assertEquals(listOf(2, 3, 4, 0, 1), w.game.raises, "the rest of the tour -- ${w.broke()}")
        assertEquals(1, w.game.raised, "the tour did not end on the Digimon it started with")
        assertEquals(2, w.visited())

        w.turn()
        assertEquals(5, w.game.raises.size, "the third turn raises nothing")
        assertEquals(0, w.game.titlePrompts)
    }

    /**
     * The app in front between the third Raise and its OK, as on LDPlayer
     * instance 0 on 2026-10-03 at 13:16: the game comes back with the
     * tour's own "Raise <name>?" open. The prompt is the broken tour's to
     * answer, and it answers Cancel -- never OK -- and then goes on with the
     * tour from the Digimon of that prompt, raised with a Raise of its own.
     */
    @Test
    fun `a tour that lost the game between Raise and OK cancels its prompt and goes on to the end`() {
        val w = World()
        var once = true
        w.game.pressedRaise = { d ->
            if (d == 4 && once) {
                once = false
                w.game.then(0, S.APP)
            }
        }
        w.tokenTaken()
        w.turn()
        assertEquals(listOf(2, 3), w.game.raises, "the tour's two Raises before the third's prompt: ${w.log}")
        assertEquals(2, w.visited())

        w.game.s = S.PROMPT                      // the game back, the prompt still open
        w.ownTurn()
        assertEquals(1, w.game.cancels, "the prompt was not cancelled: ${w.log}")
        assertTrue(w.log.any { it.contains("Cancel, not OK") }, "${w.log}")
        assertEquals(listOf(2, 3, 4, 0, 1), w.game.raises, "the rest of the tour -- ${w.broke()}")
        assertEquals(1, w.game.raised, "the tour did not end on the Digimon it started with")
        assertEquals(3, w.visited())

        w.turn()
        assertEquals(5, w.game.raises.size, "the turn after it raises nothing")
        assertEquals(0, w.game.titleOks)
    }

    /**
     * "Return to the title screen?" wears the face of the tour's own Raise
     * prompt to the pixel, and its OK ends the session. Where it stands in
     * the place of the broken tour's prompt -- a back key on the main screen
     * raises it -- the tour's answer is Cancel all the same, and the tour
     * goes on from the main screen it leaves.
     */
    @Test
    fun `a title prompt in the place of the tour's own is cancelled, never answered with OK`() {
        val w = World()
        var once = true
        w.game.pressedRaise = { d ->
            if (d == 4 && once) {
                once = false
                w.game.then(0, S.APP)
            }
        }
        w.tokenTaken()
        w.turn()

        w.game.s = S.TITLE_PROMPT
        w.ownTurn()
        assertEquals(0, w.game.titleOks, "OK on \"Return to the title screen?\" -- ${w.log}")
        assertEquals(1, w.game.cancels)
        assertEquals(listOf(2, 3, 4, 0, 1), w.game.raises, "the rest of the tour -- ${w.broke()}")
        assertEquals(1, w.game.raised, "the tour did not end on the Digimon it started with")
    }

    /**
     * Only where the Digimon the tour left is still raised: a player who has
     * raised one since has chosen. The next turn opens the page, reads the
     * player's choice, says so, and raises nothing.
     */
    @Test
    fun `a Digimon raised by the player since the tour broke off stays raised`() {
        val w = World()
        var once = true
        w.game.cameHome = { n ->
            if (n == 3 && once) {
                once = false
                w.game.then(3, S.NO_NAV)
            }
        }
        w.tokenTaken()
        w.turn()
        assertEquals(listOf(2, 3, 4), w.game.raises, "the tour's three Raises: ${w.log}")

        w.game.s = S.MAIN
        w.game.raised = 0                       // the player's own hand
        w.turn()
        assertEquals(0, w.game.raised, "the player's choice was raised over: ${w.log}")
        assertEquals(3, w.game.raises.size, "no Raise on the second turn")
        assertTrue(w.log.any { it.contains("somebody chose it, and it stays") }, "${w.log}")

        w.turn()
        assertEquals(3, w.game.raises.size, "nor on the third")
        assertEquals(0, w.game.raised)
    }

    /**
     * The tour goes on only while the player still asks for all of the
     * Digimon. Where the box has been unticked since the break -- the app in
     * front is where it is unticked -- the next turn walks nothing and only
     * undoes the tour's own work, as A5d built it: the first Digimon raised
     * again, and nothing after it.
     */
    @Test
    fun `with the all-Digimon box unticked since, the next turn only raises the first Digimon again`() {
        val w = World()
        var once = true
        w.game.cameHome = { n ->
            if (n == 3 && once) {
                once = false
                w.game.then(2, S.APP)
            }
        }
        w.tokenTaken()
        w.turn()
        assertEquals(listOf(2, 3, 4), w.game.raises, "the tour's three Raises: ${w.log}")

        w.box = false
        w.game.s = S.MAIN
        w.turn()
        assertEquals(listOf(2, 3, 4, 1), w.game.raises, "one Raise, the first Digimon's -- ${w.broke()}")
        assertEquals(1, w.game.raised)
        assertTrue(w.log.any { it.contains("raised the Digimon you started with (1) again") }, "${w.log}")

        w.turn()
        assertEquals(4, w.game.raises.size, "the third turn raises nothing")
    }

    /**
     * The tour that goes on breaks off again -- the app in front after the
     * token of the first Digimon it raised -- and keeps its new place: the
     * turn after it raises only the one that is left, the first. Three
     * turns, every Digimon once.
     */
    @Test
    fun `a tour that breaks off again while it goes on keeps its new place`() {
        val w = World()
        var first = true
        var second = true
        w.game.cameHome = { n ->
            if (n == 3 && first) {
                first = false
                w.game.then(3, S.NO_NAV)
            } else if (n == 4 && second) {
                second = false
                w.game.then(2, S.APP)
            }
        }
        w.tokenTaken()
        w.turn()
        assertEquals(listOf(2, 3, 4), w.game.raises, "the tour's three Raises: ${w.log}")

        w.game.s = S.MAIN
        w.turn()
        assertEquals(listOf(2, 3, 4, 0), w.game.raises, "the second turn's one Raise before the app: ${w.log}")
        assertEquals(1, w.visited())
        assertTrue(w.log.any { it.contains("stopped with 0 raised instead of the Digimon you started with (1)") },
                   "${w.log}")

        w.game.s = S.MAIN
        w.turn()
        assertTrue(w.log.any { it.contains("going on with the tour that broke off at Digimon 0: 1 left") },
                   "${w.log}")
        assertEquals(listOf(2, 3, 4, 0, 1), w.game.raises, "every Digimon once, the first last")
        assertEquals(1, w.game.raised)
        assertEquals(1, w.visited())

        w.turn()
        assertEquals(5, w.game.raises.size, "the fourth turn raises nothing")
    }

    /**
     * A tour the main switch stopped goes on as it did before A5h: from the
     * main screen after the pause, its own Raise counted once, the Digimon
     * that are left, the first last -- and no line of a broken tour.
     */
    @Test
    fun `a tour the main switch stopped goes on as before`() {
        val w = World()
        var once = true
        w.game.cameHome = { n ->
            if (n == 3 && once) {
                once = false
                w.on = false
            }
        }
        w.tokenTaken()
        assertEquals(Outcome.STOPPED, w.turn(), "${w.log}")
        assertEquals(listOf(2, 3, 4), w.game.raises)

        w.on = true
        w.game.s = S.MAIN
        val img = w.game.grab()
        try {
            assertTrue(w.skill.resumesOn(Director.MAIN, img))
            w.skill.resume(img, false)
        } finally {
            img.release()
        }
        assertEquals(listOf(2, 3, 4, 0, 1), w.game.raises, "the rest of the tour: ${w.log}")
        assertEquals(1, w.game.raised)
        assertEquals(2, w.visited(), "the stopped Raise is not counted again")
        assertTrue(w.log.any { it.contains("going on with the tour the main switch stopped, 3 Digimon left") },
                   "${w.log}")
        assertTrue(w.log.none { it.contains("broke off") }, "${w.log}")
    }

    /**
     * A tour that goes on and comes no further -- the Digimon page will not
     * open -- is tried on three turns and then forgotten, with a line that
     * says what may still be raised; the turn after that touches nothing.
     */
    @Test
    fun `a tour that cannot go on is given up after three turns`() {
        val w = World()
        var once = true
        w.game.cameHome = { n ->
            if (n == 3 && once) {
                once = false
                w.game.then(2, S.APP)
            }
        }
        w.tokenTaken()
        w.turn()
        assertEquals(listOf(2, 3, 4), w.game.raises, "the tour's three Raises: ${w.log}")

        w.game.tabDead = true
        repeat(BondTourSkill.RESTORE_TRIES) {
            w.game.s = S.MAIN
            w.turn()
        }
        assertTrue(w.log.any { it.contains("gave up going on with the tour that broke off after 3 tries -- " +
                                           "4 may still be raised") }, "${w.log}")

        w.game.tabDead = false
        val before = w.game.grabs
        w.turn()
        assertEquals(3, w.game.raises.size, "nothing raised after the tour was given up")
        assertEquals(S.MAIN, w.game.s, "the page was not opened: ${w.log.takeLast(6)}")
        assertTrue(w.game.grabs - before <= 1, "the turn looked no further than its frame")
    }

    // ------------------------------------------------------------------
    // The main screen is not the Digimon page
    // ------------------------------------------------------------------
    /**
     * The Poco's minute at 18:32, as far as it can be played: after the
     * third token the nav bar is gone for a while and comes back by itself,
     * the Digimon page comes two frames after its tab's tap, and the HUD
     * under the main screen reads as a sub-tab row. A check the main screen
     * passed called it the page, said "already on the Partner tab" and
     * tapped the HUD, which opened the gear window: "no sub-tab row" twice,
     * three back keys, "stopped with 3 raised". Now the page has to be the
     * page -- the main screen gone -- and the tour waits for it and goes on
     * to the end, the player's own raised last, nothing tapped on the HUD.
     */
    @Test
    fun `the main screen is never taken for the Digimon page, and the tour goes on to the end`() {
        val w = World()
        var once = true
        w.game.cameHome = { n ->
            if (n == 3 && once) {
                once = false
                w.game.then(3, S.NO_NAV)
                w.game.then(8, S.MAIN)
                w.game.pageLate = 2
                w.game.hudReadsAsSubtab = true
            }
        }
        w.tokenTaken()
        w.turn()
        w.turn()
        assertEquals(1, w.game.raised, "the tour did not end on the Digimon it started with -- ${w.broke()}")
        assertEquals(0, w.game.gearWindows, "a tap went into the HUD under the main screen: ${w.log}")
        assertEquals(listOf(2, 3, 4, 0, 1), w.game.raises, "every Digimon once, the player's own last")
        assertEquals(0, w.game.titlePrompts)

        w.turn()
        assertEquals(5, w.game.raises.size, "the turns after it raise nothing")
    }
}
