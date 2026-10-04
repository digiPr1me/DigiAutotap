package io.github.digipr1me.digiautotap.core

import org.opencv.core.Mat
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Idle Rewards on painted frames (PaintQuest): the chest, the window with
 * Claim lit and dimmed and its "n/2", the game's question before an ad, and
 * the Reward sheet. The world answers every tap the way the game did on
 * 2026-09-28 on LDPlayer instance 1: the chest opens the window, Claim raises
 * the sheet and dims itself, Extra Rewards raises the question on an account
 * without the Ad Skip Pass and the sheet with one off the count on one with
 * it, a tap below the window closes it.
 */
class IdleSkillTest {

    private enum class Screen { MAIN, WINDOW, PROMPT, SHEET }

    private inner class Game(var chest: Boolean = true, var lit: Boolean = true, var left: Int = 2,
                             /** The account has the Ad Skip Pass: Extra Rewards hands the reward over with no question. */
                             val pass: Boolean = false) : Capture {
        val hand = FakeFront()
        /** The clock, moved by the skill's sleeps. */
        var t = 0.0
        var screen = Screen.MAIN
        val taps = ArrayList<String>()
        /** Where the sheet goes when it is tapped away. */
        private var afterSheet = Screen.WINDOW

        fun frame(): Mat = when (screen) {
            Screen.MAIN -> PaintQuest.idleMain(chest)
            Screen.WINDOW -> PaintQuest.idleWindow(lit, if (left > 0) left else null)
            Screen.PROMPT -> Paint.prompt(pink = true)
            Screen.SHEET -> PaintQuest.rewardSheet()
        }

        override fun grab(): Mat = frame()

        override fun tap(x: Int, y: Int) {
            val img = frame()
            val (fx, fy) = Dungeon.gameRect(img).let { r -> (x - r.x0) / r.gw.toDouble() to (y - r.y0) / r.gh.toDouble() }
            img.release()
            fun near(ax: Double, ay: Double) = abs(fx - ax) < 0.06 && abs(fy - ay) < 0.03
            val what = when (screen) {
                Screen.MAIN -> if (chest && near(0.149, 0.700)) { screen = Screen.WINDOW; "chest" } else "main"
                Screen.WINDOW -> when {
                    near(0.6007, 0.7439) && lit -> { lit = false; screen = Screen.SHEET; afterSheet = Screen.WINDOW; "claim" }
                    near(0.6007, 0.7439) -> "claim dimmed"
                    near(0.3514, 0.7437) && left > 0 && pass -> {
                        screen = Screen.SHEET
                        left -= 1
                        afterSheet = Screen.WINDOW
                        "extra"
                    }
                    near(0.3514, 0.7437) && left > 0 -> { screen = Screen.PROMPT; "extra" }
                    near(0.3514, 0.7437) -> "extra at 0"
                    fy > 0.80 -> { screen = Screen.MAIN; "beside" }
                    else -> "window"
                }
                // OK starts the ad: the world goes on as if it had paid, and
                // the test sees "ok" among the taps.
                Screen.PROMPT -> if (near(Paint.PINK_OK.fx, Paint.PINK_OK.fy)) {
                    screen = Screen.SHEET
                    left -= 1
                    "ok"
                } else "prompt"
                Screen.SHEET -> { screen = afterSheet; "sheet" }
            }
            taps += what
        }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {}
        override fun back() { taps += "back" }
        override fun inFront(): String? = hand.game
        override fun adHand(): AdHand = hand
    }

    private class Book {
        var due: Double? = null
        var used = 0
        val kept = ArrayList<String>()
    }

    private fun skill(g: Game, book: Book, set: IdleSkill.Settings) = IdleSkill(
        g, { set }, dueAt = { book.due }, remember = { book.due = it },
        adsUsed = { book.used }, setAdsUsed = { book.used = it },
        log = { println(it) }, on = { true }, keep = { _, tag -> book.kept += tag },
        sleep = { g.t += it }, now = { g.t })

    @Test
    fun `the painted frames read as the real ones do`() {
        val main = PaintQuest.idleMain()
        assertNotNull(Quest.rewardsChest(main))
        assertEquals(Director.MAIN, Director.classify(main).screen)
        assertNull(Quest.rewardsChest(PaintQuest.idleMain(chest = false)))
        for (lit in listOf(true, false)) {
            val img = PaintQuest.idleWindow(lit, 2)
            val w = assertNotNull(Quest.idleWindow(img), "lit $lit")
            assertEquals(lit, w.lit)
            assertEquals(2, Quest.idleExtraLeft(img, w))
            assertEquals(Director.CLAIM_REWARDS, Director.classify(img).screen)
        }
        val one = PaintQuest.idleWindow(false, 1)
        assertEquals(1, Quest.idleExtraLeft(one, Quest.idleWindow(one)!!))
        assertTrue(Dungeon.rewardSheet(PaintQuest.rewardSheet()))
    }

    @Test
    fun `with the ads off a visit claims, closes the window and books four hours`() {
        val g = Game()
        val book = Book()
        val s = skill(g, book, IdleSkill.Settings(ads = false))
        assertTrue(s.hasBudget(), "never visited: due")
        val out = s.work(g.grab())
        assertEquals(Result.DONE, out.result, out.why)
        assertEquals(listOf("chest", "claim", "sheet", "beside"), g.taps)
        assertEquals(Screen.MAIN, g.screen)
        assertEquals(mapOf("claims" to 1, "ads" to 0), s.lastCounts)
        assertEquals(g.t + IdleSkill.EVERY, book.due!!, 1.0)
        assertFalse(s.hasBudget(), "claimed: not before the clock")
    }

    @Test
    fun `a chest the frame does not show is not tapped and books nothing`() {
        val g = Game(chest = false)
        val book = Book()
        val s = skill(g, book, IdleSkill.Settings(ads = true))
        assertEquals(Result.DONE, s.work(g.grab()).result)
        assertTrue(g.taps.isEmpty())
        assertNull(book.due)
        assertTrue(s.hasBudget(), "looked for again next round")
    }

    @Test
    fun `the window the game opened by itself is worked and left standing`() {
        val g = Game(lit = true, left = 0)
        g.screen = Screen.WINDOW
        val book = Book()
        val s = skill(g, book, IdleSkill.Settings(ads = false))
        val img = g.grab()
        assertEquals(true, s.seesWork(Director.CLAIM_REWARDS, img))
        assertEquals(Result.DONE, s.work(img).result)
        assertEquals(listOf("claim", "sheet"), g.taps)
        assertEquals(Screen.WINDOW, g.screen)
        assertEquals(false, s.seesWork(Director.CLAIM_REWARDS, g.grab()), "claimed, ads off: nothing left")
    }

    @Test
    fun `the day's ads by the file keep the task due, and the reset clears them`() {
        val book = Book()
        book.due = 1e12
        val g = Game()
        assertTrue(skill(g, book, IdleSkill.Settings(ads = true)).hasBudget())
        assertFalse(skill(g, book, IdleSkill.Settings(ads = false)).hasBudget())
        book.used = 2
        assertFalse(skill(g, book, IdleSkill.Settings(ads = true)).hasBudget())

        val store = MapSettings()
        val day = 1_790_000_000.0
        Stored.setIdleAdsUsed(store, 2, day)
        assertEquals(2, Stored.idleAdsUsed(store, day))
        assertEquals(0, Stored.idleAdsUsed(store, QuestSkill.nextReset(day) + 1))
    }

    /**
     * Two pauses in one visit (PLAN_RELEASE_1_3.md B4), a round from the main
     * screen with the Extra Rewards on and the pass: the switch goes off as
     * Claim is tapped -- the visit stops on its Reward sheet, the director's
     * `unknown` -- and again as the window comes back after the first ad. Each
     * time the visit goes on where it stands and, having begun on the main
     * screen, closes the window at its end. One claim and two ads in all,
     * the file's count at two, home at the end.
     */
    @Test
    fun `Idle Rewards paused twice claims once, takes both ads once and goes home`() {
        val g = Game(pass = true)
        val book = Book()
        var on = true
        var held = 0
        var offWhen: (List<String>) -> Boolean = { it.last() == "claim" }
        val hand = object : Capture by g {
            override fun tap(x: Int, y: Int) {
                if (!on) held += 1
                g.tap(x, y)
                if (offWhen(g.taps)) on = false
            }
        }
        val s = IdleSkill(hand, { IdleSkill.Settings(ads = true) }, dueAt = { book.due },
                          remember = { book.due = it }, adsUsed = { book.used }, setAdsUsed = { book.used = it },
                          log = { println(it) }, on = { on }, keep = { _, tag -> book.kept += tag },
                          sleep = { g.t += it }, now = { g.t })
        val claims = ArrayList<Int>()
        val ads = ArrayList<Int>()
        fun counted() {
            claims += s.lastCounts["claims"] ?: 0
            ads += s.lastCounts["ads"] ?: 0
        }

        assertEquals(Result.STOPPED, s.work(g.grab()).result)
        counted()
        assertEquals(Screen.SHEET, g.screen, "the sheet stands where the pause found it")
        val sheet = g.grab()
        assertTrue(s.resumesOn(Director.classify(sheet).screen, sheet), "the sheet is the visit's own")

        // Back on: the claim's sheet tapped away, the first ad, and off again
        // as its sheet is tapped away (the second "sheet" of the visit).
        on = true
        offWhen = { taps -> taps.last() == "sheet" && taps.count { it == "sheet" } == 2 }
        val second = s.resume(sheet, whole = false)
        assertEquals(Result.STOPPED, second.result)
        counted()
        assertEquals(Screen.WINDOW, g.screen)
        val window = g.grab()
        assertTrue(s.resumesOn(Director.CLAIM_REWARDS, window))

        on = true
        offWhen = { false }
        val third = s.resume(window, whole = false)
        assertEquals(Result.DONE, third.result)
        counted()
        assertEquals(1, claims.sum(), "one claim: $claims ${g.taps}")
        assertEquals(2, ads.sum(), "two ads: $ads ${g.taps}")
        assertEquals(2, book.used, "the file's count")
        assertEquals(0, g.left)
        assertEquals(1, g.taps.count { it == "claim" }, "${g.taps}")
        assertEquals(2, g.taps.count { it == "extra" }, "${g.taps}")
        assertEquals(Screen.MAIN, g.screen, "a visit begun on the main screen ends there")
        assertEquals(0, held, "no tap with the switch off: ${g.taps}")
        assertTrue(book.kept.isEmpty(), "${book.kept}")
    }

    /** The file a phone can carry into 1.3: `idle_ads` on, the pass as [pass], and `ad_watch` on, which nothing reads. */
    private fun adFile(pass: Boolean) = MapSettings(mapOf(
        SkillSettings.AD_PASS_KEY to pass, "ad_watch" to true, Stored.IDLE_ADS_KEY to true))

    /**
     * Without the Ad Skip Pass no Extra Rewards is tapped: `idle_ads` on and
     * `ad_watch` on in the file, over two visits (2026-10-03,
     * PLAN_ABSCHLUSS_1_3.md 3.6). Each visit claims and closes the window;
     * the day's two Extra Rewards stay where they are.
     */
    @Test
    fun `without the pass no Extra Rewards is tapped, with ad_watch in the file, over two visits`() {
        val g = Game()
        val book = Book()
        val s = skill(g, book, Stored.idle(adFile(pass = false)))
        repeat(2) {
            g.screen = Screen.MAIN
            g.lit = true
            val out = s.work(g.grab())
            assertEquals(Result.DONE, out.result, out.why)
        }
        assertEquals(listOf("chest", "claim", "sheet", "beside", "chest", "claim", "sheet", "beside"), g.taps)
        assertEquals(2, g.left, "the day's two Extra Rewards stay where they are")
        assertEquals(0, book.used)
    }

    /** With the pass the game hands the Extra Rewards over with the tap: two, and no question answered. */
    @Test
    fun `with the pass both Extra Rewards come with the tap`() {
        val g = Game(pass = true)
        val book = Book()
        val s = skill(g, book, Stored.idle(adFile(pass = true)))
        val out = s.work(g.grab())
        assertEquals(Result.DONE, out.result, out.why)
        assertEquals(listOf("chest", "claim", "sheet", "extra", "sheet", "extra", "sheet", "beside"), g.taps)
        assertEquals(0, g.left)
        assertEquals(2, book.used)
        assertEquals(mapOf("claims" to 1, "ads" to 2), s.lastCounts)
    }

    /**
     * The pass switched on for an account without it: Extra Rewards raises
     * the game's question. Nothing is tapped on it, and the visit parks with
     * the one sentence; the question stands for the player.
     */
    @Test
    fun `with the pass the game's question parks the visit untouched`() {
        val g = Game(pass = false)
        val book = Book()
        val s = skill(g, book, Stored.idle(adFile(pass = true)))
        val out = s.work(g.grab())
        assertEquals(Result.PARKED, out.result, out.why)
        assertEquals(FreeAds.PARK, out.why)
        assertEquals(listOf("chest", "claim", "sheet", "extra"), g.taps, "nothing tapped on the question")
        assertEquals(Screen.PROMPT, g.screen, "the question stands for the player")
        assertEquals(2, g.left)
    }
}
