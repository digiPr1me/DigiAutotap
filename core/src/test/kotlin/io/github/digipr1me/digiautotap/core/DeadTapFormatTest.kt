package io.github.digipr1me.digiautotap.core

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.opencv.core.Core
import java.io.File
import kotlin.test.assertTrue

/**
 * The quest loop's dead spot (QuestSkill.DEAD_TAP) on every format of the
 * tour: one main screen per row of corpus/formats, the place reckoned as
 * QuestSkill.tap reckons it, at the bottom's rectangle. At 0.04 it lay left
 * of the picture on 30 rows, 1080 x 2400 with an 80 px cutout among them,
 * and a gesture there is one Android refuses ("Path bounds must not be
 * negative"). notes/formats.md, "A dead spot left of the picture is no
 * place, and Android ends the round over it".
 */
@Tag(OracleFamilies.CORPUS_TAG)
class DeadTapFormatTest {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")

    @Test
    fun `the dead spot lies inside the picture on every format`() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val dir = File(repo, "${OracleFamilies.CORPUS}/formats")
        val firsts = dir.listFiles()!!.filter { it.name.startsWith("main_") }
            .groupBy { it.name.removePrefix("main_").substringBeforeLast('_') }
            .mapValues { (_, files) -> files.minBy { it.name } }
        assertTrue(firsts.size >= 40, "${firsts.size} formats -- is the corpus there?")
        val outside = ArrayList<String>()
        for ((format, file) in firsts.toSortedMap()) {
            val img = OracleFamilies.read(file)
            try {
                val r = Dungeon.gameRect(img, Dungeon.Anchor.BOTTOM)
                val x = Py.roundInt(r.x0 + QuestSkill.DEAD_TAP[0] * r.gw)
                val y = Py.roundInt(r.y0 + QuestSkill.DEAD_TAP[1] * r.gh)
                if (x !in 0 until img.cols() || y !in 0 until img.rows()) {
                    outside += "$format: $x,$y on ${img.cols()} x ${img.rows()}"
                }
            } finally {
                img.release()
            }
        }
        assertTrue(outside.isEmpty(), "the dead spot is off the picture on: $outside")
    }
}
