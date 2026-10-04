package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opencv.core.Core
import java.io.File
import java.util.Locale

/**
 * The planner's first decision on every position the corpus's boards can
 * give it (PLAN_WORLD_SEARCH_FORMATE.md 4.2, F1). Every frame the oracle
 * calls `board` is read as the skill reads it -- `calibrate`, `find_figure`,
 * `read_grid` -- and the grid is observed twice, as `WorldSearchSkill.run`
 * does; then the figure is put on every free cell of the two columns it can
 * stand in, and the planner is asked once, with paws 100, fireballs 3 and
 * claws 3 or 0, `dashEager` off and on. An exception (a StackOverflowError
 * too) is a line of the report, not the end of it.
 *
 *   gradlew :core:plannerProbe --no-daemon
 *   gradlew :core:plannerProbe --no-daemon --args="compare=<an earlier plannerProbe.tsv>"
 *   gradlew :core:plannerProbe --no-daemon --args="cells=open"
 *   gradlew :core:plannerProbe --no-daemon --args="fireballs=0"      (or =null, unread)
 *   gradlew :core:plannerProbe --no-daemon --args="synthetic=20000"  (random boards beside the corpus)
 *
 * With no claws it also says, position by position, which of three meanings
 * of "walled in" holds (PLAN_WORLD_SEARCH_FORMATE.md 4.2, F9): every
 * neighbour on the board a pyramid; no cell right of the figure's column
 * reachable on foot; no wanted target reachable on foot.
 *
 * Writes nothing but its own report: `core/build/plannerProbe.txt` (all of
 * it, NOTES.md "Two ways to lose a corpus sweep") and `core/build/
 * plannerProbe.tsv` (one decision per position, what `compare=` reads: every
 * position whose decision differs from that file's is printed, old and new
 * -- a planner change's footprint). A `compare=` is against a run with the
 * same `fireballs=`: the key does not carry it.
 */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    val compare = args.firstOrNull { it.startsWith("compare=") }?.removePrefix("compare=")
        ?.let { File(it).let { f -> if (f.isAbsolute) f else File(repo, it) } }
    // The dash's counter for every position: 3 unless given, "null" for a
    // counter that did not read.
    val fireArg = args.firstOrNull { it.startsWith("fireballs=") }?.removePrefix("fireballs=")
    val fireballs: Long? = when (fireArg) { null -> 3L; "null" -> null; else -> fireArg.toLong() }
    // Free is an empty cell. `cells=open` takes every cell that is not a
    // pyramid, objects and '?' too, which is how PLAN_WORLD_SEARCH_DASH.md
    // 4.4 counted its 524 positions (World.observe skips the figure's own
    // cell, so an object under it is simply not there).
    val open = "cells=open" in args
    val out = StringBuilder()
    fun p(s: String) { out.append(s).append('\n'); println(s) }

    val director = Json.parseToJsonElement(File(repo, "oracle/director.json").readText()).jsonObject["frames"]!!.jsonObject
    val oracleVision = Json.parseToJsonElement(File(repo, "oracle/vision.json").readText()).jsonObject["frames"]!!.jsonObject
    val boards = director.entries.filter { (_, a) ->
        a.jsonObject["classify"]?.jsonObject?.get("screen")?.jsonPrimitive?.content == "board"
    }.map { it.key }.sorted()
    val gridKey = "read_grid(calib=calibrate,templates=board_templates,figure=find_figure)"

    val letter = mapOf(null to ".", "pyramid" to "P", "ticket_orange" to "O", "ticket_green" to "G",
                       "ticket_pink" to "R", "claw" to "K", "paw" to "T", "fireball" to "F", "?" to "?")
    fun compact(grid: List<List<String?>>) = grid.joinToString("/") { r -> r.joinToString("") { letter[it] ?: "#" } }

    val vision = Vision(ClassPathAssets)
    val board = vision.boardTemplates()
    val figureTemplates = vision.loadTemplates()

    class Pos(val frame: String, val grid: List<List<String?>>, val row: Int, val col: Int,
              val claws: Long, val eager: Boolean) {
        val key get() = "$frame\tr${row + 1}c${col + 1}\tclaws $claws\teager ${if (eager) "on" else "off"}"
        val distinct get() = "${compact(grid)}|$row,$col|$claws|$eager"
    }
    val decisions = LinkedHashMap<String, String>()
    val lines = ArrayList<Triple<Pos, String, String>>()   // position, decision, target note
    // F9: with no claws, which of the three meanings of "walled in" holds,
    // asked of the world before the planner gives anything up.
    class Stuck(val walled: Boolean, val penned: Boolean, val noTarget: Boolean)
    val stuck = HashMap<String, Stuck>()
    fun onFoot(w: World) = Router(
        isPyramid = { r, c -> w.isPyramid(w.scrollOffset + c, r) },
        costStep = (PlannerConst.BIT_PAW + PlannerConst.BIT_PER_ACTION).toDouble(),
        costDestroy = (PlannerConst.BIT_CLAW + PlannerConst.BIT_PER_ACTION - PlannerConst.BIT_PYRAMID_LOOT).toDouble(),
        canDestroy = false)

    var mismatches = 0
    val grids = LinkedHashMap<String, List<List<String?>>>()
    p("== boards: ${boards.size} frames the oracle calls `board`; the figure on every " +
      (if (open) "cell that is not a pyramid" else "empty cell") + " of columns 1 and 2; fireballs " +
      (fireballs ?: "unread"))
    for (rel in boards) {
        val img = OracleFamilies.read(File(repo, rel))
        val calib = vision.calibrate(img)
        val figure = vision.findFigure(img, calib, figureTemplates)
        val grid = vision.readGrid(img, calib, board, figure = figure).first
        img.release()
        // What the oracle keeps for the same question, as a check on the
        // probe: the oracle suite holds the reader to it, so the two agree
        // unless the corpus or the reader moved under a stale oracle.
        val kept = oracleVision[rel]?.jsonObject?.get(gridKey)?.jsonArray?.get(0)?.jsonArray?.map { r ->
            r.jsonArray.map { c -> if (c is JsonNull) null else c.jsonPrimitive.content }
        }
        if (kept != grid) { mismatches++; p("  GRID DIFFERS FROM THE ORACLE: $rel") }
        grids[rel] = grid
        p(String.format(Locale.ROOT, "  %s  %s  figure %s", rel, compact(grid),
            figure?.let { "r${it.row + 1}c${it.col + 1} ${it.how} ${it.score}" } ?: "none"))
    }
    p("grids unlike the oracle's: $mismatches; distinct grids: ${grids.values.map { compact(it) }.toSet().size}")

    // One position: the world as the skill builds it, the three meanings of
    // "walled in" asked of it before the planner gives anything up, and the
    // planner's first decision.
    fun ask(pos: Pos): Triple<Pos, String, String> {
        val (row, col) = pos.row to pos.col
        val w = World(WorldSearchSettings().wanted)
        val fig = Figure(row, col, "probe", null)
        w.observe(pos.grid, fig)
        w.observe(pos.grid, fig)
        val target = w.visibleWanted().firstOrNull()?.let { (g, r, k) -> "$k r${r + 1}c${g + 1}" } ?: "-"
        val left = (0 until Vision.ROWS).filter { w.isWanted(0, it) }
            .joinToString(",") { "${w.at(0, it)} r${it + 1}c1" }.ifEmpty { "-" }
        val s = WorldSearchSettings(dashEager = pos.eager)
        val plan = Planner(w, minPaws = s.minPaws, targetMeters = s.targetMeters, bitPaw = s.bitPaw,
                           bitClaw = s.bitClaw, bitSkill = s.bitSkill, bitPerAction = s.bitPerAction,
                           bitPyramidLoot = s.bitPyramidLoot, rowSlack = s.rowSlack,
                           bitLeftPenalty = s.bitLeftPenalty, bitMiddleBias = s.bitMiddleBias,
                           dashEager = s.dashEager)
        if (pos.claws == 0L) {
            val next = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).map { (dr, dc) -> row + dr to col + dc }
                .filter { (r, c) -> r in 0 until Vision.ROWS && c in 0 until Vision.COLS }
            val walled = next.all { (r, c) -> w.isPyramid(w.scrollOffset + c, r) }
            val penned = onFoot(w).search(row to col, emptySet()).first == null
            val wanted = w.visibleWanted()
            val noTarget = wanted.isNotEmpty() && wanted.none { (g, r, _) ->
                onFoot(w).search(row to col, setOf(r to g - w.scrollOffset)).reachable }
            stuck[pos.key] = Stuck(walled, penned, noTarget)
        }
        val counters = linkedMapOf<String, Long?>("paws" to 100L, "claws" to pos.claws, "fireballs" to fireballs)
        val said = try {
            plan.nextAction(counters).toString()
        } catch (e: Throwable) {
            "THROWS ${e.javaClass.simpleName}"
        }
        decisions[pos.key] = said
        return Triple(pos, said, "first target $target; wanted in the left column $left")
    }

    for ((rel, grid) in grids) {
        for (claws in listOf(3L, 0L)) for (eager in listOf(false, true)) {
            for (row in 0 until Vision.ROWS) for (col in 0..WorldConst.FIG_COL_MAX) {
                if (if (open) grid[row][col] == "pyramid" else grid[row][col] != null) continue
                lines += ask(Pos(rel, grid, row, col, claws, eager))
            }
        }
    }

    // synthetic=N (F9): N random boards beside the corpus, because the
    // corpus has one walled-in position and cannot tell the three meanings
    // apart. Seeded, so two runs ask the same positions and `compare=` holds
    // them as it holds the corpus's. A cell is a pyramid at 0.25 -- twice
    // the corpus's density (its 60 boards carry 0 to 6 of 25, 3.0 on
    // average), so that a figure walled in comes up often enough to count;
    // these are a sample of shapes, not of how often a real board makes
    // them -- else a wanted object at 0.08 (the corpus: 1.85 a board), else
    // empty; the figure on a random cell of columns 1 and 2 that is not a
    // pyramid; no claws, as only then can the figure be stuck at all.
    val synthetic = args.firstOrNull { it.startsWith("synthetic=") }?.removePrefix("synthetic=")?.toInt() ?: 0
    val synth = ArrayList<Triple<Pos, String, String>>()
    if (synthetic > 0) {
        val rnd = java.util.Random(9)
        val kinds = WorldConst.ALL_WANTED
        for (i in 0 until synthetic) {
            val grid = List(Vision.ROWS) { List(Vision.COLS) {
                val x = rnd.nextDouble()
                when {
                    x < 0.25 -> "pyramid"
                    x < 0.33 -> kinds[rnd.nextInt(kinds.size)]
                    else -> null
                }
            } }
            val free = (0 until Vision.ROWS).flatMap { r -> (0..WorldConst.FIG_COL_MAX).map { c -> r to c } }
                .filter { (r, c) -> grid[r][c] != "pyramid" }
            if (free.isEmpty()) continue
            val (row, col) = free[rnd.nextInt(free.size)]
            val placed = grid.mapIndexed { r, cells -> cells.mapIndexed { c, v -> if (r == row && c == col) null else v } }
            for (eager in listOf(false, true)) synth += ask(Pos("synthetic#$i", placed, row, col, 0L, eager))
        }
    }

    fun kindOf(said: String) = if (said.startsWith("THROWS")) said else said.substringBefore(' ')
    // A reason without its numbers, and without the dash-held tail, which is
    // counted on its own.
    fun reasonOf(said: String): String {
        if (said.startsWith("THROWS")) return said
        val r = said.substringAfter(" (").removeSuffix(")")
        return r.substringBefore(", dash held: ").replace(Regex("\\d+"), "#")
    }
    fun heldOf(said: String): String? =
        if (", dash held: " in said) said.substringAfter(", dash held: ").removeSuffix(")").replace(Regex("\\d+"), "#") else null

    for (claws in listOf(3L, 0L)) for (eager in listOf(false, true)) {
        val these = lines.filter { it.first.claws == claws && it.first.eager == eager }
        p("")
        p("== claws $claws, dashEager ${if (eager) "on" else "off"}: ${these.size} positions " +
          "(${these.map { it.first.distinct }.toSet().size} distinct)")
        val over = these.filter { it.second.startsWith("THROWS") }
        p("  exceptions: ${over.size} (${over.map { it.first.distinct }.toSet().size} distinct) on " +
          "${over.map { it.first.frame }.toSet().size} boards")
        for ((pos, said, note) in over) p("    ${pos.frame} figure r${pos.row + 1}c${pos.col + 1}: $said; $note; ${compact(pos.grid)}")
        p("  by kind:")
        these.groupingBy { kindOf(it.second) }.eachCount().entries.sortedByDescending { it.value }
            .forEach { p(String.format(Locale.ROOT, "    %4d  %s", it.value, it.key)) }
        p("  by kind and reason:")
        these.groupingBy { kindOf(it.second) + " | " + reasonOf(it.second) }.eachCount().entries
            .sortedWith(compareBy({ it.key.substringBefore(" | ") }, { -it.value }))
            .forEach { p(String.format(Locale.ROOT, "    %4d  %s", it.value, it.key)) }
        p("  dash held:")
        these.mapNotNull { heldOf(it.second) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .forEach { p(String.format(Locale.ROOT, "    %4d  %s", it.value, it.key)) }
    }

    fun tagsOf(key: String): String {
        val s = stuck[key] ?: return "-"
        return listOfNotNull("walled".takeIf { s.walled }, "penned".takeIf { s.penned },
                             "no target".takeIf { s.noTarget }).joinToString(",").ifEmpty { "free" }
    }
    fun stuckReport(title: String, set: List<Triple<Pos, String, String>>, every: Boolean) {
        val noClaws = set.filter { it.first.claws == 0L }
        fun has(test: (Stuck) -> Boolean) = noClaws.filter { stuck[it.first.key]?.let(test) == true }
        val walled = has { it.walled }
        val penned = has { it.penned }
        val noTarget = has { it.noTarget }
        p("")
        p("== stuck on foot, $title, claws 0, fireballs ${fireballs ?: "unread"} " +
          "(${noClaws.size} positions, both switch settings)")
        p("  walled in (every neighbour on the board a pyramid): ${walled.size}")
        p("  penned (no cell right of the figure's column reachable on foot): ${penned.size}")
        p("  no wanted target reachable on foot: ${noTarget.size}")
        p("  walled but not penned: ${walled.count { it !in penned }}; penned but not walled: " +
          "${penned.count { it !in walled }}; no target but not penned: ${noTarget.count { it !in penned }}")
        p("  by meaning and decision:")
        (walled + penned + noTarget).distinct()
            .groupingBy { "[${tagsOf(it.first.key)}] ${kindOf(it.second)} | ${reasonOf(it.second)}" +
                          (heldOf(it.second)?.let { h -> ", dash held: $h" } ?: "") }
            .eachCount().entries.sortedBy { it.key }
            .forEach { p(String.format(Locale.ROOT, "    %5d  %s", it.value, it.key)) }
        if (every) for ((pos, said, note) in (walled + penned + noTarget).distinct()) {
            p("    [${tagsOf(pos.key)}] ${pos.key}\t$said\t[$note]; ${compact(pos.grid)}")
        }
    }
    stuckReport("the corpus", lines, every = true)
    if (synth.isNotEmpty()) {
        stuckReport("$synthetic synthetic boards", synth, every = false)
        // The penned positions that are not walled in, a few of them, so
        // that the difference between the two meanings can be looked at.
        val pennedOnly = synth.filter { stuck[it.first.key]?.let { s -> s.penned && !s.walled } == true }
        p("  penned but not walled, the first ${minOf(8, pennedOnly.size)}:")
        for ((pos, said, _) in pennedOnly.take(8)) p("    ${pos.key}\t$said\t${compact(pos.grid)}")
    }

    p("")
    p("== every position")
    for ((pos, said, note) in lines) p("  ${pos.key}\t$said\t[$note]")

    if (compare != null) {
        p("")
        val before = compare.readLines().filter { it.isNotBlank() }.associate {
            val parts = it.split('\t')
            parts.dropLast(1).joinToString("\t") to parts.last()
        }
        val byKey = (lines + synth).associateBy { it.first.key }
        val changed = decisions.filter { (k, v) -> before[k] != null && before[k] != v }
        val missing = before.keys - decisions.keys
        val added = decisions.keys - before.keys
        p("== against ${compare.path}: ${changed.size} of ${decisions.size} positions decide differently " +
          "(${changed.keys.map { k -> byKey.getValue(k).first.distinct }.toSet().size} distinct); " +
          "${missing.size} gone, ${added.size} new")
        val (synthChanged, corpusChanged) = changed.entries.partition { it.key.startsWith("synthetic#") }
        for ((k, v) in corpusChanged) {
            val note = byKey.getValue(k)
            p("  [${tagsOf(k)}] $k")
            p("    was  ${before[k]}")
            p("    now  $v")
            p("    ${note.third}; ${compact(note.first.grid)}")
        }
        if (synthChanged.isNotEmpty()) {
            p("  synthetic boards, ${synthChanged.size} changed, by meaning and old -> new:")
            synthChanged.groupingBy { (k, v) ->
                "[${tagsOf(k)}] ${kindOf(before.getValue(k))} | ${reasonOf(before.getValue(k))}" +
                    (heldOf(before.getValue(k))?.let { h -> ", dash held: $h" } ?: "") +
                    "  ->  ${kindOf(v)} | ${reasonOf(v)}"
            }.eachCount().entries.sortedBy { it.key }
                .forEach { p(String.format(Locale.ROOT, "    %5d  %s", it.value, it.key)) }
            p("  the first ${minOf(8, synthChanged.size)} of them:")
            for ((k, v) in synthChanged.take(8)) {
                p("    [${tagsOf(k)}] $k: ${before[k]}  ->  $v; ${compact(byKey.getValue(k).first.grid)}")
            }
        }
    }

    File(repo, "core/build").mkdirs()
    File(repo, "core/build/plannerProbe.txt").writeText(out.toString())
    File(repo, "core/build/plannerProbe.tsv").writeText(decisions.entries.joinToString("\n") { "${it.key}\t${it.value}" } + "\n")
}
