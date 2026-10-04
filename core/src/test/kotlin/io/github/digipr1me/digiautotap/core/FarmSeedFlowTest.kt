package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.Mat
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Meat Field's bought seed on the game's own frames (PLAN_ABSCHLUSS_1_3.md
 * K10): LDPlayer instance 0 on 2026-10-03, the field with six empty plots and
 * the free seeds at 0, and the seed menu with each of its three bags chosen
 * -- the free one under the violet "Ad (0/2)" -- on 900 x 1600, where the 0
 * under the free bag read 4 and the switch planted nothing, and on its twins
 * at 1080 x 1920 and 720 x 1600. Every tap is named by the picture it lands
 * on: a plot of the field, a bag, the green Select, the violet ad, or the
 * forest that closes the menu. Over two visits on each format.
 */
@Tag(OracleFamilies.CORPUS_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FarmSeedFlowTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val frames = HashMap<String, Mat>()

    @BeforeAll
    fun load() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        for ((format, takes) in TAKES) for ((screen, take) in takes) {
            val f = File(repo, "${OracleFamilies.CORPUS}/formats/${screen}_${format}_none0_$take.png")
            frames["$screen@$format"] = OracleFamilies.read(f)
        }
    }

    @AfterAll
    fun release() = frames.values.forEach { it.release() }

    /** The game behind the frames: the field, or the menu with one bag chosen. */
    private inner class Game(val format: String) {
        var menu = false
        /** The bag the menu stands on; it keeps the last choice, and the frames were taken with Great last. */
        var chosen = 2
        val taps = ArrayList<String>()
        val wrong = ArrayList<String>()
        var selects = 0

        fun frame(): Mat {
            val screen = if (!menu) "meat_field_seeds0" else listOf("seed_menu_free0", "seed_menu_good", "seed_menu_great")[chosen]
            return OracleFamilies.copy(frames.getValue("$screen@$format"))
        }

        /** What stands under (x, y) on the frame that is up, asked of that frame. */
        fun tap(x: Int, y: Int) {
            val img = frame()
            try {
                val r = Dungeon.gameRect(img, Farm.DIALOG)
                val fx = (x - r.x0) / r.gw.toDouble()
                val fy = (y - r.y0) / r.gh.toDouble()
                fun near(t: Explore.Blob, rx: Double, ry: Double) = abs(fx - t.fx) <= rx && abs(fy - t.fy) <= ry
                if (!menu) {
                    val plot = (0 until 3).flatMap { row -> (0 until 2).map { col -> col to row } }.firstOrNull { (c, w) ->
                        val t = Farm.plotTap(c, w)
                        val p = Dungeon.gameRect(img, t.anchor)
                        abs(x - (p.x0 + t.fx * p.gw)) <= 0.02 * p.gw && abs(y - (p.y0 + t.fy * p.gh)) <= 0.02 * p.gh
                    }
                    taps += if (plot != null) "plot ${plot.first},${plot.second}" else "field %.3f/%.3f".format(fx, fy)
                    if (plot != null) menu = true
                    return
                }
                val slots = Farm.seedSlots(img)
                val slot = slots.indexOfFirst { near(it, 0.065, 0.025) }
                if (slot >= 0) {
                    taps += "seed slot $slot"
                    chosen = slot
                    return
                }
                val select = Farm.seedMenu(img).second
                if (select != null && near(select, 0.12, 0.025)) {
                    taps += "Select ${listOf("free", "Good", "Great")[chosen]}"
                    if (chosen == 0) wrong += "Select on the free bag at 0"
                    selects += 1
                    menu = false
                    return
                }
                val ad = Farm.adDialog(img)
                if (ad != null && abs(fx - ad.button.fx) <= 0.12 && abs(fy - ad.button.fy) <= 0.025) {
                    taps += "seed ad"
                    wrong += "the seeds' ad at 0/2"
                    return
                }
                if (abs(fx - Farm.BOOST_CLOSE[0]) <= 0.05 && abs(fy - Farm.BOOST_CLOSE[1]) <= 0.03) {
                    taps += "outside"
                    menu = false
                    return
                }
                taps += "menu %.3f/%.3f".format(fx, fy)
                wrong += "a tap on nothing of the menu at %.3f/%.3f".format(fx, fy)
            } finally {
                img.release()
            }
        }
    }

    private fun visit(game: Game, log: MutableList<String>) {
        val cap = DirectorTest.FakeCapture()
        cap.scene = { game.frame() }
        cap.onTap = { x, y -> game.tap(x, y) }
        var clock = 1_000_000.0
        val skill = FarmSkill(
            cap, dueAt = { null }, remember = { _, _, _ -> }, knownGrowSeconds = { null },
            log = { log += it }, on = { true }, keep = { _, _ -> }, sleep = { clock += it }, now = { clock },
            settings = { FarmSkill.Settings(betterSeeds = true) }, adsUsed = { 0 }, setAdsUsed = { _, _ -> })
        val img = game.frame()
        skill.work(img)
        img.release()
    }

    @Test
    fun `the free seeds at 0 plant a Good seed on the game's own menus, over two visits`() {
        for (format in TAKES.keys) {
            val game = Game(format)
            val log = ArrayList<String>()
            for (n in 1..2) {
                val before = game.selects
                visit(game, log)
                // The field's frame stays as it was taken: the Good seed's
                // proof never comes, and a visit taps Select once for it.
                assertTrue(game.selects > before, "$format, visit $n: no Select -- ${game.taps}\n$log")
            }
            assertTrue(log.none { it.contains("one witness only") }, "$format: $log")
            assertTrue(game.taps.filter { it.startsWith("Select") }.all { it == "Select Good" }, "$format: ${game.taps}")
            assertEquals(emptyList(), game.wrong, "$format: ${game.taps}")
        }
    }

    companion object {
        /** The takes of 2026-10-03, the app stopped, one session, each format's four. */
        val TAKES = mapOf(
            "900x1600" to mapOf("meat_field_seeds0" to "081705", "seed_menu_free0" to "081709",
                                "seed_menu_good" to "081733", "seed_menu_great" to "081736"),
            "1080x1920" to mapOf("meat_field_seeds0" to "081850", "seed_menu_free0" to "081839",
                                 "seed_menu_good" to "081843", "seed_menu_great" to "081846"),
            "720x1600" to mapOf("meat_field_seeds0" to "082016", "seed_menu_free0" to "082006",
                                "seed_menu_good" to "082009", "seed_menu_great" to "082012"))
    }
}
