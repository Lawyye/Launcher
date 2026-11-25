package com.lawyye.wired

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * "The Wired": the full-screen, opaque app drawer. Grid (default) or list, category chips,
 * frequent apps, real-time search with web actions. Rows are recycled by a ListView and icons
 * come from [IconCache], so fast scrolling never decodes an icon twice.
 */
@SuppressLint("ViewConstructor")
class DrawerView(ctx: Context, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        fun launchApp(app: AppEntry, from: View)
        fun appMenu(app: AppEntry)
        fun openSettings()
        fun requestClose()
        fun webSearch(query: String)
        fun mapsSearch(query: String)
        fun storeSearch(query: String)
        fun drawerModeChanged(mode: Int)
    }

    private sealed class Row {
        class Label(val text: String, val letter: Boolean) : Row()
        class Grid(val apps: List<AppEntry>) : Row()
        class App(val app: AppEntry) : Row()
        class Result(val app: AppEntry, val first: Boolean, val start: Int, val len: Int) : Row()
        class Action(val icon: Int, val text: String, val kind: Int) : Row()
        class Empty(val text: String) : Row()
    }

    var isOpen = false
        private set

    /** The drawer now covers the screen completely / is about to uncover it (see HomeActivity). */
    var onCovering: ((Boolean) -> Unit)? = null

    private var apps: List<AppEntry> = emptyList()
    private var visible: List<AppEntry> = emptyList()
    private var hidden: Set<String> = emptySet()
    private var counts: Map<String, Int> = emptyMap()
    private var mode = Prefs.DRAWER_GRID
    private var columns = 4
    private var iconDp = 56
    private var iconFilter: ColorFilter? = null
    private var accent = Wired.BLUE
    private var category = AppEntry.CAT_ALL
    private var query = ""
    private var rows: List<Row> = emptyList()
    private val letterRows = LinkedHashMap<String, Int>()

    private val glow = GradientDrawable()
    private val content = LinearLayout(ctx)
    private val title = ctx.label(26f, Wired.CLOCK, Fonts.heading)
    private val subtitle = ctx.meta("")
    private val modeButton: FrameLayout
    private val header = LinearLayout(ctx)
    private val searchBox = LinearLayout(ctx)
    private val edit = EditText(ctx)
    private val clearButton: FrameLayout
    private val chipsScroll = HorizontalScrollView(ctx)
    private val chipsRow = LinearLayout(ctx)
    private val chips = ArrayList<TextView>()
    private val listFrame = FrameLayout(ctx)
    private val list = ListView(ctx)
    private val rail = AlphabetRail(ctx)
    private val adapter = Adapter()

    init {
        visibility = GONE
        isClickable = true
        // Android 15+: while the drawer moves or scrolls, ask for the panel's top refresh rate
        // (120 Hz on the A36); the vote counts only when it draws, so idle costs nothing
        if (Build.VERSION.SDK_INT >= 35) {
            setRequestedFrameRate(REQUESTED_FRAME_RATE_CATEGORY_HIGH)
            list.setRequestedFrameRate(REQUESTED_FRAME_RATE_CATEGORY_HIGH)
        }
        glow.gradientType = GradientDrawable.RADIAL_GRADIENT
        glow.setGradientCenter(0.5f, 0f)
        background = LayerDrawable(arrayOf(ColorDrawable(Wired.DRAWER), glow))

        content.orientation = LinearLayout.VERTICAL
        addView(content, LayoutParams(MATCH, MATCH))

        val handle = View(ctx)
        handle.background = ctx.rounded(0x38D9E9FF, 2f)
        val hlp = LinearLayout.LayoutParams(ctx.dpi(36), ctx.dpi(4))
        hlp.gravity = Gravity.CENTER_HORIZONTAL
        hlp.topMargin = ctx.dpi(10)
        content.addView(handle, hlp)

        // header: title, count, mode toggle, settings
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(ctx)
        titles.orientation = LinearLayout.VERTICAL
        title.text = "The Wired"
        titles.addView(title)
        val slp = LinearLayout.LayoutParams(WRAP, WRAP)
        slp.topMargin = ctx.dpi(4)
        titles.addView(subtitle, slp)
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        modeButton = ctx.iconButton(R.drawable.ic_list, "Показать списком") { toggleMode() }
        header.addView(modeButton)
        val gear = ctx.iconButton(R.drawable.ic_gear, "Настройки лаунчера") { host.openSettings() }
        (gear.layoutParams as LinearLayout.LayoutParams).leftMargin = ctx.dpi(8)
        header.addView(gear)
        val headLp = LinearLayout.LayoutParams(MATCH, WRAP)
        headLp.topMargin = ctx.dpi(14)
        content.addView(header, headLp)

        // search
        searchBox.orientation = LinearLayout.HORIZONTAL
        searchBox.gravity = Gravity.CENTER_VERTICAL
        searchBox.setPadding(ctx.dpi(14), 0, ctx.dpi(4), 0)
        searchBox.addView(ctx.iconView(R.drawable.ic_search, Wired.TEXT2, 18))
        edit.background = null
        edit.typeface = Fonts.ui
        edit.textSize = 15f
        edit.setTextColor(Wired.TEXT)
        edit.setHintTextColor(Wired.HINT)
        edit.hint = "Поиск приложений и в сети"
        edit.isSingleLine = true
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        edit.setPadding(ctx.dpi(10), 0, ctx.dpi(6), 0)
        searchBox.addView(edit, LinearLayout.LayoutParams(0, MATCH, 1f))
        clearButton = FrameLayout(ctx)
        clearButton.contentDescription = "Очистить"
        clearButton.background = ctx.ripple(null, 12f)
        clearButton.isClickable = true
        clearButton.addView(ctx.iconView(R.drawable.ic_close, Wired.TEXT2, 18), LayoutParams(ctx.dpi(18), ctx.dpi(18), Gravity.CENTER))
        clearButton.setOnClickListener { edit.setText("") }
        clearButton.visibility = GONE
        searchBox.addView(clearButton, LinearLayout.LayoutParams(ctx.dpi(44), ctx.dpi(44)))
        val sLp = LinearLayout.LayoutParams(MATCH, ctx.dpi(50))
        sLp.topMargin = ctx.dpi(16)
        content.addView(searchBox, sLp)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                clearButton.visibility = if (query.isEmpty()) GONE else VISIBLE
                chipsScroll.visibility = if (query.isEmpty()) VISIBLE else GONE
                rebuild()
                list.setSelection(0)
            }
        })
        edit.setOnFocusChangeListener { _, _ -> styleSearch() }
        edit.setOnEditorActionListener { _, actionId, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                launchFirst()
                true
            } else {
                false
            }
        }

        // category chips
        chipsScroll.isHorizontalScrollBarEnabled = false
        chipsScroll.overScrollMode = OVER_SCROLL_NEVER
        chipsRow.orientation = LinearLayout.HORIZONTAL
        AppEntry.CATEGORY_TITLES.forEachIndexed { i, t ->
            val c = ctx.chip(t, i == category, accent) { pickCategory(i) }
            val lp = LinearLayout.LayoutParams(WRAP, ctx.dpi(36))
            if (i > 0) lp.leftMargin = ctx.dpi(8)
            chipsRow.addView(c, lp)
            chips.add(c)
        }
        chipsScroll.addView(chipsRow)
        val cLp = LinearLayout.LayoutParams(MATCH, WRAP)
        cLp.topMargin = ctx.dpi(14)
        content.addView(chipsScroll, cLp)

        // the list
        list.divider = null
        list.dividerHeight = 0
        list.selector = ColorDrawable(0)
        list.isVerticalScrollBarEnabled = false
        list.clipToPadding = false
        list.overScrollMode = OVER_SCROLL_NEVER
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }

            override fun onScroll(view: AbsListView?, first: Int, visibleCount: Int, total: Int) {
                if (rail.visibility == VISIBLE) rail.current = currentLetter(first)
            }
        })
        listFrame.addView(list, LayoutParams(MATCH, MATCH))
        rail.onPick = { letter -> letterRows[letter]?.let { list.setSelectionFromTop(it, 0) } }
        val rlp = LayoutParams(ctx.dpi(22), WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
        listFrame.addView(rail, rlp)
        val fade = View(ctx)
        fade.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Wired.DRAWER, Wired.DRAWER and 0x00FFFFFF),
        )
        fade.isClickable = false
        listFrame.addView(fade, LayoutParams(MATCH, ctx.dpi(56), Gravity.BOTTOM))
        val lLp = LinearLayout.LayoutParams(MATCH, 0, 1f)
        lLp.topMargin = ctx.dpi(8)
        content.addView(listFrame, lLp)
        styleSearch()
    }

    // ------------------------------------------------------------ configuration

    fun applyStyle(prefs: Prefs) {
        accent = prefs.accent
        mode = prefs.drawerMode
        columns = prefs.columns.coerceIn(4, 5)
        iconDp = if (columns == 5) prefs.iconSizeDp.coerceAtMost(52) else prefs.iconSizeDp
        iconFilter = iconFilter(prefs.iconMode, accent)
        glow.colors = intArrayOf(Wired.alpha(accent, 0x29), Wired.alpha(accent, 0))
        chips.forEachIndexed { i, c -> context.styleChip(c, i == category, accent) }
        edit.highlightColor = Wired.alpha(accent, 0x55)
        styleSearch()
        updateModeButton()
        adapter.notifyDataSetInvalidated()
        rebuild()
    }

    /**
     * The exact size the drawer draws icons at — the grid size (5 columns cap it at 52dp) or the
     * 40dp of list rows. Warming the cache at any other size would only waste memory.
     */
    val iconPx: Int get() = context.dpi(if (mode == Prefs.DRAWER_LIST) LIST_ICON_DP else iconDp)

    fun setApps(all: List<AppEntry>, hidden: Set<String>, counts: Map<String, Int>) {
        apps = all
        this.hidden = hidden
        this.counts = counts
        rebuild()
    }

    fun setInsets(bars: Rect, withIme: Rect) {
        content.setPadding(bars.left + context.dpi(20), bars.top + context.dpi(4), bars.right + context.dpi(20), 0)
        list.setPadding(0, 0, 0, maxOf(bars.bottom, withIme.bottom) + context.dpi(24))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glow.gradientRadius = w * 0.85f
    }

    private fun styleSearch() {
        val focused = edit.hasFocus()
        searchBox.background = context.rounded(
            Wired.FIELD, 16f, if (focused) Wired.alpha(accent, 0x8C) else Wired.LINE, if (focused) 1.5f else 1f,
        )
    }

    private fun updateModeButton() {
        val grid = mode == Prefs.DRAWER_GRID
        val iv = modeButton.getChildAt(0) as ImageView
        iv.setImageDrawable(context.icon(if (grid) R.drawable.ic_list else R.drawable.ic_grid, Wired.ICON_SOFT))
        modeButton.contentDescription = if (grid) "Показать списком" else "Показать сеткой"
    }

    private fun toggleMode() {
        mode = if (mode == Prefs.DRAWER_GRID) Prefs.DRAWER_LIST else Prefs.DRAWER_GRID
        host.drawerModeChanged(mode)
        updateModeButton()
        rebuild()
        list.setSelection(0)
    }

    private fun pickCategory(i: Int) {
        category = i
        chips.forEachIndexed { k, c -> context.styleChip(c, k == i, accent) }
        rebuild()
        list.setSelection(0)
    }

    // ------------------------------------------------------------ rows

    private fun rebuild() {
        visible = apps.filter { it.key !in hidden }
        val out = ArrayList<Row>()
        letterRows.clear()
        val q = query.lowercase()
        if (q.isNotEmpty()) {
            // the query as typed, and the same keys in the other layout ("еудупкфь" → telegram);
            // a layout match ranks just below a direct match of the same kind
            val alt = Search.swapLayout(q).takeIf { it != q }
            val ranked = visible.mapNotNull { a ->
                val direct = Search.rank(a.search, a.searchPkg, q)
                if (direct >= 0) return@mapNotNull Hit(a, direct * 2, a.search.indexOf(q), q.length)
                if (alt == null) return@mapNotNull null
                val swapped = Search.rank(a.search, a.searchPkg, alt)
                if (swapped >= 0) Hit(a, swapped * 2 + 1, a.search.indexOf(alt), alt.length) else null
            }.sortedBy { it.rank }.take(30)
            out.add(Row.Label("приложения · " + ranked.size, false))
            if (ranked.isEmpty()) out.add(Row.Empty("ничего не найдено · попробуй в сети"))
            ranked.forEachIndexed { i, h ->
                out.add(Row.Result(h.app, i == 0, if (h.idx >= 0) h.idx else 0, if (h.idx >= 0) h.len else 0))
            }
            out.add(Row.Label("в сети", false))
            out.add(Row.Action(R.drawable.ic_globe, "Искать «$query» в интернете", 0))
            out.add(Row.Action(R.drawable.ic_map_pin, "Найти «$query» на картах", 1))
            out.add(Row.Action(R.drawable.ic_bag, "Найти в магазине приложений", 2))
            subtitle.text = visible.size.toString() + " nodes · поиск"
        } else {
            val shown = if (category == AppEntry.CAT_ALL) visible else visible.filter { it.category == category }
            if (mode == Prefs.DRAWER_GRID) {
                if (category == AppEntry.CAT_ALL) {
                    val freq = visible.filter { (counts[it.key] ?: 0) >= 2 }
                        .sortedByDescending { counts[it.key] ?: 0 }.take(columns)
                    if (freq.isNotEmpty()) {
                        out.add(Row.Label("частые", false))
                        out.add(Row.Grid(freq))
                    }
                }
                out.add(Row.Label(if (category == AppEntry.CAT_ALL) "все · " + shown.size else AppEntry.categoryMeta(category) + " · " + shown.size, false))
                shown.chunked(columns).forEach { out.add(Row.Grid(it)) }
            } else {
                var last = ""
                for (a in shown) {
                    if (a.section != last) {
                        last = a.section
                        letterRows[last] = out.size
                        out.add(Row.Label(last, true))
                    }
                    out.add(Row.App(a))
                }
            }
            if (shown.isEmpty()) {
                out.add(Row.Empty(if (!AppRepository.loaded) "сканирую сеть…" else "здесь пусто"))
            }
            subtitle.text = visible.size.toString() + " nodes · " + if (mode == Prefs.DRAWER_GRID) "а–я" else "список"
        }
        rows = out
        rail.letters = if (q.isEmpty() && mode == Prefs.DRAWER_LIST) letterRows.keys.toList() else emptyList()
        rail.visibility = if (rail.letters.size > 3) VISIBLE else GONE
        adapter.notifyDataSetChanged()
    }

    private fun currentLetter(first: Int): String? {
        var cur: String? = null
        for ((k, pos) in letterRows) {
            if (pos <= first) cur = k else break
        }
        return cur
    }

    private fun launchFirst() {
        val r = rows.firstOrNull { it is Row.Result } as? Row.Result
        if (r != null) {
            host.launchApp(r.app, list)
        } else if (query.isNotEmpty()) {
            host.webSearch(query)
        }
    }

    // ------------------------------------------------------------ open / close

    fun open(keyboard: Boolean) {
        if (isOpen) return
        isOpen = true
        // The rows are already built (setApps, applyStyle): rebuild only if the last visit left a
        // category or a search behind. Rebuilding on every open was the slowest frame of the drawer.
        if (category != AppEntry.CAT_ALL) {
            category = AppEntry.CAT_ALL
            chips.forEachIndexed { k, c -> context.styleChip(c, k == 0, accent) }
            if (edit.text.isEmpty()) rebuild()
        }
        if (edit.text.isNotEmpty()) edit.setText("")
        list.setSelection(0)
        visibility = VISIBLE
        alpha = 0f
        translationY = height * 0.06f
        // withLayer: the drawer is drawn once into a GPU layer and only that layer fades and moves;
        // without it every frame of the fade re-rendered the whole drawer through an offscreen buffer
        animate().alpha(1f).translationY(0f).setDuration(280L).setInterpolator(DecelerateInterpolator(2f))
            .withLayer().withEndAction { if (isOpen) onCovering?.invoke(true) }.start()
        if (keyboard) {
            edit.requestFocus()
            postDelayed({ showKeyboard() }, 160L)
        }
    }

    fun close(animated: Boolean) {
        if (!isOpen) return
        isOpen = false
        onCovering?.invoke(false)
        hideKeyboard()
        edit.clearFocus()
        if (animated) {
            animate().alpha(0f).translationY(height * 0.04f).setDuration(200L).setInterpolator(DecelerateInterpolator())
                .withLayer()
                .withEndAction {
                    visibility = GONE
                    if (edit.text.isNotEmpty()) edit.setText("")
                }.start()
        } else {
            animate().cancel()
            alpha = 1f
            translationY = 0f
            visibility = GONE
            if (edit.text.isNotEmpty()) edit.setText("")
        }
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(edit.windowToken, 0)
    }

    fun isAtTop(): Boolean {
        if (list.childCount == 0) return true
        return list.firstVisiblePosition == 0 && list.getChildAt(0).top >= list.paddingTop
    }

    fun isTouchOnScroller(rawX: Float, rawY: Float): Boolean = hit(rail, rawX, rawY) || hit(chipsScroll, rawX, rawY)

    private fun hit(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != VISIBLE || !v.isShown) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height
    }

    // ------------------------------------------------------------ adapter

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 6
        override fun areAllItemsEnabled(): Boolean = false
        override fun isEnabled(position: Int): Boolean = false

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Label -> 0
            is Row.Grid -> 1
            is Row.App -> 2
            is Row.Result -> 3
            is Row.Action -> 4
            is Row.Empty -> 5
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val r = rows[position]) {
                is Row.Label -> label(convertView, r)
                is Row.Grid -> grid(convertView, r)
                is Row.App -> appRow(convertView, r.app)
                is Row.Result -> result(convertView, r)
                is Row.Action -> action(convertView, r)
                is Row.Empty -> empty(convertView, r)
            }
        }
    }

    private fun label(cv: View?, r: Row.Label): View {
        val row = cv as? LinearLayout ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dpi(12), 0, context.dpi(6))
            addView(context.meta(""))
            val line = View(context)
            line.setBackgroundColor(0x2482B9FF)
            val lp = LinearLayout.LayoutParams(0, context.dpi(1), 1f)
            lp.leftMargin = context.dpi(10)
            addView(line, lp)
        }
        val t = row.getChildAt(0) as TextView
        t.text = r.text
        if (r.letter) {
            t.typeface = Fonts.uiBold
            t.textSize = 13f
            t.setTextColor(accent)
        } else {
            t.typeface = Fonts.mono
            t.textSize = 11f
            t.setTextColor(Wired.TEXT2)
        }
        row.setPadding(0, context.dpi(12), if (rail.visibility == VISIBLE) context.dpi(24) else 0, context.dpi(6))
        return row
    }

    private inner class GridHolder(val cols: Int, val icon: Int, val cells: List<LinearLayout>, val icons: List<ImageView>, val labels: List<TextView>)

    private fun grid(cv: View?, r: Row.Grid): View {
        val px = context.dpi(iconDp)
        var row = cv as? LinearLayout
        var h = row?.tag as? GridHolder
        if (row == null || h == null || h.cols != columns || h.icon != px) {
            row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            val cells = ArrayList<LinearLayout>()
            val icons = ArrayList<ImageView>()
            val labels = ArrayList<TextView>()
            repeat(columns) {
                val cell = LinearLayout(context)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.setPadding(0, context.dpi(6), 0, context.dpi(8))
                cell.isClickable = true
                cell.isFocusable = true
                cell.background = context.ripple(null, 18f, accent)
                val iv = ImageView(context)
                iv.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                cell.addView(iv, LinearLayout.LayoutParams(px, px))
                val t = context.label(12f, Wired.TEXT_SOFT, Fonts.ui)
                t.singleLine()
                t.gravity = Gravity.CENTER
                t.setPadding(context.dpi(2), 0, context.dpi(2), 0)
                val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
                tlp.topMargin = context.dpi(8)
                cell.addView(t, tlp)
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
                cells.add(cell)
                icons.add(iv)
                labels.add(t)
            }
            h = GridHolder(columns, px, cells, icons, labels)
            row.tag = h
        }
        for (i in 0 until h.cols) {
            val cell = h.cells[i]
            val app = r.apps.getOrNull(i)
            if (app == null) {
                cell.visibility = INVISIBLE
                cell.setOnClickListener(null)
                cell.setOnLongClickListener(null)
                continue
            }
            cell.visibility = VISIBLE
            cell.contentDescription = app.label
            h.labels[i].text = app.label
            h.icons[i].colorFilter = iconFilter
            IconCache.bind(h.icons[i], app, px)
            val iv = h.icons[i]
            cell.setOnClickListener { host.launchApp(app, iv) }
            cell.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                host.appMenu(app)
                true
            }
        }
        return row
    }

    private class RowHolder(val icon: ImageView, val name: TextView, val meta: TextView)

    private fun appLine(cv: View?, iconDp: Int, heightDp: Int): Pair<LinearLayout, RowHolder> {
        val existing = cv as? LinearLayout
        val eh = existing?.tag as? RowHolder
        if (existing != null && eh != null) return existing to eh
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = context.dpi(heightDp)
        row.isClickable = true
        row.isFocusable = true
        val iv = ImageView(context)
        iv.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(iv, LinearLayout.LayoutParams(context.dpi(iconDp), context.dpi(iconDp)))
        val name = context.label(15f, Wired.TEXT, Fonts.ui)
        name.singleLine()
        val nlp = LinearLayout.LayoutParams(0, WRAP, 1f)
        nlp.leftMargin = context.dpi(14)
        row.addView(name, nlp)
        val meta = context.meta("")
        meta.setTextColor(Wired.STATIC)
        val mlp = LinearLayout.LayoutParams(WRAP, WRAP)
        mlp.leftMargin = context.dpi(8)
        row.addView(meta, mlp)
        val h = RowHolder(iv, name, meta)
        row.tag = h
        return row to h
    }

    private fun bindAppLine(row: LinearLayout, h: RowHolder, app: AppEntry, iconDp: Int) {
        row.contentDescription = app.label
        h.icon.colorFilter = iconFilter
        IconCache.bind(h.icon, app, context.dpi(iconDp))
        row.setOnClickListener { host.launchApp(app, h.icon) }
        row.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            host.appMenu(app)
            true
        }
    }

    private fun appRow(cv: View?, app: AppEntry): View {
        val (row, h) = appLine(if (cv?.getTag(R.drawable.ic_list) == true) cv else null, LIST_ICON_DP, 56)
        row.setTag(R.drawable.ic_list, true)
        row.setPadding(0, 0, if (rail.visibility == VISIBLE) context.dpi(26) else 0, 0)
        row.background = context.ripple(null, 14f, accent)
        h.name.text = app.label
        h.meta.text = AppEntry.categoryMeta(app.category)
        h.meta.background = null
        h.meta.setPadding(0, 0, 0, 0)
        bindAppLine(row, h, app, LIST_ICON_DP)
        return row
    }

    private fun result(cv: View?, r: Row.Result): View {
        val (row, h) = appLine(if (cv?.getTag(R.drawable.ic_search) == true) cv else null, 44, 60)
        row.setTag(R.drawable.ic_search, true)
        row.setPadding(context.dpi(10), 0, context.dpi(10), 0)
        row.background = context.ripple(if (r.first) context.rounded(Wired.alpha(accent, 0x14), 16f) else null, 16f, accent)
        val sb = SpannableStringBuilder(r.app.label)
        if (r.len > 0 && r.start + r.len <= sb.length) {
            sb.setSpan(ForegroundColorSpan(accent), r.start, r.start + r.len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        h.name.text = sb
        h.name.textSize = 16f
        if (r.first) {
            h.meta.text = "↵ открыть"
            h.meta.setTextColor(Wired.ICON_SOFT)
            h.meta.background = context.rounded(0, 8f, 0x4D82B9FF)
            h.meta.setPadding(context.dpi(8), context.dpi(5), context.dpi(8), context.dpi(5))
        } else {
            h.meta.text = AppEntry.categoryMeta(r.app.category)
            h.meta.setTextColor(Wired.STATIC)
            h.meta.background = null
            h.meta.setPadding(0, 0, 0, 0)
        }
        bindAppLine(row, h, r.app, 44)
        return row
    }

    private fun action(cv: View?, r: Row.Action): View {
        val row = cv as? LinearLayout ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dpi(56)
            isClickable = true
            isFocusable = true
            val tile = FrameLayout(context)
            tile.addView(ImageView(context), LayoutParams(context.dpi(20), context.dpi(20), Gravity.CENTER))
            addView(tile, LinearLayout.LayoutParams(context.dpi(44), context.dpi(44)))
            val t = context.label(15f, Wired.TEXT_SOFT, Fonts.ui)
            t.singleLine()
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.leftMargin = context.dpi(14)
            addView(t, lp)
            addView(context.iconView(R.drawable.ic_arrow_out, Wired.HINT, 18))
        }
        val tile = row.getChildAt(0) as FrameLayout
        tile.background = context.rounded(0, 13f, Wired.alpha(accent, 0x47))
        (tile.getChildAt(0) as ImageView).setImageDrawable(context.icon(r.icon, accent))
        (row.getChildAt(1) as TextView).text = r.text
        row.background = context.ripple(null, 14f, accent)
        row.setOnClickListener {
            when (r.kind) {
                0 -> host.webSearch(query)
                1 -> host.mapsSearch(query)
                else -> host.storeSearch(query)
            }
        }
        return row
    }

    private fun empty(cv: View?, r: Row.Empty): View {
        val t = cv as? TextView ?: context.meta("").apply {
            gravity = Gravity.CENTER
            setPadding(0, context.dpi(28), 0, context.dpi(28))
        }
        t.text = r.text
        return t
    }

    private class Hit(val app: AppEntry, val rank: Int, val idx: Int, val len: Int)

    companion object {
        const val LIST_ICON_DP = 40
    }
}

/** Letters down the right edge of the list; touch or drag to jump. */
class AlphabetRail(ctx: Context) : View(ctx) {

    var letters: List<String> = emptyList()
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }
    var current: String? = null
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var onPick: ((String) -> Unit)? = null
    var accent = Wired.BLUE

    private val item = ctx.dp(18f)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        text.typeface = Fonts.mono
        text.textSize = 10f * ctx.resources.displayMetrics.scaledDensity
        text.textAlign = Paint.Align.CENTER
        contentDescription = "Алфавитный указатель"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = (letters.size * item).toInt() + paddingTop + paddingBottom
        val avail = MeasureSpec.getSize(heightMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) want else minOf(want, avail)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private fun step(): Float = if (letters.isEmpty()) item else minOf(item, (height - paddingTop - paddingBottom) / letters.size.toFloat())

    override fun onDraw(canvas: Canvas) {
        val s = step()
        val cx = width / 2f
        letters.forEachIndexed { i, l ->
            val cy = paddingTop + s * i + s / 2f
            if (l == current) {
                dot.color = Wired.alpha(accent, 0x38)
                canvas.drawCircle(cx, cy, s * 0.55f, dot)
                text.color = Wired.CLOCK
            } else {
                text.color = Wired.HINT
            }
            canvas.drawText(l, cx, cy + text.textSize * 0.35f, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = ((event.y - paddingTop) / step()).toInt().coerceIn(0, letters.size - 1)
                val l = letters[i]
                if (l != current) {
                    current = l
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onPick?.invoke(l)
                }
                return true
            }
        }
        return true
    }
}
