package com.lawyye.wired

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

/** The app drawer: a terminal-style, searchable list of every launchable app. */
@SuppressLint("ViewConstructor")
class DrawerView(ctx: Context, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        fun launchApp(app: AppEntry, from: View)
        fun appMenu(app: AppEntry)
        fun openSettings()
        fun requestClose()
        fun webSearch(query: String)
    }

    private sealed class Row {
        class Header(val letter: String) : Row()
        class App(val app: AppEntry) : Row()
        class Web(val query: String) : Row()
    }

    private class AppHolder(val root: LinearLayout, val icon: ImageView, val label: TextView, val mark: TextView)

    var isOpen = false
        private set

    private val content = LinearLayout(ctx)
    private val title = ctx.monoText(11f, Palette.DIM)
    private val count = ctx.monoText(11f, Palette.DIM)
    private val promptMark = ctx.monoText(17f, Palette.TEXT)
    private val search = EditText(ctx)
    private val rule = View(ctx)
    private val list = ListView(ctx)
    private val azBar = AlphabetBar(ctx) { letter -> jumpTo(letter) }
    private val rowAdapter = RowAdapter()

    private var accent = Palette.TEXT
    private var iconMode = Prefs.ICON_MONO
    private var monoFilter: ColorFilter = Palette.duotone(Palette.TEXT, 0.18f, 1.25f)
    private var all: List<AppEntry> = emptyList()
    private var hidden: Set<String> = emptySet()

    init {
        setBackgroundColor(Palette.PANEL)
        isClickable = true
        visibility = GONE

        content.orientation = LinearLayout.VERTICAL
        addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // header: user@host:~$ ls /wired/apps   [047] [cfg]
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        head.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(count)
        val cfg = ctx.monoText(11f, Palette.TEXT)
        cfg.text = "[cfg]"
        cfg.setPadding(ctx.dpi(12), ctx.dpi(10), ctx.dpi(6), ctx.dpi(10))
        cfg.setOnClickListener { host.openSettings() }
        head.addView(cfg)
        content.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        // search line: > query
        val searchRow = LinearLayout(ctx)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        promptMark.text = "> "
        searchRow.addView(promptMark)
        search.background = null
        search.typeface = Palette.mono
        search.textSize = 16f
        search.letterSpacing = 0.06f
        search.setTextColor(Palette.BRIGHT)
        search.setHintTextColor(Palette.DIM)
        search.hint = "search_"
        search.setSingleLine(true)
        search.setPadding(0, ctx.dpi(8), 0, ctx.dpi(8))
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                rebuild()
            }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                launchFirst()
                true
            } else {
                false
            }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, WRAP, 1f))
        content.addView(searchRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val ruleLp = LinearLayout.LayoutParams(MATCH, ctx.dpi(1))
        ruleLp.bottomMargin = ctx.dpi(4)
        content.addView(rule, ruleLp)

        // list + letter index
        val body = FrameLayout(ctx)
        list.divider = null
        list.dividerHeight = 0
        list.cacheColorHint = Color.TRANSPARENT
        list.isVerticalScrollBarEnabled = false
        list.adapter = rowAdapter
        list.setOnItemClickListener { _, view, position, _ ->
            when (val r = rowAdapter.rows[position]) {
                is Row.App -> host.launchApp(r.app, view)
                is Row.Web -> host.webSearch(r.query)
                is Row.Header -> {}
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val r = rowAdapter.rows[position]
            if (r is Row.App) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                host.appMenu(r.app)
                true
            } else {
                false
            }
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }

            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {}
        })
        val listLp = FrameLayout.LayoutParams(MATCH, MATCH)
        listLp.rightMargin = ctx.dpi(26)
        body.addView(list, listLp)
        body.addView(azBar, FrameLayout.LayoutParams(ctx.dpi(24), MATCH, Gravity.END))
        content.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val footer = ctx.monoText(9f, Palette.DIM)
        footer.text = "[tap] exec  ·  [hold] options  ·  [swipe ↓] close"
        footer.gravity = Gravity.CENTER
        footer.setPadding(0, ctx.dpi(6), 0, ctx.dpi(2))
        content.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    fun setInsets(bars: Rect, withIme: Rect) {
        val c = context
        content.setPadding(
            c.dpi(18) + bars.left,
            c.dpi(8) + bars.top,
            c.dpi(8) + bars.right,
            c.dpi(6) + withIme.bottom,
        )
    }

    fun applyStyle(accent: Int, iconMode: Int, userHost: String) {
        this.accent = accent
        this.iconMode = iconMode
        monoFilter = Palette.duotone(accent, 0.18f, 1.25f)
        title.text = "$userHost:~\$ ls /wired/apps"
        promptMark.setTextColor(accent)
        rule.setBackgroundColor(Palette.withAlpha(accent, 90))
        list.selector = pressBg(accent)
        azBar.accent = accent
        if (Build.VERSION.SDK_INT >= 29) {
            val cursor = GradientDrawable()
            cursor.setColor(accent)
            cursor.setSize(context.dpi(9), context.dpi(18))
            search.textCursorDrawable = cursor
        }
        rowAdapter.notifyDataSetChanged()
    }

    fun setApps(apps: List<AppEntry>, hidden: Set<String>) {
        all = apps
        this.hidden = hidden
        rebuild()
    }

    fun isAtTop(): Boolean = !list.canScrollVertically(-1)

    fun isTouchOnScroller(rawX: Float, rawY: Float): Boolean {
        if (azBar.visibility != VISIBLE) return false
        val loc = IntArray(2)
        azBar.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + azBar.width && rawY >= loc[1] && rawY <= loc[1] + azBar.height
    }

    fun open(keyboard: Boolean) {
        if (isOpen) return
        isOpen = true
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f
        translationY = context.dp(48f)
        animate().alpha(1f).translationY(0f).setDuration(190L)
            .setInterpolator(DecelerateInterpolator()).start()
        list.setSelection(0)
        if (keyboard) showKeyboard()
    }

    fun close(animated: Boolean) {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        if (search.text.isNotEmpty()) search.setText("")
        search.clearFocus()
        animate().cancel()
        if (animated) {
            animate().alpha(0f).translationY(context.dp(48f)).setDuration(150L)
                .withEndAction { if (!isOpen) visibility = GONE }.start()
        } else {
            alpha = 0f
            visibility = GONE
        }
    }

    private fun showKeyboard() {
        search.requestFocus()
        search.postDelayed({
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(search, 0)
        }, 120L)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun rank(a: AppEntry, q: String): Int {
        val l = a.label.lowercase()
        return when {
            l.startsWith(q) -> 0
            l.split(' ').any { it.startsWith(q) } -> 1
            l.contains(q) -> 2
            else -> 3
        }
    }

    private fun rebuild() {
        val raw = search.text.toString().trim()
        val q = raw.lowercase()
        val visible = all.filter { it.key !in hidden }
        val rows = ArrayList<Row>()
        val letters = ArrayList<String>()
        if (q.isEmpty()) {
            var last: String? = null
            for (a in visible) {
                if (a.section != last) {
                    rows.add(Row.Header(a.section))
                    letters.add(a.section)
                    last = a.section
                }
                rows.add(Row.App(a))
            }
            count.text = String.format(Locale.US, "[%03d]", visible.size)
        } else {
            val hits = visible.filter { it.search.contains(q) }.sortedBy { rank(it, q) }
            for (a in hits) rows.add(Row.App(a))
            rows.add(Row.Web(raw))
            count.text = String.format(Locale.US, "[%03d]", hits.size)
        }
        rowAdapter.rows = rows
        rowAdapter.notifyDataSetChanged()
        azBar.setLetters(letters.distinct())
    }

    private fun jumpTo(letter: String) {
        val idx = rowAdapter.rows.indexOfFirst { it is Row.Header && it.letter == letter }
        if (idx >= 0) list.setSelection(idx)
    }

    private fun launchFirst() {
        val first = rowAdapter.rows.firstOrNull { it is Row.App } as? Row.App
        if (first != null) {
            host.launchApp(first.app, search)
        } else {
            val q = search.text.toString().trim()
            if (q.isNotEmpty()) host.webSearch(q)
        }
    }

    private fun makeAppHolder(): AppHolder {
        val c = context
        val root = LinearLayout(c)
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.setPadding(c.dpi(2), c.dpi(9), 0, c.dpi(9))
        val icon = ImageView(c)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        val label = c.monoText(15f)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        val mark = c.monoText(10f)
        val iconLp = LinearLayout.LayoutParams(c.dpi(26), c.dpi(26))
        iconLp.rightMargin = c.dpi(14)
        root.addView(icon, iconLp)
        root.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(mark)
        val h = AppHolder(root, icon, label, mark)
        root.tag = h
        return h
    }

    private fun appView(app: AppEntry, convert: View?): View {
        val h = convert?.tag as? AppHolder ?: makeAppHolder()
        h.label.text = app.label.uppercase()
        h.label.setTextColor(Palette.TEXT)
        if (iconMode == Prefs.ICON_NONE || app.icon == null) {
            h.icon.visibility = GONE
            h.icon.setImageDrawable(null)
        } else {
            h.icon.visibility = VISIBLE
            h.icon.setImageDrawable(app.icon)
            if (iconMode == Prefs.ICON_MONO) h.icon.colorFilter = monoFilter else h.icon.clearColorFilter()
        }
        h.mark.text = if (app.isWork) "[w]" else ""
        h.mark.setTextColor(accent)
        return h.root
    }

    private inner class RowAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Any = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 3

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            is Row.App -> 1
            is Row.Web -> 2
        }

        override fun isEnabled(position: Int): Boolean = rows[position] !is Row.Header

        override fun areAllItemsEnabled(): Boolean = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val c = this@DrawerView.context
            return when (val row = rows[position]) {
                is Row.Header -> {
                    val tv = convertView as? TextView ?: c.monoText(10f).also {
                        it.setPadding(c.dpi(2), c.dpi(16), 0, c.dpi(4))
                        it.maxLines = 1
                    }
                    tv.setTextColor(Palette.withAlpha(accent, 210))
                    tv.text = "── " + row.letter + " " + "─".repeat(40)
                    tv
                }
                is Row.App -> appView(row.app, convertView)
                is Row.Web -> {
                    val tv = convertView as? TextView ?: c.monoText(13f).also {
                        it.setPadding(c.dpi(2), c.dpi(14), 0, c.dpi(14))
                        it.maxLines = 1
                        it.ellipsize = TextUtils.TruncateAt.END
                    }
                    tv.setTextColor(Palette.DIM)
                    tv.text = "[web] search: \"" + row.query + "\""
                    tv
                }
            }
        }
    }
}
