package ro.noraz.nagenda

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.Date
import com.google.android.material.R as M

class MainActivity : AppCompatActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        changed()
        rebuild()
    }

    private lateinit var sections: LinearLayout
    private lateinit var previewHost: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Opened as the widget's configuration screen: accept and draw it right away
        // (Android doesn't send the first update itself when a widget has a config screen).
        val wid = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (wid != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
        }
        AgendaWidget.updateAll(this)

        setContentView(R.layout.activity_main)
        sections = findViewById(R.id.sections)
        previewHost = findViewById(R.id.preview_host)

        val content = findViewById<View>(R.id.content)
        val top = content.paddingTop
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scroll)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(content.paddingLeft, top + bars.top, content.paddingRight, bottom + bars.bottom)
            insets
        }

        previewHost.background = wallpaper(dp(28).toFloat())

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        findViewById<TextView>(R.id.footer).text = getString(R.string.footer, version)
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    override fun onPause() {
        super.onPause()
        AgendaWidget.updateAll(this)
    }

    /** Settings changed: refresh widget + preview + status. */
    private fun changed() {
        AgendaWidget.updateAll(this)
        renderPreview()
    }

    // ------------------------------------------------------------------ build

    private fun rebuild() {
        renderPreview()
        sections.removeAllViews()
        val perm = Agenda.hasPerm(this)
        val items = Agenda.items(this)

        // Status
        section(R.string.sec_status) {
            row(getString(R.string.st_access),
                value = getString(if (perm) R.string.st_granted else R.string.st_denied),
                valueColor = if (perm) attr(M.attr.colorPrimary) else attr(M.attr.colorError),
                onClick = if (perm) null else ({ permLauncher.launch(Manifest.permission.READ_CALENDAR) }))
            row(getString(R.string.st_widgets), value = AgendaWidget.count(this@MainActivity).toString())
            row(getString(R.string.st_events), value = if (perm) items.size.toString() else getString(R.string.st_never))
            val last = Prefs.lastRefresh(this@MainActivity)
            row(getString(R.string.st_last),
                value = if (last == 0L) getString(R.string.st_never) else DateFormat.getTimeFormat(this@MainActivity).format(Date(last)))
            row(getString(R.string.st_refresh), accent = true, chevron = true) {
                changed(); rebuild()
                Toast.makeText(this@MainActivity, R.string.refreshed, Toast.LENGTH_SHORT).show()
            }
            row(getString(R.string.pin_btn), accent = true, chevron = true) { pinWidget() }
        }

        // Look
        section(R.string.sec_look) { add(themeTiles()) }

        // Content
        section(R.string.sec_content) {
            choiceRow(R.string.days, "days", Prefs.days(this@MainActivity).toString(),
                listOf("7" to null, "14" to null, "30" to null))
            switchRow(getString(R.string.show_location), Prefs.showLocation(this@MainActivity)) {
                Prefs.put(this@MainActivity, "location", it); changed()
            }
            switchRow(getString(R.string.show_countdown), Prefs.showCountdown(this@MainActivity)) {
                Prefs.put(this@MainActivity, "countdown", it); changed()
            }
        }

        // Calendars
        section(R.string.sec_calendars) {
            if (!perm) {
                row(getString(R.string.calendars_need_perm), chevron = true) {
                    permLauncher.launch(Manifest.permission.READ_CALENDAR)
                }
            } else {
                val hidden = Prefs.hidden(this@MainActivity).toMutableSet()
                Agenda.calendars(this@MainActivity).forEach { cal ->
                    switchRow(cal.name, cal.id !in hidden,
                        subtitle = cal.account.takeIf { it.isNotEmpty() && it != cal.name },
                        dotColor = cal.color) { on ->
                        if (on) hidden.remove(cal.id) else hidden.add(cal.id)
                        Prefs.setHidden(this@MainActivity, hidden)
                        changed()
                    }
                }
            }
        }

        // Language
        section(R.string.sec_language) {
            choiceRow(R.string.app_language, "lang", Prefs.lang(this@MainActivity),
                listOf("auto" to R.string.lang_auto, "ro" to R.string.lang_ro, "en" to R.string.lang_en)) {
                App.applyLang(it)
            }
        }
    }

    // ------------------------------------------------------------------ preview

    /** The real widget layouts, filled with real data, so what you see is what you get. */
    private fun renderPreview() {
        if (!::previewHost.isInitialized) return
        previewHost.removeAllViews()
        val p = Palettes.of(this)
        val v = layoutInflater.inflate(R.layout.widget_agenda, previewHost, false)
        v.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        v.findViewById<ImageView>(R.id.bg).apply { imageTintList = null; setColorFilter(p.bg); imageAlpha = p.bgAlpha }
        v.findViewById<ImageView>(R.id.header_line).apply { imageTintList = null; setColorFilter(p.lineRgb); imageAlpha = p.lineAlpha }
        for (id in intArrayOf(R.id.ic_cal, R.id.btn_add, R.id.btn_refresh)) {
            v.findViewById<ImageView>(id).apply { imageTintList = null; setColorFilter(p.text) }
        }
        val loc = Loc.locale(this)
        v.findViewById<TextView>(R.id.weekday).apply {
            text = java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", loc).format(java.time.LocalDate.now()).cap(loc)
            setTextColor(p.text)
        }

        val frame = v.findViewById<View>(R.id.list).parent as FrameLayout
        frame.removeAllViews()
        frame.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(10)) }
        frame.addView(box)

        val rows = Agenda.rows(this).take(4)
        if (rows.isEmpty()) {
            box.addView(TextView(this).apply {
                text = Loc.ctx(this@MainActivity).getString(
                    if (Agenda.hasPerm(this@MainActivity)) R.string.empty_days else R.string.need_permission,
                    Prefs.days(this@MainActivity))
                setTextColor(p.muted); gravity = Gravity.CENTER; textSize = 14f
                setPadding(dp(16), dp(28), dp(16), dp(24))
            })
        }
        rows.forEachIndexed { i, row ->
            val r = layoutInflater.inflate(R.layout.widget_row_event, box, false)
            r.findViewById<TextView>(R.id.date).apply { text = row.date; setTextColor(if (row.today) p.accent else p.muted) }
            r.findViewById<TextView>(R.id.title).apply { text = row.item.title; setTextColor(p.text) }
            r.findViewById<TextView>(R.id.sub).apply {
                if (row.sub.isEmpty()) visibility = View.GONE
                else { text = row.sub; setTextColor(if (row.ongoing) p.accent else p.muted) }
            }
            r.findViewById<ImageView>(R.id.dot).setColorFilter(row.item.color)
            r.findViewById<ImageView>(R.id.line).apply {
                imageTintList = null; setColorFilter(p.lineRgb); imageAlpha = p.lineAlpha
                visibility = if (i == rows.lastIndex) View.INVISIBLE else View.VISIBLE
            }
            box.addView(r)
        }
        previewHost.addView(v)
    }

    /** Four tappable mini-widgets, one per style. */
    private fun themeTiles(): View {
        val names = mapOf("glass" to R.string.style_glass, "graphite" to R.string.style_graphite,
            "paper" to R.string.style_paper, "you" to R.string.style_you)
        val current = Prefs.style(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(12))
        }
        Palettes.STYLES.forEach { st ->
            val p = Palettes.of(this, st)
            val selected = st == current
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
                setOnClickListener {
                    if (st != Prefs.style(this@MainActivity)) {
                        Prefs.put(this@MainActivity, "style", st)
                        changed(); rebuild()
                    }
                }
            }
            val tile = FrameLayout(this).apply {
                background = wallpaper(dp(16).toFloat()).apply {
                    if (selected) setStroke(dp(2), attr(M.attr.colorPrimary))
                }
                setPadding(dp(7), dp(7), dp(7), dp(7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84))
            }
            val mini = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor((p.bg and 0x00FFFFFF) or (p.bgAlpha shl 24))
                }
                setPadding(dp(8), dp(8), dp(8), dp(6))
            }
            fun bar(w: Int, color: Int, h: Int = 4, top: Int = 0) = View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(h)).apply { topMargin = dp(top) }
            }
            mini.addView(bar(30, p.text, 5))
            listOf(0xFFC9A7F5.toInt(), 0xFF7BC96F.toInt(), 0xFFF26B5B.toInt()).forEachIndexed { i, dot ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(if (i == 0) 9 else 6)
                    }
                }
                line.addView(bar(8, if (i == 0) p.accent else p.muted, 3))
                line.addView(bar(if (i == 1) 18 else 24, p.text, 3).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(5); weight = 0f }
                })
                line.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
                line.addView(View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) }
                    layoutParams = LinearLayout.LayoutParams(dp(5), dp(5))
                })
                mini.addView(line)
            }
            tile.addView(mini, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            col.addView(tile)
            col.addView(text(getString(names.getValue(st)), M.attr.textAppearanceLabelMedium,
                if (selected) attr(M.attr.colorPrimary) else attr(M.attr.colorOnSurfaceVariant)).apply {
                setPadding(0, dp(8), 0, 0)
                maxLines = 1
            })
            row.addView(col)
        }
        return row
    }

    /** A dark "home screen" behind previews, so Glass reads correctly. */
    private fun wallpaper(radius: Float) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF55555B.toInt(), 0xFF1A1A1D.toInt(), 0xFF0B0B0C.toInt()),
    ).apply { cornerRadius = radius }

    // ------------------------------------------------------------------ table UI

    private inner class Section(val box: LinearLayout) {
        private var count = 0

        fun add(v: View) {
            if (count++ > 0) box.addView(View(this@MainActivity).apply {
                setBackgroundColor(attr(M.attr.colorOutlineVariant))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(16); marginEnd = dp(16)
                }
            })
            box.addView(v)
        }

        fun row(
            title: String,
            value: String? = null,
            valueColor: Int? = null,
            subtitle: String? = null,
            accent: Boolean = false,
            chevron: Boolean = false,
            dotColor: Int? = null,
            trailing: View? = null,
            onClick: (() -> Unit)? = null,
        ): LinearLayout {
            val r = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(16), dp(10), dp(12), dp(10))
                if (onClick != null) {
                    val tv = TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener { onClick() }
                }
            }
            if (dotColor != null) r.addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(14) }
            })
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(text(title, M.attr.textAppearanceBodyLarge,
                if (accent) attr(M.attr.colorPrimary) else attr(M.attr.colorOnSurface)))
            if (subtitle != null) col.addView(text(subtitle, M.attr.textAppearanceBodySmall, attr(M.attr.colorOnSurfaceVariant)))
            r.addView(col)
            if (value != null) r.addView(text(value, M.attr.textAppearanceBodyMedium,
                valueColor ?: attr(M.attr.colorOnSurfaceVariant)).apply {
                setPadding(dp(12), 0, if (chevron) dp(2) else dp(4), 0)
            })
            if (trailing != null) r.addView(trailing)
            if (chevron) r.addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(attr(M.attr.colorOnSurfaceVariant))
                alpha = 0.6f
            })
            add(r)
            return r
        }

        fun choiceRow(
            titleRes: Int, key: String, current: String,
            options: List<Pair<String, Int?>>, after: ((String) -> Unit)? = null,
        ) {
            val labels = options.map { (v, res) -> if (res != null) getString(res) else v }
            val idx = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
            val title = getString(titleRes)
            row(title, value = labels[idx], chevron = true) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(title)
                    .setSingleChoiceItems(labels.toTypedArray(), idx) { d, which ->
                        d.dismiss()
                        val v = options[which].first
                        if (v == current) return@setSingleChoiceItems
                        if (key == "days") Prefs.put(this@MainActivity, key, v.toInt()) else Prefs.put(this@MainActivity, key, v)
                        changed()
                        rebuild()
                        after?.invoke(v)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        fun switchRow(
            title: String, checked: Boolean, subtitle: String? = null, dotColor: Int? = null,
            onChange: (Boolean) -> Unit,
        ) {
            val sw = MaterialSwitch(this@MainActivity).apply { isChecked = checked }
            val r = row(title, subtitle = subtitle, dotColor = dotColor, trailing = sw) { sw.toggle() }
            r.setPadding(r.paddingLeft, dp(4), dp(8), dp(4))
            sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        }
    }

    private fun section(titleRes: Int, build: Section.() -> Unit) {
        sections.addView(text(getString(titleRes), M.attr.textAppearanceLabelLarge, attr(M.attr.colorPrimary)).apply {
            setPadding(dp(16), dp(24), dp(16), dp(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(attr(M.attr.colorSurfaceContainerLow))
                setStroke(1, attr(M.attr.colorOutlineVariant))
            }
            clipToOutline = true
        }
        Section(card).build()
        sections.addView(card)
    }

    // ------------------------------------------------------------------ helpers

    private fun text(s: String, appearanceAttr: Int, color: Int) = TextView(this).apply {
        val tv = TypedValue()
        theme.resolveAttribute(appearanceAttr, tv, true)
        setTextAppearance(tv.resourceId)
        text = s
        setTextColor(color)
    }

    private fun attr(a: Int) = MaterialColors.getColor(this, a, 0xFF888888.toInt())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pinWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, AgendaWidget::class.java), null, AgendaWidget.refreshIntent(this))
        } else {
            Toast.makeText(this, R.string.pin_manual, Toast.LENGTH_LONG).show()
        }
    }
}
