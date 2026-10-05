package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chef's Special offline, no emulator needed (PLAN_SKEWER.md SK2, 4.2 point
 * 4): the world is **painted** (PaintSkewer) and read by the real Skewer.kt,
 * and it reacts to the taps it is sent the way the game does -- an
 * ingredient goes on the plate, Complete serves the plate to the guest and
 * costs a life where it is not the order, the next guest walks in, a round
 * ends after its minute or at the last life, and the pause menu's Quit goes
 * back to the stage popup. Every grab costs what the phone's costs, so the
 * loop runs at the pace it will really have.
 *
 * What the world counts is what the event's mission counts, the game's own
 * "N Combo": a guest served right after a guest served right in the same
 * round. The task counts for itself, and the two are held to each other.
 */
class SkewerSkillTest {

    init {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    }

    // ------------------------------------------------------------------------
    // The painted frames, against the real readers
    // ------------------------------------------------------------------------
    private val icons = PaintSkewer.icons

    @Test
    fun `the painted frames read as what they are painted as`() {
        val menu = PaintSkewer.menu()
        val play = Skewer.menu(menu)
        assertNotNull(play, "the menu's Play Game")
        assertTrue(PaintSkewer.inside(play.fx, play.fy, Skewer.PENNANT_BOX), "Play Game on its pennant: $play")
        assertNull(Skewer.stage(menu))
        assertEquals(Director.SKEWER_MENU, Director.classify(menu).screen, "${Director.classify(menu)}")
        menu.release()

        val stage = PaintSkewer.stage()
        val start = Skewer.stage(stage)
        assertNotNull(start, "the stage popup's Start")
        assertTrue(PaintSkewer.inside(start.fx, start.fy, Skewer.START_BOX))
        assertNull(Skewer.menu(stage), "the menu is dimmed under the popup")
        assertEquals(Director.SKEWER_STAGE, Director.classify(stage).screen, "${Director.classify(stage)}")
        val beside = SkewerSkill.stageOutside(stage)
        assertTrue(beside.fx < PaintSkewer.POPUP_BOX[0] && beside.fy in PaintSkewer.POPUP_BOX[1]..PaintSkewer.POPUP_BOX[3],
                   "beside the popup, left of it: $beside")
        stage.release()

        val orders = listOf(listOf("corn", "mushroom"), listOf("corn", "mushroom", "mushroom"),
                            listOf("potato", "octopus", "dark_meat", "shrimp"),
                            listOf("onion", "tomato", "leaf_wrap", "raw_meat", "asparagus"))
        for ((order, lives) in orders.map { it to 3 } + listOf(orders[1] to 2, orders[3] to 1)) {
            val img = PaintSkewer.play(order = order, lives = lives)
            assertTrue(Skewer.grill(img).ok, "the grill: ${Skewer.grill(img)}")
            assertTrue(Skewer.playing(img))
            assertNull(Skewer.over(img)); assertNull(Skewer.paused(img))
            assertEquals(SkewerIcons.NAMES, icons.grid(img).map { it.name }, "the twelve, row by row")
            assertEquals(order, icons.order(img)?.names, "the order of ${order.size}: ${icons.order(img)}")
            assertEquals(lives, icons.lives(img)?.n, "the lives: ${icons.lives(img)}")
            assertEquals(Director.SKEWER_PLAY, Director.classify(img).screen)
            img.release()
        }
        val gap = PaintSkewer.play(order = null)
        assertNull(icons.order(gap), "no bubble, no order")
        assertTrue(Skewer.playing(gap))
        gap.release()

        val over = PaintSkewer.over(lives = 0)
        assertEquals(Runner.QUIT_RESULT, Skewer.over(over), "Close where the run's Quit stands")
        assertNull(Skewer.paused(over))
        assertEquals(0, icons.lives(over)?.n, "the lives under the dialog")
        assertEquals(Director.SKEWER_OVER, Director.classify(over).screen)
        over.release()
        val paused = PaintSkewer.paused()
        assertEquals(Runner.QUIT_PAUSE, Skewer.paused(paused))
        assertNull(Skewer.over(paused))
        assertEquals(Director.SKEWER_PAUSE, Director.classify(paused).screen)
        paused.release()

        val cards = PaintRunner.eventsDialog(Events.CHEFS_SPECIAL)
        assertNotNull(Events.card(cards, Events.CHEFS_SPECIAL))
        assertNull(Events.card(cards, Events.GEKKOMON_RUN))
        cards.release()
    }

    // ------------------------------------------------------------------------
    // The one place a guest is judged
    // ------------------------------------------------------------------------
    @Test
    fun `a guest is judged by the frame, then by the lives three seconds on`() {
        val playing = SkewerSkill.Look(true, order = listOf("corn"), lives = 3)
        val served = SkewerSkill.Look(true, order = null, lives = 3)
        fun judge(look: SkewerSkill.Look, gap: Boolean, since: Double) =
            SkewerSkill.judge(look, SkewerSkill.Judging(3, gap, since))
        assertEquals(SkewerIcons.Served.WRONG, judge(playing.copy(lives = 2), false, 0.4), "a life less")
        assertEquals(SkewerIcons.Served.WRONG, judge(served.copy(served = SkewerIcons.Served.WRONG), true, 0.4))
        assertEquals(SkewerIcons.Served.RIGHT, judge(served.copy(served = SkewerIcons.Served.RIGHT), true, 0.4),
                     "the pink N Combo says so at once")
        assertEquals(SkewerIcons.Served.UNCLEAR, judge(served, true, 1.0), "not yet: a life can still go")
        assertEquals(SkewerIcons.Served.RIGHT, judge(served, true, SkewerSkill.JUDGE_SETTLE))
        assertEquals(SkewerIcons.Served.RIGHT, judge(playing, true, SkewerSkill.JUDGE_SETTLE),
                     "the next guest's order standing, the life kept")
        assertEquals(SkewerIcons.Served.UNCLEAR, judge(playing, false, 5.0),
                     "the bubble never went: Complete did not take")
        assertEquals(SkewerIcons.Served.UNCLEAR, judge(served.copy(lives = null), true, 5.0), "no lives read")
        val over = SkewerSkill.Look(true, over = Runner.QUIT_RESULT, lives = 3)
        assertEquals(SkewerIcons.Served.UNCLEAR, judge(over, true, 5.0), "the round ended on it")
        assertEquals(SkewerIcons.Served.WRONG, judge(over.copy(lives = 2), true, 1.0), "and a life lost reads there")
        // On a painted frame: the real reader under the rule.
        val img = PaintSkewer.play(order = null, lives = 2)
        assertEquals(SkewerIcons.Served.WRONG, icons.served(img, 3))
        assertEquals(SkewerIcons.Served.UNCLEAR, icons.served(img, 2), "no pink text painted")
        img.release()
    }

    // ------------------------------------------------------------------------
    // The world: a game that reacts, at the phone's pace
    // ------------------------------------------------------------------------
    /**
     * The game, as far as this task can see it: painted at [w] x [h] with
     * [headroom] rows over the canvas (PaintSkewer paints through each
     * anchor's rectangle), and every tap read back by the fraction it landed
     * on, in the rectangle the thing it hit is painted in.
     */
    class World(
        val w: Int = Paint.W, val h: Int = Paint.H, val headroom: Int = 0,
        /** Every guest's order, in turn, over the rounds; the list runs round when it ends. */
        val orders: List<List<String>> = ORDERS,
        /** How long a round lasts after its countdown. */
        val roundSeconds: Double = 60.0,
        /** The one card in the Events window (Events.GEKKOMON_RUN, CHEFS_SPECIAL, UNKNOWN). */
        var card: String = Events.CHEFS_SPECIAL,
    ) : Capture {
        var t = 100.0
        var screen = "menu"
        /** The next looks at the main screen that find the hologram device lit ([Paint.holoLight]). */
        var lit = 0
        val taps = ArrayList<String>()
        /** What was under each tap in the frame the game showed: the tap's name and the HSV there. */
        val under = ArrayList<Pair<String, IntArray?>>()
        val log = ArrayList<String>()
        var grabs = 0

        // The round.
        var rounds = 0
        var lives = 3
        var roundAt = 0.0
        var pausedAt = 0.0
        var guests = 0
        var order: List<String>? = null
        var nextAt = Double.MAX_VALUE
        var lingering: List<String>? = null
        var lingerUntil = 0.0
        val plate = ArrayList<String>()
        var streak = 0
        /** What the event's mission counts: the game's own "N Combo", over the rounds. */
        var combos = 0
        var right = 0
        var wrong = 0
        /** Ingredient taps the game does not take, by their number over the world (1-based). */
        val swallow = HashSet<Int>()
        var ingredientTaps = 0
        /** Guest number (1-based) -> after this many ingredients on the plate, the order becomes this. */
        val change = HashMap<Int, Pair<Int, List<String>>>()
        private var last: Mat? = null

        companion object {
            const val PACE = 0.35
            const val COUNTDOWN = 3.0
            /** A new guest's order shows this long after the last one left. */
            const val WALK = 1.0
            /** A wrong guest's bubble stays this long while it leaves angry. */
            const val LINGER = 1.0

            /** Two to five ingredients, never the same order twice in a row. */
            val ORDERS = listOf(
                listOf("corn", "mushroom"), listOf("shrimp", "onion", "tomato"),
                listOf("potato", "octopus", "dark_meat", "shrimp"), listOf("raw_meat", "raw_meat"),
                listOf("onion", "tomato", "leaf_wrap", "raw_meat", "asparagus"), listOf("dumpling", "corn", "octopus"))
        }

        private val proto = PaintSkewer.frame(w, h, headroom)
        private val bottom = Dungeon.gameRect(proto, Dungeon.Anchor.BOTTOM)
        private val middle = Dungeon.gameRect(proto, Dungeon.Anchor.MIDDLE)
        private val top = Dungeon.gameRect(proto, Dungeon.Anchor.TOP)

        fun sleep(s: Double) { t += s }

        /** The clock's own changes: the round's end, the next guest, a wrong guest leaving. */
        private fun advance() {
            if (screen != "play") return
            if (t >= roundAt + COUNTDOWN + roundSeconds) {
                screen = "over"
                order = null
                log += "the round ended by its time: $right right, $wrong wrong, $combos combos"
                return
            }
            if (lingering != null && t >= lingerUntil) lingering = null
            if (order == null && t >= nextAt) {
                order = orders[guests % orders.size]
                guests += 1
                nextAt = Double.MAX_VALUE
            }
        }

        override fun grab(): Mat {
            grabs += 1
            t += PACE
            advance()
            val img = PaintSkewer.frame(w, h, headroom)
            when (screen) {
                "main" -> {
                    img.release()
                    val main = PaintRunner.mainScreen()
                    if (lit == 0) return main.also { keepLast(it) }
                    lit -= 1
                    return Paint.holoLight(main).also { main.release(); keepLast(it) }
                }
                "events" -> { img.release(); return PaintRunner.eventsDialog(card).also { keepLast(it) } }
                "menu" -> PaintSkewer.menu(img)
                "stage" -> PaintSkewer.stage(img)
                "play" -> PaintSkewer.play(img, order = lingering ?: order, lives = lives)
                "pause" -> PaintSkewer.paused(img, lives)
                "over" -> PaintSkewer.over(img, lives)
                else -> throw IllegalStateException(screen)
            }
            keepLast(img)
            return img
        }

        private fun keepLast(img: Mat) {
            last?.release()
            last = img.clone()
        }

        private fun complete() {
            val o = order
            if (o == null) {
                taps += "Complete (no guest)"
                return
            }
            if (plate == o) {
                right += 1
                streak += 1
                if (streak >= 2) combos += 1
                order = null
                nextAt = t + WALK
                log += "served right: $o" + (if (streak >= 2) " (${streak - 1} Combo)" else "")
            } else {
                wrong += 1
                streak = 0
                lives -= 1
                lingering = o
                lingerUntil = t + LINGER
                order = null
                nextAt = t + LINGER + WALK
                log += "served wrong: $plate for $o"
                if (lives <= 0) {
                    screen = "over"
                    lingering = null
                    log += "the round ended at the last life"
                }
            }
            plate.clear()
        }

        override fun tap(x: Int, y: Int) {
            advance()
            val fx = (x - bottom.x0) / bottom.gw.toDouble()
            val fy = (y - bottom.y0) / bottom.gh.toDouble()
            val mfy = (y - middle.y0) / middle.gh.toDouble()
            // Each screen's parts where Skewer measured them: the round at the
            // top, the menu's pennant and the stage popup in the middle, the
            // X at the bottom.
            val tfy = (y - top.y0) / top.gh.toDouble()
            val hsv = last?.let { PaintSkewer.hsvAt(it, x, y) }
            fun hit(name: String) { taps += name; under += name to hsv }
            when (screen) {
                "main" -> if (PaintSkewer.inside(fx, fy, PaintRunner.EVENTS_TILE)) {
                    hit("Events icon"); screen = "events"
                } else hit("main %.3f/%.3f".format(fx, fy))
                "events" -> when {
                    PaintSkewer.inside(fx, fy, PaintRunner.EVENT_CARD) -> {
                        hit(if (card == Events.CHEFS_SPECIAL) "card" else "card $card")
                        if (card == Events.CHEFS_SPECIAL) screen = "menu"
                    }
                    fx < 0.156 -> { hit("beside the Events window"); screen = "main" }
                    else -> hit("events %.3f/%.3f".format(fx, fy))
                }
                "menu" -> when {
                    PaintSkewer.inside(fx, mfy, Skewer.PENNANT_BOX) -> { hit("Play Game"); screen = "stage" }
                    kotlin.math.abs(fx - Summon.EXIT_BUTTON_FX) < 0.04 && kotlin.math.abs(fy - Summon.EXIT_BUTTON_FY) < 0.03 -> {
                        hit("X"); screen = "main"
                    }
                    else -> hit("menu %.3f/%.3f".format(fx, fy))
                }
                "stage" -> when {
                    PaintSkewer.inside(fx, mfy, Skewer.START_BOX) -> {
                        hit("Start")
                        screen = "play"; rounds += 1; roundAt = t; lives = 3; streak = 0
                        order = null; lingering = null; plate.clear(); nextAt = t + COUNTDOWN
                    }
                    fx < PaintSkewer.POPUP_BOX[0] && mfy in PaintSkewer.POPUP_BOX[1]..PaintSkewer.POPUP_BOX[3] -> {
                        hit("beside the stage popup"); screen = "menu"
                    }
                    else -> hit("stage %.3f/%.3f".format(fx, fy))
                }
                "play" -> {
                    val cell = (0 until 12).firstOrNull { PaintSkewer.inside(fx, tfy, PaintSkewer.cellBox(it)) }
                    when {
                        cell != null -> {
                            val name = SkewerIcons.NAMES[cell]
                            hit(name)
                            ingredientTaps += 1
                            if (ingredientTaps in swallow) {
                                log += "tap $ingredientTaps on $name not taken"
                            } else {
                                plate += name
                                val c = change[guests]
                                if (c != null && plate.size == c.first && order != null) {
                                    log += "guest $guests changed the order: $order -> ${c.second}"
                                    order = c.second
                                    change.remove(guests)
                                }
                            }
                        }
                        PaintSkewer.inside(fx, tfy, PaintSkewer.COMPLETE_BOX) -> { hit("Complete"); complete() }
                        PaintSkewer.inside(fx, tfy, PaintSkewer.PAUSE_BOX) -> { hit("pause"); screen = "pause"; pausedAt = t }
                        else -> hit("play %.3f/%.3f".format(fx, tfy))
                    }
                }
                "pause" -> when {
                    PaintSkewer.inside(fx, mfy, PaintSkewer.QUIT) -> { hit("Quit"); screen = "stage" }
                    PaintSkewer.inside(fx, mfy, PaintSkewer.CONTINUE_BOX) -> {
                        hit("Continue"); screen = "play"; roundAt += t - pausedAt
                    }
                    else -> hit("pause %.3f/%.3f".format(fx, mfy))
                }
                "over" -> if (PaintSkewer.inside(fx, mfy, PaintSkewer.CLOSE)) {
                    hit("Close"); screen = "stage"
                } else hit("over %.3f/%.3f".format(fx, mfy))
            }
        }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun back() { taps += "back" }
        override fun inFront(): String? = "game"
    }

    /** One skill over a world, with the world's clock and a day's count that outlives the pass. */
    class Rig(val world: World, combos: Int = SkewerSkill.COMBO_TARGET, rounds: Int = SkewerSkill.ROUNDS_MAX) {
        val log = ArrayList<String>()
        var on = true
        val kept = ArrayList<String>()
        /** The day's combos, as the app's `Stored.addSkewerCombo` writes them. */
        var day = 0
        var wanted = combos
        val skill = SkewerSkill(world, PaintSkewer.icons, { SkewerSkill.Settings(wanted, day) },
                                log = { log += it }, on = { on }, keep = { _, tag -> kept += tag },
                                comboCounted = { day += 1 }, roundsMax = rounds,
                                sleep = { world.sleep(it) }, now = { world.t })
        fun ingredientTaps() = world.taps.filter { it in SkewerIcons.NAMES }
    }

    // ------------------------------------------------------------------------
    // One guest, and the round
    // ------------------------------------------------------------------------
    @Test
    fun `one order is read on two frames, built on the grill, completed and judged right`() {
        val world = World(orders = listOf(listOf("corn", "mushroom"), listOf("potato", "octopus")))
            .apply { screen = "stage" }
        val rig = Rig(world, combos = 1)
        val outcome = rig.skill.work(PaintSkewer.stage())
        assertEquals(Result.DONE, outcome.result, "${rig.log} / ${world.taps}")
        // Start, the first guest's two, Complete, the second's two, Complete;
        // the third guest's two go out while the second is judged on their
        // frames, its Complete waits for that verdict, and the verdict is the
        // combo: the way out of the round and the popup, the menu left open.
        assertEquals(listOf("Start", "corn", "mushroom", "Complete", "potato", "octopus", "Complete",
                            "corn", "mushroom", "pause", "Quit", "beside the stage popup"), world.taps,
                     rig.log.toString())
        assertEquals(2, world.right)
        assertEquals(1, world.combos, "the game's combo: the second right guest")
        assertEquals(1, rig.day, "counted for the day the moment it happened")
        assertEquals("menu", world.screen, "work hands the menu back")
        assertEquals(mapOf("rounds" to 1, "combos" to 1, "best" to 1), rig.skill.lastCounts)
        assertTrue(rig.log.any { it.contains("right -- 1 Combo") }, rig.log.toString())
    }

    @Test
    fun `sixteen combos over two rounds, and the round with the last one is left through its pause menu`() {
        val world = World().apply { screen = "menu" }
        val rig = Rig(world)
        val outcome = rig.skill.work(PaintSkewer.menu())
        assertEquals(Result.DONE, outcome.result, "${rig.log} / ${world.log}")
        assertEquals(2, world.rounds, "one round is not enough for sixteen: ${world.log}")
        assertEquals(16, rig.day, "sixteen counted for the day")
        // The task counts what the game counts, less the guest served in the
        // last seconds of a round that ran out: nobody could judge it.
        assertTrue(world.combos - rig.day in 0..world.rounds - 1,
                   "the task counts what the game counts (${world.combos} against ${rig.day}): ${world.log}")
        assertEquals(0, world.wrong, world.log.toString())
        // The first round ran out and was closed; the second was left at the
        // sixteenth through its pause menu (the player's answer to question 9).
        val close = world.taps.indexOf("Close")
        assertTrue(close > 0 && world.taps[close + 1] == "Start", "Close, then Start again: ${world.taps}")
        // The next guest may be half built when the verdict that is the
        // sixteenth comes: it is left on the plate, never completed.
        assertEquals(listOf("pause", "Quit", "beside the stage popup"), world.taps.takeLast(3))
        assertEquals("menu", world.screen)
        assertEquals(16, rig.skill.lastCounts["combos"])
        assertEquals(2, rig.skill.lastCounts["rounds"])
        assertTrue(rig.skill.lastCounts.getValue("best") >= 8, "${rig.skill.lastCounts}")
        // A pass that has its combos hands the question back to the director.
        assertNull(rig.skill.seesWork(Director.SKEWER_MENU, PaintSkewer.menu()))
        assertTrue(!rig.skill.hasBudget(), "the day has its combos")
        assertTrue(rig.skill.noBudgetWhy()!!.startsWith("16 of 16 combos counted today"), rig.skill.noBudgetWhy())
    }

    @Test
    fun `a guest is judged on the next guest's frames, so a round serves twelve and more`() {
        // The player's measure (PLAN_BEFUNDE_1_3.md 0.4): twelve orders a
        // round at least. At the phone's pace of a frame (World.PACE) and the
        // game's walk-in (World.WALK), a round of orders of two to five
        // ingredients; before, every guest waited JUDGE_SETTLE on frames of
        // its own, and the next order was not even read in that time.
        val world = World().apply { screen = "stage" }
        val rig = Rig(world, combos = 99, rounds = 1)
        rig.skill.work(PaintSkewer.stage())
        val end = world.log.first { it.startsWith("the round ended by its time") }
        assertTrue(world.right >= 12, "$end / ${rig.log.filter { "guest" in it || "judged" in it }}")
        assertEquals(0, world.wrong, world.log.toString())
        assertTrue(world.taps.none { it == "Complete (no guest)" }, world.taps.toString())
    }

    @Test
    fun `a mistake costs the running combo and nothing that is counted`() {
        // The third ingredient tap is not taken: the second guest's skewer
        // goes out short, a life is lost, and the combo starts again. The
        // guests: right, wrong, right, right, right -> combos 0, -, 0, 1, 2.
        val world = World().apply { screen = "stage"; swallow += 3 }
        val rig = Rig(world, combos = 2)
        val outcome = rig.skill.work(PaintSkewer.stage())
        assertEquals(Result.DONE, outcome.result, "${rig.log} / ${world.log}")
        assertEquals(1, world.wrong, world.log.toString())
        assertEquals(4, world.right, world.log.toString())
        assertEquals(2, world.combos)
        assertEquals(2, rig.day, "the task counted what the game counted, no more")
        assertTrue(rig.log.any { it.contains("wrong -- a life lost") }, rig.log.toString())
        assertEquals(1, rig.skill.lastSession!!.mistakes)
        assertEquals(2, rig.skill.lastSession!!.best)
    }

    @Test
    fun `an order that changes under the hand is taken up where it can be, and served to clear it where not`() {
        // The first guest's order becomes one whose start is the plate so
        // far: built on. The second's becomes one that does not begin with
        // what is on the plate: the plate is served as it stands, a life.
        val world = World(orders = listOf(listOf("corn", "mushroom"), listOf("shrimp", "onion", "tomato"),
                                          listOf("potato", "octopus"), listOf("raw_meat", "corn")))
            .apply {
                screen = "stage"
                change[1] = 1 to listOf("corn", "tomato", "onion")
                change[2] = 1 to listOf("dumpling", "corn")
            }
        val rig = Rig(world, combos = 1)
        val outcome = rig.skill.work(PaintSkewer.stage())
        assertEquals(Result.DONE, outcome.result, "${rig.log} / ${world.log}")
        assertEquals(listOf("corn", "tomato", "onion"), rig.ingredientTaps().take(3),
                     "the first guest's new order, the corn already on: ${world.taps}")
        assertTrue(rig.log.any { it.contains("the plate so far is its start, going on") }, rig.log.toString())
        assertTrue(rig.log.any { it.contains("serving the plate as it is to clear it") }, rig.log.toString())
        assertEquals("shrimp", rig.ingredientTaps()[3], "one on the second guest's plate, then Complete")
        assertEquals("Complete", world.taps[world.taps.indexOf("shrimp") + 1], world.taps.toString())
        assertEquals(1, world.wrong)
        assertTrue(rig.kept.contains("skewer_order_changed"), rig.kept.toString())
        assertEquals(world.combos, rig.day)
    }

    @Test
    fun `the last life lost ends the round, and the next one starts on the same stage`() {
        // Every third ingredient tap is not taken: three mistakes in a row end
        // the first round on "Failed...", which is closed and started again.
        val world = World(orders = listOf(listOf("corn", "mushroom", "onion"))).apply {
            screen = "stage"; swallow += listOf(3, 6, 9)
        }
        val rig = Rig(world, combos = 1)
        val outcome = rig.skill.work(PaintSkewer.stage())
        assertEquals(Result.DONE, outcome.result, "${rig.log} / ${world.log}")
        assertTrue(world.log.any { it == "the round ended at the last life" }, world.log.toString())
        assertEquals(2, world.rounds)
        assertEquals(listOf("Close", "Start"), world.taps.subList(world.taps.indexOf("Close"), world.taps.indexOf("Close") + 2))
        assertEquals(3, rig.skill.lastSession!!.mistakes)
        assertEquals(1, rig.day)
    }

    // ------------------------------------------------------------------------
    // The way in and out
    // ------------------------------------------------------------------------
    @Test
    fun `run walks from the main screen through the Chef's Special card and comes home`() {
        val world = World().apply { screen = "main" }
        val rig = Rig(world, combos = 1)
        val outcome = rig.skill.run()
        assertEquals(Result.DONE, outcome.result, "${rig.log} / ${world.taps}")
        assertEquals(listOf("Events icon", "card", "Play Game", "Start"), world.taps.take(4))
        assertEquals(listOf("pause", "Quit", "beside the stage popup", "X"), world.taps.takeLast(4))
        assertEquals("main", world.screen)
        assertEquals(1, rig.day)
    }

    @Test
    fun `a main screen lit for a moment is waited out on the way in, twice running`() {
        // K2 of PLAN_ABSCHLUSS_1_3.md: one look at the auto button, and the
        // hologram device's light over it said "chef's special: not the plain
        // main screen, cannot open the event" (MainScreen).
        val world = World().apply { screen = "main" }
        val rig = Rig(world)
        for (way in 1..2) {
            world.screen = "main"
            world.lit = way
            val before = world.taps.size
            assertTrue(rig.skill.goToMenu(), "way $way: ${rig.log} / ${world.taps}")
            assertEquals(listOf("Events icon", "card"), world.taps.drop(before), "way $way")
            assertEquals("menu", world.screen)
            assertEquals(0, world.lit, "way $way looked at every lit frame")
        }
        assertEquals(2, rig.log.count { it.startsWith("  the plain main screen after ") }, rig.log.toString())
        assertTrue(rig.log.none { it.contains("cannot open") }, rig.log.toString())
    }

    /**
     * Gekkomon Run in the Events window, or a card nobody has measured, and
     * no Chef's Special: no card is tapped, the log says why, the window is
     * closed beside it, and the pass is RETIRED -- the chain's "not in this
     * run" (PLAN_SKEWER.md 3.2).
     */
    @Test
    fun `an Events window without Chef's Special is left without a tap on the other card`() {
        for (other in listOf(Events.GEKKOMON_RUN, Events.UNKNOWN)) {
            val world = World(card = other).apply { screen = "main" }
            val rig = Rig(world)
            val outcome = rig.skill.run()
            assertEquals(Result.RETIRED, outcome.result, "$outcome / ${rig.log} / ${world.taps}")
            assertEquals(SkewerSkill.NOT_IN_WINDOW_WHY, outcome.why)
            assertEquals(listOf("Events icon", "beside the Events window"), world.taps)
            assertEquals("main", world.screen)
            assertTrue(rig.log.any { it.startsWith("Chef's Special is not in the Events window (cards: $other)") },
                       rig.log.toString())
            assertEquals(0, world.rounds)
        }
    }

    @Test
    fun `the main switch off in the middle of a round leaves the game where it stands`() {
        val world = World().apply { screen = "stage" }
        val rig = Rig(world)
        var frames = 0
        val counting = object : Capture by world {
            override fun grab(): Mat { frames += 1; return world.grab() }
        }
        val skill = SkewerSkill(counting, PaintSkewer.icons, { SkewerSkill.Settings() }, log = { rig.log += it },
                                on = { frames < 14 }, sleep = { world.sleep(it) }, now = { world.t })
        val outcome = skill.work(PaintSkewer.stage())
        assertEquals(Result.STOPPED, outcome.result, "${rig.log} / ${world.taps}")
        val tapsAtStop = world.taps.toList()
        assertEquals("play", world.screen, "no pause, no Quit with the switch off: ${world.taps}")
        assertTrue("pause" !in world.taps, world.taps.toString())
        // The round runs out by itself; its result dialog is work again,
        // because the pass that was cut short is still owed.
        val over = PaintSkewer.over()
        assertEquals(true, skill.seesWork(Director.SKEWER_OVER, over))
        assertEquals(false, skill.seesWork(Director.SKEWER_PLAY, over), "a round in play is never taken over")
        assertEquals(false, skill.seesWork(Director.SKEWER_PAUSE, over))
        over.release()
        assertEquals(tapsAtStop, world.taps)
    }

    /**
     * Two pauses in one pass (PLAN_RELEASE_1_3.md B4, the pattern of
     * RunnerSkillTest's "paused twice"): the day wants three combos. The
     * switch goes off at the first combo counted -- the pass stops between two
     * looks, nothing more is tapped and the round goes on by itself; back on,
     * the pass goes on in the round it stands in, and the switch goes off
     * again at the second combo; back on once more, the pass plays for the one
     * combo the day still owes and ends on the menu. Three in all, counted as
     * they came -- not the day's three for each pass that went on.
     */
    @Test
    fun `Chef's Special paused twice plays what the day still owes and nothing twice`() {
        val world = World().apply { screen = "stage" }
        val log = ArrayList<String>()
        var day = 0
        var offAt: Int? = 1
        val on = { val o = offAt; !(o != null && day >= o) }
        val skill = SkewerSkill(world, PaintSkewer.icons, { SkewerSkill.Settings(3, day) }, log = { log += it },
                                on = on, comboCounted = { day += 1 }, sleep = { world.sleep(it) }, now = { world.t })
        val first = skill.work(PaintSkewer.stage())
        assertEquals(Result.STOPPED, first.result, log.joinToString("\n"))
        assertEquals(1, day)
        assertEquals("play", world.screen, "the round goes on by itself")
        val tapsAtStop = world.taps.toList()
        assertTrue("pause" !in tapsAtStop, "nothing raised on the way out with the switch off: $tapsAtStop")

        val playing = world.grab()
        assertTrue(skill.resumesOn(Director.SKEWER_PLAY, playing), "the round it stands in is the pass's own")
        assertTrue(!skill.resumesOn(Director.SKEWER_PAUSE, playing), "a pause menu is never the pass's")
        offAt = 2
        val second = skill.resume(playing, whole = false)
        assertEquals(Result.STOPPED, second.result, log.joinToString("\n"))
        assertEquals(2, day, "the second pass counts on from the day's count")

        val again = world.grab()
        val screen = Director.classify(again).screen
        assertTrue(skill.resumesOn(screen, again), "$screen")
        offAt = null
        val third = skill.resume(again, whole = false)
        val said = log.joinToString("\n")
        assertEquals(Result.DONE, third.result, said)
        assertEquals(3, day, "three combos in all, as the day asked\n$said")
        assertEquals("menu", world.screen, "the semi-automatic work ends on the menu")
        assertEquals(3, log.count { it.contains("Combo;") }, said)
        assertTrue(log.count { it == "going on with the Chef's Special pass the main switch stopped" } == 2, said)
        // A pass that ran to its end is no longer carried.
        assertTrue(!skill.resumesOn(Director.SKEWER_MENU, again))
        playing.release(); again.release()
    }

    /**
     * The counters are the pass's own, cleared at its top (notes/director.md,
     * "A counter nothing clears is counted again at the end of every pass"):
     * two passes, and the second hands over only what it played.
     */
    @Test
    fun `two passes hand over their own counts`() {
        val world = World().apply { screen = "stage" }
        val rig = Rig(world, combos = 2)
        assertEquals(Result.DONE, rig.skill.work(PaintSkewer.stage()).result, rig.log.toString())
        assertEquals(mapOf("rounds" to 1, "combos" to 2, "best" to 2), rig.skill.lastCounts)
        assertEquals(2, rig.day)
        // The next pass of the same day: one more combo wanted on the page.
        rig.wanted = 3
        world.screen = "menu"
        assertEquals(Result.DONE, rig.skill.work(PaintSkewer.menu()).result, rig.log.toString())
        assertEquals(mapOf("rounds" to 1, "combos" to 1, "best" to 1), rig.skill.lastCounts,
                     "the second pass's own, not the day's")
        assertEquals(3, rig.day)
        // A day that has them is nothing to do, and hands nothing over.
        assertTrue(!rig.skill.hasBudget())
    }

    @Test
    fun `a pass short of its combos parks at the ceiling of rounds`() {
        // Every order goes out wrong (its second ingredient is never taken):
        // no combo can come, and the pass gives up after its rounds.
        val world = World(orders = listOf(listOf("corn", "mushroom")), roundSeconds = 12.0).apply {
            screen = "stage"; swallow += (1..200).filter { it % 2 == 0 }
        }
        val rig = Rig(world, combos = 1, rounds = 2)
        val outcome = rig.skill.work(PaintSkewer.stage())
        assertEquals(Result.PARKED, outcome.result, "${rig.log} / ${world.log}")
        assertTrue(outcome.why.startsWith("Chef's Special played 2 rounds"), outcome.why)
        assertEquals(2, world.rounds)
        assertEquals(0, rig.day)
        assertNull(rig.skill.seesWork(Director.SKEWER_MENU, PaintSkewer.menu()), "a pass that gave up has finished")
    }

    /**
     * The ceiling grows with the combos wanted (SkewerSkill.roundsFor, the
     * page at 999 since 2026-10-04): a ceiling of two rounds for sixteen is
     * five for forty, and a pass whose rounds count right is not parked
     * short of a number it could reach. This world counts fifteen a round,
     * so forty take three -- with the ceiling fixed it parked after two.
     */
    @Test
    fun `a pass that wants more combos gets more rounds at the same rate`() {
        val world = World().apply { screen = "menu" }
        val rig = Rig(world, combos = 40, rounds = 2)
        val outcome = rig.skill.work(PaintSkewer.menu())
        assertEquals(Result.DONE, outcome.result, "${rig.log} / ${world.log}")
        assertEquals(3, world.rounds, world.log.toString())
        assertEquals(40, rig.day)
        assertEquals(0, world.wrong, world.log.toString())
    }

    // ------------------------------------------------------------------------
    // Under the director
    // ------------------------------------------------------------------------
    /** The director over the world, with the world's clock: one tick, then the clock moves by the beat. */
    private class Directed(val world: World, mode: String, steps: List<String> = emptyList(), combos: Int = 1,
                           included: Boolean = true) {
        val log = ArrayList<String>()
        val rig = Rig(world, combos)
        val director = DirectorLoop(world, listOf(rig.skill), emptyList(), { "game" }, { mode },
                                    Chain(steps.map { Chain.Step(it) }, log = { log += it }),
                                    included = { included }, log = { log += it }, now = { world.t })
        fun tick(): Director.Tick = director.tick().also { world.t += it.beat }
    }

    @Test
    fun `semi-automatic, the menu is handed over after three quiet seconds and handed back`() {
        val d = Directed(World().apply { screen = "menu" }, SkillSettings.MODE_SEMI)
        var worked: Director.Tick? = null
        for (i in 0 until 10) {
            val t = d.tick()
            if (t.did == "work skewer") { worked = t; break }
        }
        assertNotNull(worked, "${d.log} / ${d.rig.log}")
        assertTrue(d.log.any { it == "giving the skewer_menu to Chef's Special" }, d.log.toString())
        assertEquals(1, d.rig.day)
        assertEquals("menu", d.world.screen)
        // Handed back on the menu, with the day's combos in: nothing more to do.
        val after = (0 until 4).map { d.tick() }
        assertTrue(after.none { it.did != null }, after.toString())
        assertNull(d.director.parked)
        // A round in play stands the director still, and is no park.
        val playing = Directed(World().apply { screen = "play"; lives = 3; roundAt = t; nextAt = t + 1.0 },
                               SkillSettings.MODE_SEMI)
        val ticks = (0 until 6).map { playing.tick() }
        assertTrue(ticks.all { it.screen == Director.SKEWER_PLAY && it.did == null }, ticks.toString())
        assertNull(playing.director.parked, "a round the player plays is not something in the way")
        assertEquals(emptyList<String>(), playing.world.taps)
    }

    /**
     * PLAN_SKEWER.md K6: with Chef's Special switched off -- Gekkomon Run on,
     * or the task locked -- the player playing a round by hand is nothing in
     * the way. Before 2026-09-30 every skewer screen parked here with
     * "Something is in the way that I did not put there".
     */
    @Test
    fun `with Chef's Special off, its screens stand still and are no park`() {
        for (screen in listOf("menu", "stage", "play")) {
            val d = Directed(World().apply { this.screen = screen; lives = 3; roundAt = t; nextAt = t + 1.0 },
                             SkillSettings.MODE_SEMI, included = false)
            val ticks = (0 until 6).map { d.tick() }
            assertTrue(ticks.all { it.did == null }, ticks.toString())
            assertEquals("a minigame whose task is off; nothing to do here", ticks.last().note, ticks.toString())
            assertNull(d.director.parked, "a park on the player's own $screen")
            assertEquals(emptyList<String>(), d.world.taps)
        }
    }

    @Test
    fun `fully automatic, the chain step walks in from the main screen and comes home`() {
        val d = Directed(World().apply { screen = "main" }, SkillSettings.MODE_FULL, steps = listOf("skewer"))
        var ran: Director.Tick? = null
        for (i in 0 until 6) {
            val t = d.tick()
            if (t.did == "run skewer") { ran = t; break }
        }
        assertNotNull(ran, "${d.log} / ${d.rig.log}")
        assertTrue(d.log.any { it == "chain: starting Chef's Special" }, d.log.toString())
        assertEquals("main", d.world.screen)
        assertEquals(1, d.rig.day)
        assertEquals(listOf("Events icon", "card", "Play Game", "Start"), d.world.taps.take(4))
        // And the day that has its combos is skipped, with the task's own
        // reason -- and its name, not its key, since 2026-10-02
        // (PLAN_BEFUNDE_1_3.md N3 b: the note is the notification's sentence).
        val next = Directed(World().apply { screen = "main" }, SkillSettings.MODE_FULL, steps = listOf("skewer"))
        next.rig.day = 1
        val skipped = (0 until 3).map { next.tick() }
        assertTrue(skipped.any { it.note.startsWith("chain: skipped Chef's Special -- 1 of 1 combos counted today") }, skipped.toString())
        assertEquals(emptyList<String>(), next.world.taps)
    }

    // ------------------------------------------------------------------------
    // Over the ceiling: every tap in its reader's rectangle
    // ------------------------------------------------------------------------
    /**
     * SummonAnchorFlowTest's question on the tour's three long rows --
     * 1080 x 2520 (180 rows of headroom), 1644 x 3840 (278), 720 x 1600 (40)
     * -- asked of painted frames, because no frame of Chef's Special was
     * taken on them (notes/skewer.md, "The page's anchor is not measured":
     * everything of the page is asked at the bottom, the round's dialogs
     * are Gekkomon Run's and stand in the middle). What says a tap landed is
     * the picture under it: Start on its blue, each ingredient in its own
     * cell, the pause menu's Quit on its pink -- and the bottom's rectangle,
     * the headroom's half lower, would have missed Quit.
     */
    @Test
    fun `on a display over the ceiling every tap lands in the rectangle of the reader that aimed it`() {
        for ((w, h, room) in listOf(Triple(1080, 2520, 180), Triple(1644, 3840, 278), Triple(720, 1600, 40))) {
            val format = "${w}x$h"
            val world = World(w, h, room, orders = listOf(listOf("corn", "mushroom"), listOf("potato", "octopus")))
                .apply { screen = "stage" }
            val rig = Rig(world, combos = 1)
            val stage = PaintSkewer.stage(PaintSkewer.frame(w, h, room))
            assertEquals(room, Dungeon.headroom(stage))
            val outcome = rig.skill.work(stage)
            stage.release()
            assertEquals(Result.DONE, outcome.result, "$format: ${rig.log} / ${world.taps}")
            assertEquals(listOf("Start", "corn", "mushroom", "Complete", "potato", "octopus", "Complete",
                                "corn", "mushroom", "pause", "Quit", "beside the stage popup"), world.taps,
                         "$format: ${rig.log}")
            val under = world.under.toMap()
            val start = under.getValue("Start")!!
            assertTrue(start[0] in 97..104 && start[1] >= 220, "$format: Start tapped on ${start.toList()}")
            val quit = under.getValue("Quit")!!
            assertTrue(quit[0] in 143..157, "$format: Quit tapped on ${quit.toList()}")
            val beside = under.getValue("beside the stage popup")!!
            assertTrue(beside[0] in 55..65, "$format: beside the popup, on the dimmed menu: ${beside.toList()}")
            assertEquals("menu", world.screen, format)
        }
        // And where the headroom is large enough to tell: Quit aimed with the
        // bottom's rectangle would have been off the button.
        val paused = PaintSkewer.paused(PaintSkewer.frame(1080, 2520, 180))
        val mid = Dungeon.gameRect(paused, Dungeon.Anchor.MIDDLE)
        val bottom = Dungeon.gameRect(paused)
        val x = Py.roundInt(mid.x0 + Runner.QUIT_PAUSE.fx * mid.gw)
        val y = Py.roundInt(mid.y0 + Runner.QUIT_PAUSE.fy * mid.gh)
        val yOld = Py.roundInt(bottom.y0 + Runner.QUIT_PAUSE.fy * bottom.gh)
        assertEquals(90, yOld - y, "the headroom's half")
        assertTrue(PaintSkewer.hsvAt(paused, x, y)!![0] in 143..157, "Quit where the middle's rectangle puts it")
        assertTrue(PaintSkewer.hsvAt(paused, x, yOld)!![0] !in 143..157, "and not where the bottom's would")
        paused.release()
    }

    // ------------------------------------------------------------------------
    // The page, the chain, the day, the swap
    // ------------------------------------------------------------------------
    @Test
    fun `the task answers for its five screens and is counted over the game day`() {
        val skill = SkewerSkill(World(), PaintSkewer.icons, { SkewerSkill.Settings() })
        for (s in SkewerSkill.SCREENS) assertTrue(skill.worksOn(s), s)
        assertTrue(!skill.worksOn(Director.EVENT_PAGE) && !skill.worksOn(Director.MAIN))
        assertEquals("skewer", skill.key)
        assertEquals("Chef's Special", skill.name)
        assertTrue(skill.hasBudget())
        assertTrue(!SkewerSkill(World(), PaintSkewer.icons, { SkewerSkill.Settings(16, 16) }).hasBudget())
        // The chain has it right after Gekkomon Run, not by default.
        val chainable = SkillSettings.CHAINABLE
        assertEquals(chainable.indexOf("runner") + 1, chainable.indexOf("skewer"))
        assertEquals("Chef's Special", SkillSettings.CHAIN_NAMES["skewer"])
        assertTrue("skewer" !in SkillSettings.CHAIN_DEFAULT)
        // The page: one number, 16 by default, 1 to 999, a supporter's.
        val page = SkillSettings.page("skewer")
        assertTrue(page.supporter)
        val n = page.fields.single() as SkillSettings.Number
        assertEquals(Stored.SKEWER_COMBOS_KEY, n.key)
        assertEquals("Combos per day", n.label)
        assertEquals(16.0, n.default)
        assertEquals(1.0 to 999.0, n.min to n.max)
        // And the rounds a pass may play grow with it: ten for the default,
        // the same rate above it, never fewer than ten.
        assertEquals(10, SkewerSkill.roundsFor(1))
        assertEquals(10, SkewerSkill.roundsFor(16))
        assertEquals(19, SkewerSkill.roundsFor(30))
        assertEquals(625, SkewerSkill.roundsFor(999))
        assertEquals(625, SkewerSkill.roundsFor(n.max.toInt()))
        assertTrue("Gekkomon Run off" in page.note && "8:00" in page.note, page.note)
        // The day's count: the game day's, 08:00 Vienna, both keys in one go.
        val s = MapSettings()
        assertEquals(SkewerSkill.Settings(16, 0), Stored.skewer(s, NOON))
        repeat(10) { Stored.addSkewerCombo(s, NOON) }
        assertEquals(SkewerSkill.Settings(16, 10), Stored.skewer(s, NOON))
        assertEquals(6, Stored.skewer(s, NOON).left)
        s.put(Stored.SKEWER_COMBOS_KEY, 10)
        assertEquals(0, Stored.skewer(s, NOON + 3600).left)
        assertEquals(10, Stored.skewer(s, NOON + 11.5 * 3600).doneToday, "23:30 is the same game day")
        assertEquals(0, Stored.skewer(s, NOON + 20 * 3600).doneToday, "08:00 the next morning is not")
        // The TODAY card: rounds, combos, the best combo kept and not added.
        val card = MapSettings()
        SkillStats.add(card, "skewer", mapOf("rounds" to 2, "combos" to 9, "best" to 9), 1L)
        SkillStats.add(card, "skewer", mapOf("rounds" to 1, "combos" to 7, "best" to 7), 1L)
        assertEquals("3 rounds, 16 combos, best combo 9.",
                     SkillStats.sentence("skewer", SkillStats.read(card, "skewer", 1L)))
        assertEquals("1 round, 1 combo.", SkillStats.sentence("skewer", mapOf("rounds" to 1, "combos" to 1)))
    }

    /**
     * The player's rule of 2026-09-30 (PLAN_SKEWER.md 3.3): Gekkomon Run on
     * switches Chef's Special off and the other way round, both off is
     * allowed -- and each switch is one read, change and write, so that a
     * second store never sees both on.
     */
    @Test
    fun `switching one minigame on switches the other off in one write`() {
        val writes = ArrayList<Map<String, Any?>>()
        val inner = MapSettings()
        val s = object : Settings by inner {
            override fun put(key: String, value: Any?) { writes += mapOf(key to value); inner.put(key, value) }
            override fun putAll(values: Map<String, Any?>) { writes += values; values.forEach { (k, v) -> inner.put(k, v) } }
        }
        // A phone from before 1.3: Gekkomon Run in by the old default, Chef's
        // Special off until switched on.
        assertTrue(Stored.included(s, "runner"))
        assertTrue(!Stored.included(s, "skewer"), "Chef's Special is off until switched on")
        assertTrue(Stored.included(s, "dungeon"), "every other row is in by default, as before")
        assertEquals(listOf("runner"), Stored.include(s, "skewer", true))
        assertEquals(1, writes.size, "one write: $writes")
        assertEquals(mapOf("included_skewer" to true, "included_runner" to false), writes.single())
        assertTrue(Stored.included(s, "skewer") && !Stored.included(s, "runner"))
        // And back: Gekkomon Run on, Chef's Special off.
        assertEquals(listOf("skewer"), Stored.include(s, "runner", true))
        assertTrue(Stored.included(s, "runner") && !Stored.included(s, "skewer"))
        // Off is off, alone; both off is allowed.
        assertEquals(emptyList<String>(), Stored.include(s, "runner", false))
        assertTrue(!Stored.included(s, "runner") && !Stored.included(s, "skewer"))
        // On with the other already off switches nothing else off (it says nothing).
        assertEquals(emptyList<String>(), Stored.include(s, "skewer", true))
        assertTrue(Stored.included(s, "skewer") && !Stored.included(s, "runner"))
        // Any other row is its own switch.
        writes.clear()
        assertEquals(emptyList<String>(), Stored.include(s, "dungeon", false))
        assertEquals(listOf(mapOf<String, Any?>("included_dungeon" to false)), writes)
    }

    private companion object {
        /** 2026-09-23 12:00 in Vienna (CEST, UTC+2). */
        const val NOON = 1790157600.0
    }
}
