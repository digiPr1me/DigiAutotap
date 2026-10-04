package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.system.exitProcess

/**
 * What the readers say about pictures that are not in the corpus yet.
 *
 *   gradlew :core:readerProbe --no-daemon --args="explore,director staging/farm/a.png staging/farm/b.png"
 *
 * The first argument names the families ([OracleFamilies.BY_NAME], comma
 * separated), every other argument is a picture or a folder of them. Each
 * picture is read as `DigiAutotapService.grab` would hand it over -- with
 * its headroom, by its name ([OracleFamilies.framed]) -- and every answer of
 * every named family is printed, one per line. A key argument filters the
 * keys: `key=water` prints only keys that contain "water". `mask=0.06:0.155:0.005`
 * asks again with the overlay's dot and plate masked in ([Dot.square],
 * [Dot.plate], [Dot.mask]) at every fy of that range -- what the overlay
 * costs a reader, before OverlayOracleTest can say it over the corpus.
 *
 * This is how a frame is measured before it is moved into `corpus/`: the
 * oracle writer reads nothing else, and a frame goes there by hand, once
 * its answers have been looked at (NOTES.md, "The corpus is a deliberate
 * act"). Nothing here writes anywhere.
 */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    if (args.size < 2) {
        println("readerProbe wants families and pictures: `explore,director a.png b.png [key=part]`")
        exitProcess(2)
    }
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val families = args[0].split(',').map {
        OracleFamilies.BY_NAME[it] ?: run { println("no family '$it': ${OracleFamilies.BY_NAME.keys}"); exitProcess(2) }
    }
    val keys = args.drop(1).filter { it.startsWith("key=") }.map { it.removePrefix("key=") }
    val places: List<Double?> = args.drop(1).firstOrNull { it.startsWith("mask=") }?.removePrefix("mask=")
        ?.split(':')?.map { it.toDouble() }
        ?.let { (a, b, step) -> generateSequence(a) { it + step }.takeWhile { it <= b + 1e-9 }.toList() }
        ?: listOf(null)
    val files = args.drop(1).filter { !it.startsWith("key=") && !it.startsWith("mask=") }.flatMap { arg ->
        val f = File(arg).let { if (it.isAbsolute) it else File(repo, arg) }
        if (f.isDirectory) f.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name } else listOf(f)
    }
    val vision = Vision(ClassPathAssets)
    for (file in files) {
        val raw = OracleFamilies.framed(Imgcodecs.imread(file.path), file.name)
        println("== ${file.name}  ${raw.cols()} x ${raw.rows()}  headroom ${Dungeon.headroom(raw)} above ${Dungeon.above(raw)}")
        for (fy in places) {
            val img = if (fy == null) raw else OracleFamilies.copy(raw).also {
                Dot.mask(it, listOf(Dot.square(it.cols(), it.rows(), false, fy), Dot.plate(it.cols(), it.rows(), false, fy)))
            }
            val at = if (fy == null) "" else "[fy %.3f] ".format(fy)
            for (family in families) {
                val answers = OracleFamilies.answers(family, img, vision)
                for ((key, answer) in answers) {
                    if (key == "size") continue
                    if (keys.isNotEmpty() && keys.none { key.contains(it) }) continue
                    println("  $at${family.name}.$key = ${Oracle.encode(answer)}")
                }
            }
            if (img !== raw) img.release()
        }
        raw.release()
    }
}
