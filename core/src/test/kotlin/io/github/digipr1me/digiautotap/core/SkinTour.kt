package io.github.digipr1me.digiautotap.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * The fifteen partners on the World Search board (PLAN_WORLD_SEARCH_FORMATE.md
 * 13, B7): the figure on the board is the raised Digimon, and every one of
 * them is another sprite. This drives the game on one LDPlayer instance from
 * the JVM, with the app's core stopped, so that one hand taps and no dot is in
 * the picture:
 *
 *   gradlew :core:skinTour --args="list"
 *   gradlew :core:skinTour --args="raise 3"
 *   gradlew :core:skinTour --args="pass 3 15"
 *   gradlew :core:skinTour --args="skin 3 15"      raise 3, then pass 3 15
 *   gradlew :core:skinTour --args="home"
 *
 * Options anywhere in the line: `serial=emulator-5554` (the default), `dir=b7a`
 * (where the board frames go, in the repository root), `eager=true|false`
 * (`mini_dash_eager`, default true as on instance 0).
 *
 * Nothing here is a second way to do what the app does. The capture is adb
 * ([AdbCapture]: `screencap -p` as a Mat, `input tap`, `input swipe`, `input
 * keyevent 4`, `mCurrentFocus`), and over it run the app's own pieces
 * unchanged: [BondTour.enter] and [BondTour.raise] to change the partner, and
 * [WorldSearchSkill.run] with `navigate = true` for the pass -- the same
 * readers, the same planner, the same verification at the counters, only
 * `grab` through screencap. The one thing added to the tour is a look: the
 * frame of the "Raise <name>?" prompt is written out when `recognise` answers
 * it, because the name is on it and nothing reads names; a person looks.
 *
 * `pass` keeps the skill's own start frame (`board_start`, the quiet frame
 * the run found its figure on) as
 * `<dir>/board_skin<NN>_<W>x<H>_none0_<hhmmss>.png`, NN the cell index given
 * to `raise`, and prints on it what B7's table asks: `calibrate`,
 * `find_figure` (way and value), the body share of every cell, the figure
 * template's score, the eye blobs, `read_grid` with and without the figure,
 * the cells around the figure, `read_counters`. Then the run's log.
 *
 * A file `core/build/skinTour.stop` is the main switch: while it exists the
 * tour and the skill stop between two actions. Nothing here writes into
 * `corpus/`.
 */
object SkinTour {

    private val repo = File(System.getProperty("digiautotap.repo") ?: "..")
    private val adb = System.getProperty("digiautotap.adb") ?: "adb"
    private val stopFile = File(repo, "core/build/skinTour.stop")
    private val work = File(repo, "core/build/skinTour").also { it.mkdirs() }
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val stamp = DateTimeFormatter.ofPattern("HHmmss")

    private lateinit var out: PrintStream

    fun say(line: String) {
        val l = "${LocalTime.now().format(clock)}  $line"
        println(l)
        out.println(l)
        out.flush()
    }

    fun on(): Boolean = !stopFile.exists()

    fun hhmmss(): String = LocalTime.now().format(stamp)

    /** The adb capture. Coordinates are the display's: the service cuts nothing on these formats. */
    class AdbCapture(private val serial: String) : Capture {
        private fun run(vararg args: String): ByteArray {
            val p = ProcessBuilder(listOf(adb, "-s", serial) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val bytes = p.inputStream.readBytes()
            p.waitFor()
            return bytes
        }

        var grabs = 0

        /** The last frame grabbed, as bytes, and the one that stood before the last tap. */
        private var lastPng: ByteArray? = null
        var beforeTap: ByteArray? = null
            private set
        var lastTap: Pair<Int, Int>? = null
            private set

        override fun grab(): Mat {
            val bytes = run("exec-out", "screencap", "-p")
            if (bytes.isEmpty()) throw CaptureError("screencap gave nothing")
            val buf = MatOfByte(*bytes)
            val img = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
            buf.release()
            if (img.empty()) throw CaptureError("screencap was not a picture (${bytes.size} bytes)")
            grabs += 1
            lastPng = bytes
            return img
        }

        override fun tap(x: Int, y: Int) {
            beforeTap = lastPng
            lastTap = x to y
            run("shell", "input", "tap", x.toString(), y.toString())
        }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) {
            run("shell", "input", "swipe", "$x1", "$y1", "$x2", "$y2", "$ms")
        }

        override fun back() {
            run("shell", "input", "keyevent", "4")
        }

        override fun inFront(): String? {
            val text = String(run("shell", "dumpsys window | grep mCurrentFocus"))
            // mCurrentFocus=Window{b22bb15 u0 com.bandainamcoent.dgup_ww/com....Activity}
            val m = Regex("""u0 ([\w.]+)/""").find(text) ?: return null
            return m.groupValues[1]
        }
    }

    /**
     * The app's bond tour, with one look added: the Raise prompt's frame is
     * written out when `recognise` answers it, so the name on it can be read
     * by a person. Nothing else is overridden.
     */
    class Tour(cap: Capture, private val cell: () -> Int?) :
        BondTour(cap, log = { say(it) }, on = { on() },
                 keep = { img, tag -> Imgcodecs.imwrite(File(work, "tour_${tag}_${hhmmss()}.png").path, img) }) {
        var prompts = ArrayList<String>()
        override fun recognise(img: Mat): Dungeon.Recognition {
            val r = super.recognise(img)
            if (r.state == Dungeon.EXIT && r.exitKind == "party") {
                val f = File(work, "raise_prompt_%02d_%s.png".format(cell() ?: -1, hhmmss()))
                Imgcodecs.imwrite(f.path, img)
                prompts.add(f.path)
                say("  [skinTour] the Raise prompt is kept as ${f.path}")
            }
            return r
        }
    }

    private fun f(v: Double?): String = if (v == null) "None" else "%.3f".format(v)

    /** `list`: the Partner page, who is raised and how many cells, home. */
    fun list(cap: AdbCapture): Pair<Int?, Int> {
        val tour = Tour(cap) { null }
        val (raised, total) = tour.enter()
        val img = cap.grab()
        Imgcodecs.imwrite(File(work, "partner_page_${hhmmss()}.png").path, img)
        val cells = Bond.cells(img)
        say("list: raised $raised, cells $total")
        cells?.forEachIndexed { i, (fx, fy) ->
            say("  cell %2d at fx %.4f fy %.4f%s".format(i, fx, fy, if (i == raised) "  <- raised" else ""))
        }
        img.release()
        val home = tour.goHome()
        say("list: home $home")
        return raised to total
    }

    /** `raise N`: the tour's own enter and raise, then home, then the page again to prove it took. */
    fun raise(cap: AdbCapture, n: Int): Boolean {
        val tour = Tour(cap) { n }
        val (before, total) = tour.enter()
        say("raise $n: before $before of $total")
        if (before == null) {
            tour.goHome()
            return false
        }
        if (before == n) {
            say("raise $n: already raised")
            tour.goHome()
            return true
        }
        val ok = tour.raise(n)
        say("raise $n: raise() answered $ok")
        if (!tour.goHome()) {
            say("raise $n: could not get home")
            return false
        }
        val (after, _) = tour.enter()
        say("raise $n: the page reads $after raised")
        tour.goHome()
        return ok && after == n
    }

    // ------------------------------------------------------------------------
    // The readings on the start frame
    // ------------------------------------------------------------------------
    private val LETTERS = mapOf(
        "ticket_orange" to 'O', "ticket_green" to 'G', "ticket_pink" to 'K', "claw" to 'C',
        "paw" to 'P', "fireball" to 'F', "pyramid" to 'A', "arrow" to '>', "?" to '?')

    private fun gridLine(grid: List<List<String?>>, fig: Figure?): String =
        grid.mapIndexed { r, row ->
            row.mapIndexed { c, name ->
                if (fig != null && fig.row == r && fig.col == c && name == null) '@'
                else if (name == null) '.' else LETTERS[name] ?: '#'
            }.joinToString("")
        }.joinToString("/")

    /** The figure template's best score over the board window, as findFigure computes it. */
    private fun figureTemplateScore(img: Mat, calib: Calib, templates: Map<String, Mat>): Double? {
        val tpl0 = templates["figure"] ?: return null
        val gx = Py.int(calib.gridX0)
        val gy = Py.int(calib.gridY0)
        val gw = Py.int(Vision.COLS * calib.cellW)
        val gh = Py.int(Vision.ROWS * calib.cellH)
        val x1 = maxOf(0, gx - Py.int(0.60 * calib.cellW))
        val y1 = maxOf(0, gy - Py.int(0.40 * calib.cellH))
        val board = Py.crop(img, y1, gy + gh, x1, gx + gw) ?: return null
        val factor = calib.cellW / 87.5
        val tpl = if (Math.abs(factor - 1.0) < 0.02) tpl0 else Mat().also {
            Imgproc.resize(tpl0, it, Size(maxOf(4, Py.int(tpl0.cols() * factor)).toDouble(),
                                          maxOf(4, Py.int(tpl0.rows() * factor)).toDouble()))
        }
        if (board.rows() < tpl.rows() || board.cols() < tpl.cols()) return null
        val res = Mat()
        Imgproc.matchTemplate(board, tpl, res, Imgproc.TM_CCOEFF_NORMED)
        val m = Core.minMaxLoc(res)
        res.release()
        return m.maxVal
    }

    /**
     * The pyramid template's score in one cell, as readGrid computes it (the
     * same patch, the upper two thirds in the bottom row, the same scaling);
     * readGrid hands back a score only for a cell that read. The bar is 0.74,
     * 0.68 in the bottom row.
     */
    private fun pyramidScore(vision: Vision, img: Mat, calib: Calib, templates: Map<String, Mat>,
                             r: Int, c: Int): Double? = templateScore(vision, img, calib, templates, r, c, "pyramid")

    private fun templateScore(vision: Vision, img: Mat, calib: Calib, templates: Map<String, Mat>,
                              r: Int, c: Int, name: String): Double? {
        val patch = vision.searchPatch(img, calib, r, c) ?: return null
        val whole = templates[name] ?: return null
        val tpl0 = if (r == Vision.ROWS - 1 && name == "pyramid") vision.upperPart(whole) else whole
        val factor = calib.cellW / 87.5
        val tpl = if (Math.abs(factor - 1.0) < 0.02) tpl0 else Mat().also {
            Imgproc.resize(tpl0, it, Size(maxOf(4, Py.int(tpl0.cols() * factor)).toDouble(),
                                          maxOf(4, Py.int(tpl0.rows() * factor)).toDouble()))
        }
        if (tpl.rows() > patch.rows() || tpl.cols() > patch.cols()) return null
        val res = Mat()
        Imgproc.matchTemplate(patch, tpl, res, Imgproc.TM_CCOEFF_NORMED)
        val m = Core.minMaxLoc(res)
        res.release()
        return m.maxVal
    }

    /**
     * readGrid's contest in one cell, as it runs it: the candidates it asks
     * (every power-up, the pyramid and the arrow where the cell is
     * coloured, else the pyramid alone), each with its score and bar, and
     * the winner by margin. An arrow that wins is dropped as "no object".
     */
    private fun contest(vision: Vision, img: Mat, calib: Calib, templates: Map<String, Mat>,
                        r: Int, c: Int): String {
        val colourful = vision.hasObjectColour(img, calib, r, c)
        val names = if (colourful) Vision.POWERUPS + "pyramid" + "arrow" else listOf("pyramid")
        val parts = names.mapNotNull { name ->
            val v = templateScore(vision, img, calib, templates, r, c, name) ?: return@mapNotNull null
            var need = Vision.THRESHOLDS[name] ?: Vision.DEFAULT_THRESHOLD
            if (r == Vision.ROWS - 1) need -= 0.06
            Triple(name, v, need)
        }
        val best = parts.maxByOrNull { it.second - it.third }
        // The arrow's score also where readGrid does not ask it (an uncoloured
        // cell), to see what it would say of a pyramid.
        val arrow = if (colourful) "" else " (arrow not asked, %.2f)".format(
            templateScore(vision, img, calib, templates, r, c, "arrow") ?: 0.0)
        return "best %s %.3f/%.2f".format(best?.first ?: "-", best?.second ?: 0.0, best?.third ?: 0.0) +
            " [" + parts.joinToString(" ") { "%s %.2f".format(it.first.replace("ticket_", "t_"), it.second) } + "]" + arrow
    }

    /** The eye blobs as eyeBlobs finds them (yellow inside a dilated dark body), with area and cell. */
    private fun eyeBlobs(img: Mat, calib: Calib): List<String> {
        val gx = Py.int(calib.gridX0)
        val gy = Py.int(calib.gridY0)
        val gw = Py.int(Vision.COLS * calib.cellW)
        val gh = Py.int(Vision.ROWS * calib.cellH)
        val x1 = maxOf(0, gx - Py.int(0.60 * calib.cellW))
        val y1 = maxOf(0, gy - Py.int(0.40 * calib.cellH))
        val board = Py.crop(img, y1, gy + gh, x1, gx + gw) ?: return emptyList()
        val hsv = Cv.hsv(board)
        val eyes = Mat()
        val body = Mat()
        val lo = Vision.HSV_EYE_LO
        val hi = Vision.HSV_EYE_HI
        val blo = Vision.HSV_BODY_LO
        val bhi = Vision.HSV_BODY_HI
        Core.inRange(hsv, Scalar(lo[0].toDouble(), lo[1].toDouble(), lo[2].toDouble()),
                     Scalar(hi[0].toDouble(), hi[1].toDouble(), hi[2].toDouble()), eyes)
        Core.inRange(hsv, Scalar(blo[0].toDouble(), blo[1].toDouble(), blo[2].toDouble()),
                     Scalar(bhi[0].toDouble(), bhi[1].toDouble(), bhi[2].toDouble()), body)
        val grown = Mat()
        Imgproc.dilate(body, grown, Mat.ones(9, 9, CvType.CV_8U))
        Core.bitwise_and(eyes, grown, eyes)
        val labels = Mat()
        val stats = Mat()
        val cent = Mat()
        val n = Imgproc.connectedComponentsWithStats(eyes, labels, stats, cent, 8)
        val minArea = maxOf(6, Py.int(0.0008 * calib.cellW * calib.cellH))
        val maxArea = Py.int(0.05 * calib.cellW * calib.cellH)
        val outList = ArrayList<String>()
        for (i in 1 until n) {
            val s = IntArray(5).also { stats.get(i, 0, it) }
            val c = DoubleArray(2).also { cent.get(i, 0, it) }
            if (s[4] !in minArea..maxArea) continue
            val px = x1 + c[0]
            val py = y1 + c[1]
            val col = Math.floor((px - calib.gridX0) / calib.cellW).toInt()
            val row = Math.floor((py - calib.gridY0) / calib.cellH).toInt()
            outList.add("area %d at r%dc%d (%.0f,%.0f)".format(s[4], row + 1, col + 1, px, py))
        }
        listOf(hsv, eyes, body, grown, labels, stats, cent).forEach { it.release() }
        return outList
    }

    /** Everything B7's table wants from the quiet start frame. */
    fun readings(vision: Vision, img: Mat) {
        val calib = try {
            vision.calibrate(img)
        } catch (e: CalibrationError) {
            say("[readings] calibrate: ${e.message}")
            return
        }
        val templates = vision.loadTemplates()
        val fig = vision.findFigure(img, calib, templates)
        say("[readings] frame %d x %d, cell %.2f x %.2f, card %s".format(
            img.cols(), img.rows(), calib.cellW, calib.cellH, calib.card.toList()))
        say("[readings] find_figure: " + (if (fig == null) "None" else
            "r%dc%d how %s score %s".format(fig.row + 1, fig.col + 1, fig.how, f(fig.score))))
        val body = List(Vision.ROWS) { r -> List(Vision.COLS) { c -> vision.figureBodyFraction(img, calib, r, c) } }
        say("[readings] body share per cell (per cent, rows 1-5):")
        for (r in 0 until Vision.ROWS) {
            say("[readings]   r%d  %s".format(r + 1, body[r].joinToString(" ") { "%5.1f".format(it) }))
        }
        val ranked = (0 until 25).sortedByDescending { body[it / 5][it % 5] }.take(3)
        say("[readings] body best: " + ranked.joinToString(", ") {
            "r%dc%d %.1f".format(it / 5 + 1, it % 5 + 1, body[it / 5][it % 5]) })
        say("[readings] figure template score: ${f(figureTemplateScore(img, calib, templates))} (bar 0.62)")
        val eyes = eyeBlobs(img, calib)
        val perCell = eyes.groupingBy { Regex("""r-?\d+c-?\d+""").find(it)!!.value }.eachCount()
        say("[readings] eye blobs: ${eyes.size}" + (if (eyes.isEmpty()) "" else " -- by cell " +
            perCell.entries.joinToString(", ") { "${it.key} ${it.value}" }))
        val board = vision.boardTemplates(templates)
        val (plain, plainScores) = vision.readGrid(img, calib, board)
        val (withFig, _) = vision.readGrid(img, calib, board, figure = fig)
        say("[readings] read_grid: ${gridLine(plain, fig)}   (with figure: ${gridLine(withFig, fig)})")
        val occupied = ArrayList<String>()
        for (r in 0 until Vision.ROWS) for (c in 0 until Vision.COLS) {
            val n = plain[r][c] ?: continue
            occupied.add("r%dc%d %s %.3f".format(r + 1, c + 1, n, plainScores[r][c]))
        }
        say("[readings] occupied: " + occupied.joinToString(", "))
        if (fig != null) {
            val around = ArrayList<String>()
            for (dr in -1..1) for (dc in -1..1) {
                val r = fig.row + dr
                val c = fig.col + dc
                if (r !in 0 until Vision.ROWS || c !in 0 until Vision.COLS) continue
                val name = plain[r][c]
                val colour = vision.hasObjectColour(img, calib, r, c)
                val tag = if (dr == 0 && dc == 0) " (figure)" else ""
                around.add("r%dc%d%s %s colour %s body %.1f pyramid %s".format(r + 1, c + 1, tag,
                    name ?: "-", if (colour) "yes" else "no", body[r][c],
                    f(pyramidScore(vision, img, calib, templates, r, c))))
            }
            say("[readings] around the figure: " + around.joinToString("; "))
            for (dr in -1..1) for (dc in -1..1) {
                val r = fig.row + dr
                val c = fig.col + dc
                if (r !in 0 until Vision.ROWS || c !in 0 until Vision.COLS) continue
                say("[readings]   contest r%dc%d: %s".format(r + 1, c + 1, contest(vision, img, calib, templates, r, c)))
            }
            val flagged = around.filterIndexed { _, s -> !s.contains("(figure)") && !s.contains(" - colour") }
            say("[readings] neighbours reading '?' or an object: " +
                (if (flagged.isEmpty()) "none" else flagged.joinToString("; ")))
        }
        val counters = vision.readCounters(img, calib)
        say("[readings] read_counters: " + counters.entries.joinToString(", ") { "${it.key} ${it.value ?: "None"}" } +
            " -- ${counters.values.count { it != null }}/7")
    }

    /** `pass N MAX`: home, then the skill's own run() with navigate on, its start frame kept and read. */
    fun pass(cap: AdbCapture, vision: Vision, n: Int, max: Int, dir: String, eager: Boolean): String? {
        val home = Tour(cap) { n }.goHome()
        say("pass $n: home $home")
        var kept: String? = null
        val folder = File(repo, dir).also { it.mkdirs() }
        val keep: (Mat, String) -> Unit = { img, tag ->
            val t = hhmmss()
            if (tag.startsWith("board_start")) {
                val f = File(folder, "board_skin%02d_%dx%d_none0_%s.png".format(n, img.cols(), img.rows(), t))
                Imgcodecs.imwrite(f.path, img)
                kept = f.path
                say("[skinTour] start frame kept as ${f.path}")
                readings(vision, img)
            } else {
                val f = File(folder, "skin%02d_%s_%dx%d_%s.png".format(n, tag, img.cols(), img.rows(), t))
                Imgcodecs.imwrite(f.path, img)
                say("[skinTour] $tag kept as ${f.path}")
                if (tag.startsWith("no_start")) readings(vision, img)
                if (tag.startsWith("resync")) {
                    // The last frame grabbed before the tap that did nothing:
                    // what the planner's map was built from.
                    cap.beforeTap?.let { png ->
                        val b = File(folder, "skin%02d_%s_before_%dx%d_%s.png".format(n, tag, img.cols(), img.rows(), t))
                        b.writeBytes(png)
                        say("[skinTour] the frame before the tap at ${cap.lastTap} kept as ${b.path}")
                        val m = Imgcodecs.imdecode(MatOfByte(*png), Imgcodecs.IMREAD_COLOR)
                        readings(vision, m)
                        m.release()
                    }
                }
            }
        }
        val settings = WorldSearchSettings(maxActions = max, navigate = true, dashEager = eager)
        val skill = WorldSearchSkill(cap, vision, { settings }, log = { say(it) }, keep = keep, on = { on() })
        val t0 = System.nanoTime()
        val outcome = skill.run()
        say("pass $n: outcome ${outcome.result} -- ${outcome.why}; counts ${skill.lastCounts}; " +
            "debug ${skill.debugCounts()}; %.0f s, %d grabs".format((System.nanoTime() - t0) / 1e9, cap.grabs))
        return kept
    }

    fun main(args: Array<String>) {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
        val opts = args.filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }
        val words = args.filter { "=" !in it }
        val serial = opts["serial"] ?: "emulator-5554"
        val dir = opts["dir"] ?: "b7"
        val eager = (opts["eager"] ?: "true").toBoolean()
        out = PrintStream(File(work, "skinTour_${hhmmss()}.txt"))
        val cap = AdbCapture(serial)
        val front = if (words.firstOrNull() == "read") Game.KNOWN else cap.inFront()
        say("skinTour ${args.joinToString(" ")} -- serial $serial, in front $front, adb $adb")
        if (front != Game.KNOWN) {
            say("the game is not in front; not touching anything")
            exitProcess(2)
        }
        val vision = Vision(ClassPathAssets)
        when (words.firstOrNull()) {
            "list" -> list(cap)
            "raise" -> raise(cap, words[1].toInt())
            "pass" -> pass(cap, vision, words[1].toInt(), words[2].toInt(), dir, eager)
            "skin" -> {
                val n = words[1].toInt()
                if (raise(cap, n)) pass(cap, vision, n, words[2].toInt(), dir, eager)
                else say("skin $n: the raise did not take, no pass")
            }
            "home" -> say("home: ${Tour(cap) { null }.goHome()}")
            "read" -> {
                // A stored picture, read the way pass reads its start frame.
                for (name in words.drop(1)) {
                    val file = File(name).let { if (it.isAbsolute) it else File(repo, name) }
                    say("read ${file.path}")
                    readings(vision, Imgcodecs.imread(file.path))
                }
            }
            else -> {
                say("commands: list | raise N | pass N MAX | skin N MAX | home | read <png>")
                exitProcess(2)
            }
        }
        out.close()
    }
}

fun main(args: Array<String>) = SkinTour.main(args)
