package io.github.digipr1me.digiautotap.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.system.exitProcess

/**
 * The numbers behind the readers of [Camp] (PLAN_CAMP.md C1), printed
 * before a threshold was chosen. Reads; writes only templates on `cut`.
 *
 *   gradlew :core:campProbe --no-daemon --args="all staging/camp"
 *   gradlew :core:campProbe --no-daemon --args="cut staging/camp/shop_x.png gate_blue=0 gate_pink=1"
 *   gradlew :core:campProbe --no-daemon --args="director"
 *
 * `all` prints, per picture, game_rect and what every reader and every
 * candidate list says: the title bar's share, the roof clumps, the plates,
 * the blue, grey, yellow and khaki buttons of the band, the card plates
 * with each card's glyph boxes and template scores, the dialog's squares,
 * the red shares. `mask=0.06:0.155:0.005` asks the readers again with the
 * dot and its plate masked in at every fy of that range. `cut` writes
 * `templates/camp_<item>.png` from the named cards (index in the grid's
 * reading order on that picture) of one 1080 x 1920 frame. `director`
 * runs Director.classify over every frame oracle/director.json names and
 * prints the frames that answer otherwise, and every frame a camp reader
 * answers on at all.
 */
fun main(args: Array<String>) {
    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    if (args.isEmpty()) {
        println("campProbe wants a mode: `all <pictures>`, `cut <frame> <item>=<card>...`, `director`")
        exitProcess(2)
    }
    val repo = File(System.getProperty("digiautotap.repo") ?: ".")
    when (args[0]) {
        "director" -> CampProbe.director(repo)
        "cut" -> CampProbe.cut(repo, args[1], args.drop(2))
        "all" -> {
            val places: List<Double?> = args.drop(1).firstOrNull { it.startsWith("mask=") }?.removePrefix("mask=")
                ?.split(':')?.map { it.toDouble() }
                ?.let { (a, b, step) -> generateSequence(a) { it + step }.takeWhile { it <= b + 1e-9 }.toList() }
                ?: listOf(null)
            val files = ArrayList<File>()
            for (arg in args.drop(1).filter { !it.startsWith("mask=") }) {
                val f = File(arg).let { if (it.isAbsolute) it else File(repo, arg) }
                if (f.isDirectory) files += f.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name }
                else files.add(f)
            }
            val icons = CampIcons(ClassPathAssets)
            println("all over ${files.size} pictures")
            for (file in files) {
                val raw = OracleFamilies.framed(Imgcodecs.imread(file.path), file.name)
                for (fy in places) {
                    val img = if (fy == null) raw else OracleFamilies.copy(raw).also { Dot.mask(it, MissionsProbe.dot(it, fy)) }
                    val at = if (fy == null) "" else "[fy %.3f] ".format(fy)
                    if (fy == null) CampProbe.all(img, icons, at + file.name)
                    else CampProbe.brief(img, icons, at + file.name)
                    if (img !== raw) img.release()
                }
                raw.release()
            }
        }
        else -> { println("no mode '${args[0]}'"); exitProcess(2) }
    }
}

object CampProbe {

    private fun f(x: Double) = "%.4f".format(x)
    private fun blob(b: Runner.Blob) = "fx0 ${f(b.fx0)} fy0 ${f(b.fy0)} w ${f(b.width)} h ${f(b.height)} cx ${f(b.cx)} cy ${f(b.cy)} fill ${f(b.fill)}"

    /** The readers' answers alone: what a masked frame costs. */
    fun brief(img: Mat, icons: CampIcons, name: String) {
        val tab = Camp.campTab(img)
        val screen = Camp.screen(img)
        val tent = Camp.tentWindow(img)
        val support = Camp.supportWindow(img)
        val shop = Camp.shopWindow(img, icons)
        val dialog = Camp.buyDialog(img)
        println("$name  tab=${tab != null} screen=${screen != null} tent=${tent != null} support=${support != null} " +
                "shop=${shop?.cards?.size} items=${shop?.cards?.count { it.item != null }} dialog=${dialog != null} " +
                "title ${f(Camp.titleShare(img))}")
    }

    fun all(img: Mat, icons: CampIcons, name: String) {
        val r = Dungeon.gameRect(img, Camp.MAP)
        val rw = Dungeon.gameRect(img, Camp.WINDOW)
        println("== $name  ${img.cols()}x${img.rows()} headroom ${Dungeon.headroom(img)} rect(bottom) ${r.x0},${r.y0} ${r.gw}x${r.gh} rect(middle) y0 ${rw.y0}")
        brief(img, icons, "   readers")
        // The main screen's nav labels, by their dx from the globe.
        val home = Dungeon.homeButton(img)
        if (home != null) {
            val camp = Explore.navTab(img, Camp.NAV_CAMP_DX)
            println("   nav: globe fx ${f(home.fx)} camp_tab=${camp?.let { "fx ${f(it.fx)} dx ${f(it.fx - home.fx)}" }} " +
                    "explore=${Explore.exploreTab(img)?.let { f(it.fx - home.fx) }} auto=${Dungeon.autoButton(img) != null}")
        }
        println("   title share ${f(Camp.titleShare(img))}  (at MIDDLE ${f(shareAt(img, Dungeon.Anchor.MIDDLE))}, TOP ${f(shareAt(img, Dungeon.Anchor.TOP))})")
        for (anchor in Dungeon.Anchor.values()) {
            if (anchor != Camp.MAP && Dungeon.headroom(img) == 0) continue
            for (b in Runner.blobs(img, Camp.ROOF_ORANGE, band = Camp.ROOF_BAND, minShare = Camp.ROOF_MIN_SHARE, anchor = anchor))
                println("   roof@$anchor ${blob(b)}")
        }
        for (p in Camp.plates(img)) println("   plate ${blob(p)}")
        for (b in Camp.buttonBlobs(img, Dungeon.BLUE, doubleArrayOf(0.10, 0.95), doubleArrayOf(0.03, 0.40), doubleArrayOf(0.02, 0.08)))
            println("   blue ${blob(b)} badge ${f(Camp.badgeShare(img, b))}")
        for (b in Camp.buttonBlobs(img, Camp.GREY)) println("   grey ${blob(b)}")
        for (b in Camp.buttonBlobs(img, Runner.CLAIM_YELLOW, doubleArrayOf(0.10, 0.95), doubleArrayOf(0.10, 0.40)))
            println("   yellow ${blob(b)} badge ${f(Camp.badgeShare(img, b))}")
        for (b in Camp.buttonBlobs(img, Camp.KHAKI, doubleArrayOf(0.10, 0.95), doubleArrayOf(0.10, 0.40)))
            println("   khaki ${blob(b)}")
        for (b in Camp.buttonBlobs(img, Camp.TURQUOISE, doubleArrayOf(0.10, 0.95), doubleArrayOf(0.10, 0.40)))
            println("   turquoise ${blob(b)} red ${f(redOf(img, b))} coin ${f(Camp.coinShare(img, b))}")
        for (b in Camp.squares(img)) println("   square ${blob(b)}")
        val tent = Camp.tentWindow(img)
        if (tent != null) println("   tent: ${tent.toOracle()}")
        val sw = Camp.supportWindow(img)
        if (sw != null) println("   support_window: ${sw.toOracle()}")
        val dialog = Camp.buyDialog(img)
        if (dialog != null) println("   buy_dialog: ${dialog.toOracle()}")
        val plates = Camp.cardPlates(img)
        val shop = Camp.shopWindow(img, icons)
        if (shop != null) println("   shop: at_top=${shop.atTop} at_bottom=${shop.atBottom} plate ${blob(shop.plate)} thumb ${shop.thumb?.let { blob(it) }}")
        for ((i, pair) in plates.withIndex()) {
            val (p, sold) = pair
            val card = shop?.cards?.getOrNull(i)
            val crop = Camp.iconCrop(img, p)
            val scores = crop?.let { icons.scores(it) }?.take(3)?.joinToString(" ") { "${it.first}=${f(it.second)}" }
            println("   card $i ${if (sold) "grey" else "white"} ${blob(p)} -> ${card?.let { "item=${it.item} left=${it.left} price=${it.price} affordable=${it.affordable} daily=${it.daily} tap ${f(it.target.fx)},${f(it.target.fy)}" }}")
            println("      scores $scores")
            val top = p.fy1 - Camp.CARD_H
            val leftCrop = cropAt(img, p.cx + Camp.LEFT_BOX[0] * p.width, p.cx + Camp.LEFT_BOX[1] * p.width, top + Camp.LEFT_BOX[2], top + Camp.LEFT_BOX[3])
            if (leftCrop != null) println("      left glyphs ${glyphBoxes(leftCrop, null)}")
            val priceCrop = cropAt(img, p.fx0 + Camp.PRICE_FROM * p.width, p.fx1 - 0.02 * p.width, p.fy0, p.fy1)
            if (priceCrop != null) println("      price glyphs dark ${glyphBoxes(priceCrop, Camp.PRICE_DARK)} pink ${glyphBoxes(priceCrop, Camp.PRICE_PINK)}")
            val dailyCrop = cropAt(img, p.cx + Camp.DAILY_BOX[0] * p.width, p.cx + Camp.DAILY_BOX[1] * p.width, top + Camp.DAILY_BOX[2], top + Camp.DAILY_BOX[3])
            if (dailyCrop != null) {
                val m = Cv.hsvMask(dailyCrop, Camp.DAILY_BLUE); println("      daily share ${f(Cv.share(m))}"); m.release()
            }
        }
        if (dialog == null && Camp.squares(img).size == 4) {
            val row = Camp.squares(img)
            val cy = row.map { it.cy }.average()
            val countCrop = cropAt(img, row[1].fx1 + 0.01, row[2].fx0 - 0.01, cy - 0.016, cy + 0.016)
            if (countCrop != null) println("   count glyphs ${glyphBoxes(countCrop, null)} red ${f(Camp.redShare(countCrop))}")
        }
        if (dialog != null) {
            val row = Camp.squares(img)
            val cy = row.map { it.cy }.average()
            val countCrop = cropAt(img, row[1].fx1 + 0.01, row[2].fx0 - 0.01, cy - 0.016, cy + 0.016)
            if (countCrop != null) println("   count glyphs ${glyphBoxes(countCrop, null)} red ${f(Camp.redShare(countCrop))}")
            val limitCrop = cropAt(img, Camp.LIMIT_BOX[0], Camp.LIMIT_BOX[1], cy + Camp.LIMIT_BOX[2], cy + Camp.LIMIT_BOX[3])
            if (limitCrop != null) println("   limit glyphs ${glyphBoxes(limitCrop, null)}")
        }
        println("   reward_sheet=${Dungeon.rewardSheet(img)} classify=${Director.classify(img)}")
    }

    private fun shareAt(img: Mat, anchor: Dungeon.Anchor): Double {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, anchor)
        val b = Camp.TITLE_BOX
        val sub = Py.crop(img, maxOf(0, Py.int(y0 + b[2] * gh)), Py.int(y0 + b[3] * gh),
                          maxOf(0, Py.int(x0 + b[0] * gw)), Py.int(x0 + b[1] * gw)) ?: return 0.0
        val m = Cv.hsvMask(sub, Camp.TITLE_BLUE)
        return Cv.share(m).also { m.release() }
    }

    private fun cropAt(img: Mat, fx0: Double, fx1: Double, fy0: Double, fy1: Double): Mat? {
        val (x0, y0, gw, gh) = Dungeon.gameRect(img, Camp.WINDOW)
        return Py.crop(img, maxOf(0, Py.int(y0 + fy0 * gh)), Py.int(y0 + fy1 * gh),
                       maxOf(0, Py.int(x0 + fx0 * gw)), Py.int(x0 + fx1 * gw))
    }

    private fun redOf(img: Mat, b: Runner.Blob): Double = cropAt(img, b.fx0, b.fx1, b.fy0, b.fy1)?.let { Camp.redShare(it) } ?: 0.0

    private fun glyphBoxes(crop: Mat, colour: Dungeon.Hsv?): String {
        val g = Camp.glyphs(crop, colour)
        val rows = g.mask.rows()
        val s = g.boxes.joinToString(" ") { "[x${it[0]} w${it[2]} h${it[3]} (${f(it[3] / rows.toDouble())}) ${Dungeon.readDigit(g.mask, it)}]" }
        g.mask.release()
        return "rows $rows: $s"
    }

    /** `cut <frame> <item>=<card index>...`: the icon's box of each named card into templates/camp_<item>.png. */
    fun cut(repo: File, frame: String, items: List<String>) {
        val file = File(frame).let { if (it.isAbsolute) it else File(repo, frame) }
        val img = OracleFamilies.framed(Imgcodecs.imread(file.path), file.name)
        val plates = Camp.cardPlates(img)
        for (spec in items) {
            val (item, index) = spec.split('=')
            val p = plates[index.toInt()].first
            val crop = Camp.iconCrop(img, p) ?: throw IllegalStateException("no crop for $spec")
            val out = File(repo, "templates/camp_$item.png")
            Imgcodecs.imwrite(out.path, crop)
            println("wrote $out  ${crop.cols()}x${crop.rows()} from card $index (${f(p.cx)},${f(p.cy)})")
        }
    }

    /** Director.classify over the oracle's frames, against the file: every frame that answers otherwise, and every frame a camp reader answers on. */
    fun director(repo: File) {
        val file = File(repo, "oracle/director.json")
        val frames = Json.parseToJsonElement(file.readText()).jsonObject["frames"]!!.jsonObject
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(Runtime.getRuntime().availableProcessors(), 6))
        val lines = java.util.concurrent.ConcurrentHashMap<String, String>()
        val moved = java.util.concurrent.atomic.AtomicInteger()
        val jobs = frames.map { (rel, entry) ->
            pool.submit {
                val img = OracleFamilies.read(File(repo, rel))
                try {
                    val was = ((entry as JsonObject)["classify"] as JsonObject)
                    val now = Director.classify(img)
                    val same = was["screen"]!!.jsonPrimitive.content == now.screen && was["by"]!!.jsonPrimitive.content == now.by
                    if (!same) moved.incrementAndGet()
                    val any = Camp.screen(img) != null || Camp.tentWindow(img) != null || Camp.supportWindow(img) != null ||
                        Camp.shopWindow(img) != null || Camp.buyDialog(img) != null || Camp.campTab(img) != null
                    if (!same || any)
                        lines[rel] = "$rel  was ${was["screen"]!!.jsonPrimitive.content} (by ${was["by"]!!.jsonPrimitive.content})  now $now" +
                            "  tab=${Camp.campTab(img) != null} screen=${Camp.screen(img) != null} tent=${Camp.tentWindow(img) != null}" +
                            " support=${Camp.supportWindow(img) != null} shop=${Camp.shopWindow(img) != null} dialog=${Camp.buyDialog(img) != null}"
                } finally {
                    img.release()
                }
            }
        }
        jobs.forEach { it.get() }
        pool.shutdown()
        lines.toSortedMap().values.forEach(::println)
        println("director over ${frames.size} frames of the oracle: ${moved.get()} answer otherwise, ${lines.size} printed")
    }
}
