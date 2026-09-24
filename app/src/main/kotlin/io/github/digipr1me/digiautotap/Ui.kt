package io.github.digipr1me.digiautotap

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.digipr1me.digiautotap.core.HelperState

/**
 * The pieces every page is built out of, drawn from [Theme]'s names and from
 * nothing else -- a page that reached for a value of its own is what
 * `ThemeTest` fails on.
 *
 * Framework views in code, no AndroidX and no layout files, as the shell was
 * built (PLAN_ANDROID_5_SHELL.md 5.1). Where a platform widget cannot be
 * given the design's shape -- the segmented control, the switch, the chips
 * -- it is drawn out of a TextView and a rounded drawable instead of being
 * fought with.
 *
 * Since 2026-09-21 the shape is the console design's (the design session of
 * that evening, direction D): 8 dp corners rather than 14, panels rather
 * than cards, a value in monospace wherever the app is reporting rather
 * than explaining, and the tab bar's four text characters replaced by
 * drawables. Three numbers carry that design and are here once each:
 * [RADIUS], [PAD] and the sizes in [mono].
 */
class Ui(val c: Context) {

    val p: Palette = Theme.of(c)

    fun dp(v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    companion object {
        /** A panel's corner. Anything smaller than a panel takes one less. */
        const val RADIUS = 8

        /** A panel's own padding, and the page's margin around it. */
        const val PAD = 12

        /**
         * The main switch's height, and the mode button's beside it. It was
         * 46 dp on a row of its own; the one-page layout of 2026-09-22 put
         * the mode next to it and took 8 dp off both, so that the status
         * panel is a sentence and one row of buttons.
         */
        const val SWITCH_H = 38
    }

    // --- shapes ---------------------------------------------------------

    fun round(fill: Int, stroke: Int? = null, radius: Int = RADIUS) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(maxOf(1, dp(1)), stroke)
    }

    private fun pressable(normal: Int, pressed: Int, radius: Int, stroke: Int? = null) =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), round(pressed, stroke, radius))
            addState(intArrayOf(), round(normal, stroke, radius))
        }

    // --- boxes ----------------------------------------------------------

    fun column() = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun row() = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }

    /** A panel: SURFACE on BG, a LINE border, 8 dp corners. */
    fun card(): LinearLayout = column().apply {
        background = round(p.SURFACE, p.LINE)
        setPadding(dp(PAD), dp(10), dp(PAD), dp(10))
    }

    /** A list of rows in one panel, the Skills tab's shape. */
    fun listBox(): LinearLayout = column().apply {
        background = round(p.SURFACE, p.LINE)
        clipToOutline = true
    }

    fun divider(): View = View(c).apply {
        setBackgroundColor(p.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1)))
    }

    /** The lighter rule between two rows of the same panel. */
    fun hairline(): View = View(c).apply {
        setBackgroundColor(p.SURFACE_HOVER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1)))
    }

    fun space(height: Int): View = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    /** Width 0, weight 1: the part of a row that takes what is left. */
    fun grow(v: View): View = v.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    // --- text -----------------------------------------------------------

    private fun text(t: CharSequence, size: Float, colour: Int, bold: Boolean = false) =
        TextView(c).apply {
            text = t
            textSize = size
            setTextColor(colour)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    /**
     * A value the app is reporting rather than a sentence it is saying: the
     * screen it reads, a chip, a count, the steps of the chain. Monospace
     * is not decoration here -- it is what keeps a column of numbers a
     * column while they change under the player's eyes.
     */
    fun mono(t: CharSequence, size: Float = 11f, colour: Int = p.TEXT, bold: Boolean = false) =
        text(t, size, colour, bold).apply { typeface = Typeface.MONOSPACE }

    /** A panel's heading: monospace, small, spaced and upper case. */
    fun eyebrow(t: String) = mono(t, 10f, p.TEXT_2, bold = true).apply {
        isAllCaps = true
        letterSpacing = 0.14f
        setPadding(0, 0, 0, dp(6))
    }

    fun title(t: String) = text(t, 16f, p.TEXT, bold = true)

    fun body(t: CharSequence) = text(t, 13f, p.TEXT)

    fun hint(t: CharSequence) = text(t, 12f, p.TEXT_2)

    fun statusLine(t: CharSequence) = text(t, 15f, p.TEXT, bold = true)

    /** The one line in amber a missing ask writes under the status. */
    fun warnLine(t: CharSequence) = mono(t, 11f, p.PAUSE)

    fun name(t: CharSequence) = text(t, 14f, p.TEXT)

    /**
     * The face the tasks are named in, and the app's own name in the bar:
     * Chakra Petch SemiBold, the one face the app carries (res/font, with
     * its licence beside it in res/raw; About names it). Chosen by the
     * player on 2026-09-22 over the system face -- "a bit more digital" --
     * and over Oxanium and Share Tech Mono, from a strip of the seven
     * names in each. It names things and nothing else: a sentence, a value
     * and a button stay in the system face and in monospace, where they
     * were.
     */
    val face: Typeface by lazy { c.resources.getFont(R.font.chakra_petch_semibold) }

    fun taskName(t: CharSequence) = text(t, 15f, p.TEXT).apply {
        typeface = face
        letterSpacing = 0.01f
    }

    fun barTitle(t: CharSequence) = text(t, 18f, p.TEXT).apply {
        typeface = face
        letterSpacing = 0.01f
    }

    fun sub(t: CharSequence, amber: Boolean = false) =
        mono(t, 10f, if (amber) p.PAUSE else p.TEXT_2)

    /** The blue box: what is happening now, never a setting (Theme.kt, STATE_*). */
    fun answer(t: CharSequence) = text(t, 12f, p.STATE_FG).apply {
        background = round(p.STATE_BG, p.STATE_EDGE, radius = 6)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    /**
     * One reading of the status panel: a monospace key of a fixed width and
     * its value beside it. The width is the key column's, not the longest
     * key's, so a value that changes does not move the one under it.
     */
    fun field(key: String, value: CharSequence): LinearLayout = row().apply {
        setPadding(0, dp(2), 0, dp(2))
        addView(mono(key, 11f, p.TEXT_2).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(grow(mono(value, 11f, p.TEXT)))
    }

    // --- chips and the status ---------------------------------------------

    /** A small square-shouldered chip: OK, MISSING, a state, a count. */
    fun chip(label: String, fg: Int, bg: Int, edge: Int? = null) =
        mono(label, 10f, fg, bold = true).apply {
            isAllCaps = true
            letterSpacing = 0.1f
            background = round(bg, edge, radius = 5)
            setPadding(dp(7), dp(3), dp(7), dp(3))
        }

    fun okChip() = chip("ok", p.PILL_OK_FG, p.PILL_OK_BG, p.PILL_OK_EDGE)

    fun missingChip() = chip("missing", p.PILL_PAUSE_FG, p.PILL_PAUSE_BG, p.PILL_PAUSE_EDGE)

    /**
     * What DigiAutotap is doing, in the app bar and at the head of a skill's
     * page: the state's own word with its dot, drawn as a chip rather than
     * as the round pill the first design had.
     */
    fun pill(state: HelperState, small: Boolean = false) =
        chip(state.word, state.pillFg(p), state.pillBg(p), state.pillEdge(p)).apply {
            if (!small) textSize = 11f
            val size = dp(if (small) 6 else 7)
            setCompoundDrawablesWithIntrinsicBounds(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(state.pillFg(p))
                setSize(size, size)
            }, null, null, null)
            compoundDrawablePadding = dp(6)
        }

    /** The nav dot of the Skills list; a halo says this one is working. */
    fun navDot(colour: Int, halo: Boolean): View {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            // A quarter of the dot's own colour, derived from it rather than
            // written down, so the halo cannot end up a shade of its own.
            setColor(if (halo) Color.argb(64, Color.red(colour), Color.green(colour),
                                          Color.blue(colour)) else Color.TRANSPARENT)
        }
        val core = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colour)
        }
        val layers = LayerDrawable(arrayOf(ring, core))
        layers.setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
        return View(c).apply {
            background = layers
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
        }
    }

    // --- icons ------------------------------------------------------------

    /**
     * A drawable in a colour of the palette's. Every icon in the app is
     * white in its file and tinted here, which is the same rule the colours
     * themselves follow: one place writes the value.
     */
    fun icon(res: Int, size: Int, colour: Int): ImageView = ImageView(c).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(colour)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun chevron(res: Int = R.drawable.ic_chevron) = icon(res, 16, p.TEXT_2)

    // --- buttons ----------------------------------------------------------

    /**
     * The main switch's button: one line, thumb-sized, PRIMARY.
     *
     * A row and not a TextView with a compound drawable, because a compound
     * drawable is drawn at the view's own edge and not beside the words: on
     * a button as wide as the page that puts the icon an inch from the
     * label it belongs to.
     */
    fun bigButton(label: String, glyph: Int?, onClick: () -> Unit): LinearLayout =
        row().apply {
            gravity = Gravity.CENTER
            background = pressable(p.PRIMARY, p.PRIMARY_ACTIVE, radius = RADIUS)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(SWITCH_H))
            if (glyph != null) addView(icon(glyph, 17, p.ON_PRIMARY).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(9)
            })
            addView(text(label, 15f, p.ON_PRIMARY, bold = true))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * An icon in the app bar, 32 dp: Settings, since the one-page layout of
     * 2026-09-22 took the tab bar away and left it as a button (More, beside
     * the state chip, until 2026-09-23; Settings at the bar's left end
     * for a day; in the top right corner, after the chip, since
     * 2026-09-24). The log stood beside
     * it until 2026-09-23; it is a tile on Settings now.
     */
    fun barButton(res: Int, what: String, onClick: () -> Unit): View = ImageView(c).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(p.TEXT_2)
        contentDescription = what
        background = pressable(p.SURFACE, p.SURFACE_HOVER, radius = 6, stroke = p.LINE)
        val inset = dp(7)
        setPadding(inset, inset, inset, inset)
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(8) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * The outlined button beside the main switch, the mode's: TEXT on
     * SURFACE, as tall as the switch it stands next to, and no wider than
     * its label. What it opens is a sheet; the label says which mode holds.
     */
    fun ghostButton(label: String, onClick: () -> Unit) =
        text(label, 13f, p.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = pressable(p.SURFACE, p.SURFACE_HOVER, radius = RADIUS, stroke = p.LINE)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(SWITCH_H)).apply { leftMargin = dp(8) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /**
     * The same outline as [ghostButton], square and with an icon in place
     * of a label: the way back into the game, beside the mode. PRIMARY,
     * because it is an action and the mode is a setting.
     */
    fun ghostIcon(res: Int, what: String, onClick: () -> Unit): View = ImageView(c).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(p.PRIMARY)
        contentDescription = what
        background = pressable(p.SURFACE, p.SURFACE_HOVER, radius = RADIUS, stroke = p.LINE)
        val inset = dp(9)
        setPadding(inset, inset, inset, inset)
        layoutParams = LinearLayout.LayoutParams(dp(SWITCH_H), dp(SWITCH_H)).apply { leftMargin = dp(8) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun outButton(label: String, onClick: () -> Unit) =
        text(label, 12f, p.PRIMARY, bold = true).apply {
            background = pressable(p.SURFACE, p.SURFACE_HOVER, radius = 6, stroke = p.LINE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun textButton(label: String, onClick: () -> Unit) =
        text(label, 13f, p.PRIMARY, bold = true).apply {
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** The filled small button beside a field, Unlock's shape. */
    fun primaryButton(label: String, onClick: () -> Unit) =
        text(label, 13f, p.ON_PRIMARY, bold = true).apply {
            gravity = Gravity.CENTER
            background = pressable(p.PRIMARY, p.PRIMARY_ACTIVE, radius = 6)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** A row of a list or a panel that opens something when it is tapped. */
    fun tappable(v: View, onClick: () -> Unit): View = v.apply {
        background = pressable(p.SURFACE, p.SURFACE_HOVER, radius = 0)
        isClickable = true
        setOnClickListener { onClick() }
    }

    // --- tiles -------------------------------------------------------------

    /**
     * The coloured square a tile's icon sits in: the mark's own field, so
     * that the tile around it can stay a panel like every other panel.
     */
    private fun iconField(res: Int, size: Int, fg: Int, bg: Int, edge: Int?): View =
        icon(res, if (size >= 40) 24 else 20, fg).apply {
            background = round(bg, edge, radius = RADIUS)
            val inset = dp((size - (if (size >= 40) 24 else 20)) / 2)
            setPadding(inset, inset, inset, inset)
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }

    /**
     * A panel that is a button (2026-09-23, design A2): an icon field on the
     * left, a name in the task face with a line under it, and a glyph on the
     * right saying what a tap does -- the Discord tile under the set-up
     * line. It replaced a text link the player called too quiet.
     */
    fun tile(res: Int, fg: Int, bg: Int, edge: Int?, title: String, note: String,
             trailing: Int, onClick: () -> Unit): LinearLayout = row().apply {
        background = pressable(p.SURFACE, p.SURFACE_HOVER, radius = RADIUS, stroke = p.LINE)
        setPadding(dp(PAD), dp(PAD), dp(PAD), dp(PAD))
        addView(iconField(res, 40, fg, bg, edge))
        addView(grow(column().apply {
            addView(taskName(title))
            addView(hint(note))
        }).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(PAD) })
        addView(chevron(trailing).apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        })
        isClickable = true
        setOnClickListener { onClick() }
    }

    /**
     * Half of a pair (design B3): the same panel stood on end, icon field
     * over name over line, for two things that belong side by side -- the
     * log and the debug package at the foot of Settings. The caller gives it
     * its half of the row.
     */
    fun squareTile(res: Int, fg: Int, bg: Int, edge: Int?, title: String, note: String,
                   onClick: () -> Unit): LinearLayout = column().apply {
        background = pressable(p.SURFACE, p.SURFACE_HOVER, radius = RADIUS, stroke = p.LINE)
        setPadding(dp(PAD), dp(PAD), dp(PAD), dp(PAD))
        addView(iconField(res, 36, fg, bg, edge))
        addView(taskName(title).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                     ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        })
        addView(hint(note))
        isClickable = true
        setOnClickListener { onClick() }
    }

    // --- controls ---------------------------------------------------------

    /**
     * Two or three choices in one track, the console design's answer to the
     * radio rows the first design had: the chosen one is a raised plate, the
     * others are flat. What each choice *means* is one line under the
     * track, so the control itself stays one row high whatever the
     * sentences say.
     */
    fun segmented(labels: List<String>, chosen: Int, onPick: (Int) -> Unit): LinearLayout {
        val track = row().apply {
            background = round(p.BG, radius = RADIUS)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        labels.forEachIndexed { i, label ->
            val on = i == chosen
            track.addView(text(label, 12f, if (on) p.TEXT else p.TEXT_2, bold = on).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(7))
                background = if (on) round(p.SURFACE, p.LINE, radius = 6) else null
                isClickable = true
                setOnClickListener { onPick(i) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i > 0) leftMargin = dp(3) }
            })
        }
        return track
    }

    /**
     * The "included" switch, drawn rather than fought for. A platform
     * `Switch` was tried first and gave it up: its track is a translucent
     * drawable, so a tint over it comes out washed, and an opaque one of its
     * own is then travelled past its right end by the thumb, whatever the
     * thumb's size and inset. Two attempts and two wrong pictures in
     * LDPlayer; 40 x 23 dp with an 18 dp thumb is what the console design
     * says, and this draws exactly that.
     */
    fun switch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): View =
        Toggle(this, checked, enabled, onChange)

    /**
     * A small tick box with its label beside it, the whole row the touch
     * target. For a quiet opt-out under a sheet's text, where a switch would
     * say "setting" louder than the one button the sheet is about. The label
     * goes from TEXT_2 to TEXT when it is ticked.
     */
    fun checkRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val box = Tick(this, checked)
        val words = text(label, 12f, if (checked) p.TEXT else p.TEXT_2)
        return row().apply {
            minimumHeight = dp(40)
            isClickable = true
            addView(box)
            addView(words, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(10) })
            setOnClickListener {
                box.checked = !box.checked
                words.setTextColor(if (box.checked) p.TEXT else p.TEXT_2)
                onChange(box.checked)
            }
        }
    }

    /** A row with a label on the left and a switch on the right. */
    fun switchRow(label: String, note: String, checked: Boolean, enabled: Boolean = true,
                  indent: Int = 0, onChange: (Boolean) -> Unit): LinearLayout {
        val box = column().apply { setPadding(dp(20 * indent), dp(2), 0, dp(2)) }
        val r = row()
        val left = column()
        left.addView(name(label))
        if (note.isNotEmpty()) left.addView(hint(note))
        r.addView(grow(left))
        r.addView(switch(checked, enabled, onChange))
        box.addView(r)
        return box
    }

    fun edit(hintText: String = "", numeric: Boolean = false, decimal: Boolean = false): EditText =
        EditText(c).apply {
            hint = hintText
            setTextColor(p.TEXT)
            setHintTextColor(p.TEXT_2)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            background = round(p.BG, p.LINE, radius = 6)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        }
}

/**
 * The design's switch: a 40 x 23 dp track with an 18 dp thumb, in PRIMARY
 * when it is on and DISABLED_BG when it is off, inside a 48 dp touch target.
 * A View of its own because the platform Switch would not draw it (see
 * [Ui.switch]); the colours are still [Palette]'s and nothing here writes a
 * value.
 */
class Toggle(
    private val ui: Ui,
    private var checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) : View(ui.c) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = enabled
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener {
            checked = !checked
            invalidate()
            onChange(checked)
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension(ui.dp(40), ui.dp(48))

    override fun onDraw(canvas: Canvas) {
        val p = ui.p
        val trackH = ui.dp(23).toFloat()
        val top = (height - trackH) / 2f
        val r = trackH / 2f
        paint.color = if (checked) p.PRIMARY else p.DISABLED_BG
        canvas.drawRoundRect(0f, top, width.toFloat(), top + trackH, r, r, paint)
        paint.color = if (checked) p.ON_PRIMARY else p.SURFACE
        canvas.drawCircle(if (checked) width - r else r, top + r, ui.dp(9).toFloat(), paint)
    }
}

/**
 * The tick box of [Ui.checkRow]: 18 dp, a DISABLED_FG outline when it is
 * off, PRIMARY with an ON_PRIMARY tick when it is on. Drawn, as [Toggle] is,
 * so that its colours are [Palette]'s and nothing else's; the row it sits in
 * takes the tap.
 */
class Tick(private val ui: Ui, checked: Boolean) : View(ui.c) {

    var checked: Boolean = checked
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Path()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension(ui.dp(18), ui.dp(18))

    override fun onDraw(canvas: Canvas) {
        val p = ui.p
        val s = width.toFloat()
        val r = ui.dp(4).toFloat()
        val density = ui.c.resources.displayMetrics.density
        val line = 1.5f * density
        if (checked) {
            paint.style = Paint.Style.FILL
            paint.color = p.PRIMARY
            canvas.drawRoundRect(0f, 0f, s, s, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = p.ON_PRIMARY
            mark.reset()
            mark.moveTo(s * 0.24f, s * 0.52f)
            mark.lineTo(s * 0.42f, s * 0.70f)
            mark.lineTo(s * 0.76f, s * 0.32f)
            canvas.drawPath(mark, paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = p.SURFACE
            canvas.drawRoundRect(0f, 0f, s, s, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = line
            paint.color = p.DISABLED_FG
            val h = line / 2f
            canvas.drawRoundRect(h, h, s - h, s - h, r, r, paint)
        }
    }
}
