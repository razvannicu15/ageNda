package ro.noraz.nagenda

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import com.google.android.material.R as M

class MainActivity : AppCompatActivity() {

    // iOS-like icon chip colours
    private val INDIGO = 0xFF5E5CE6.toInt()
    private val RED = 0xFFFF453A.toInt()
    private val ORANGE = 0xFFFF9F0A.toInt()
    private val GREEN = 0xFF30D158.toInt()
    private val TEAL = 0xFF40C8E0.toInt()
    private val BLUE = 0xFF0A84FF.toInt()
    private val GRAY = 0xFF8E8E93.toInt()

    private val calPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        changed(); rebuild()
    }
    private var onLocPerm: ((Boolean) -> Unit)? = null
    private val locPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        onLocPerm?.invoke(ok); onLocPerm = null
    }

    private lateinit var sections: LinearLayout
    private lateinit var previewHost: FrameLayout
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Opened as a widget's configuration screen: accept and draw it right away
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
        previewHost.background = wallpaper(dp(28).toFloat())

        val content = findViewById<View>(R.id.content)
        val top = content.paddingTop
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scroll)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(content.paddingLeft, top + bars.top, content.paddingRight, bottom + bars.bottom)
            insets
        }
        findViewById<TextView>(R.id.footer).text = getString(R.string.footer, version())
    }

    override fun onResume() {
        super.onResume()
        Weather.refreshIfStale(this)
        rebuild()
    }

    override fun onPause() {
        super.onPause()
        AgendaWidget.updateAll(this)
    }

    private fun changed() {
        AgendaWidget.updateAll(this)
        renderPreview()
    }

    private fun version() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // ================================================================== settings

    private fun rebuild() {
        renderPreview()
        sections.removeAllViews()
        val perm = Agenda.hasPerm(this)
        val ctx = this

        section(R.string.sec_widgets) {
            listOf(
                Triple(AgendaWidget::class.java, R.string.add_agenda, R.drawable.si_list),
                Triple(WeatherWidget::class.java, R.string.add_weather, R.drawable.si_cloud),
                Triple(ClockWidget::class.java, R.string.add_clock, R.drawable.si_widgets),
            ).forEach { (cls, title, ic) ->
                val n = AgendaWidget.countOf(ctx, cls)
                row(getString(title), icon = ic, chip = INDIGO, subtitle = getString(R.string.add_hint),
                    value = countLabel(n), valueColor = attr(M.attr.colorPrimary), chevron = true) { pin(cls) }
            }
        }

        section(R.string.sec_look) {
            add(themeTiles())
            choiceRow(getString(R.string.text_size), R.drawable.si_text, ORANGE, Prefs.size(ctx),
                listOf("normal" to getString(R.string.size_normal), "large" to getString(R.string.size_large))) {
                Prefs.put(ctx, "size", it)
            }
        }

        section(R.string.sec_agenda) {
            if (!perm) {
                row(getString(R.string.st_access), icon = R.drawable.si_lock, chip = RED,
                    value = getString(R.string.st_denied), valueColor = attr(M.attr.colorError), chevron = true) {
                    calPerm.launch(Manifest.permission.READ_CALENDAR)
                }
            } else {
                val cals = Agenda.calendars(ctx)
                val hidden = Prefs.hidden(ctx)
                val n = Agenda.items(ctx).size
                val on = cals.count { it.id !in hidden }
                row(getString(R.string.sec_calendars), icon = R.drawable.si_calendar, chip = RED,
                    subtitle = getString(R.string.events_found, n),
                    value = if (on == cals.size) getString(R.string.calendars_all) else "$on/${cals.size}",
                    chevron = true) { pickCalendars(cals) }
            }
            choiceRow(getString(R.string.days), R.drawable.si_hourglass, ORANGE, Prefs.days(ctx).toString(),
                listOf("7" to "7", "14" to "14", "30" to "30")) { Prefs.put(ctx, "days", it.toInt()) }
            switchRow(getString(R.string.show_location), R.drawable.si_pin, GREEN, Prefs.showLocation(ctx)) {
                Prefs.put(ctx, "location", it); changed()
            }
            switchRow(getString(R.string.show_countdown), R.drawable.si_list, TEAL, Prefs.showCountdown(ctx)) {
                Prefs.put(ctx, "countdown", it); changed()
            }
        }

        section(R.string.sec_weather) {
            row(getString(R.string.location), icon = R.drawable.si_pin, chip = BLUE,
                value = Prefs.placeName(ctx).ifEmpty { getString(R.string.wx_set_place) },
                valueColor = if (Weather.hasPlace(ctx)) null else attr(M.attr.colorPrimary),
                chevron = true) { pickLocation() }
            choiceRow(getString(R.string.units), R.drawable.si_thermo, ORANGE, Prefs.units(ctx),
                listOf("c" to getString(R.string.units_c), "f" to getString(R.string.units_f)),
                short = mapOf("c" to "°C", "f" to "°F")) {
                Prefs.put(ctx, "units", it)
                Weather.clear(ctx); Weather.refreshIfStale(ctx, force = true)
            }
            val chosen = Prefs.weatherApp(ctx)
            val wxLabel = if (chosen.isNotEmpty()) Apps.label(ctx, chosen)
            else Apps.detectedWeatherPkg(ctx)?.let { getString(R.string.weather_app_auto_found, Apps.label(ctx, it)) }
                ?: getString(R.string.weather_app_auto)
            row(getString(R.string.weather_app), icon = R.drawable.si_cloud, chip = TEAL, value = wxLabel, chevron = true) {
                pickWeatherApp()
            }
            row(getString(R.string.clock_tap), icon = R.drawable.si_clock, chip = GRAY, value = getString(R.string.clock_tap_value))
        }

        section(R.string.sec_general) {
            if (perm) row(getString(R.string.st_access), icon = R.drawable.si_lock, chip = GREEN, value = getString(R.string.st_granted))
            choiceRow(getString(R.string.app_language), R.drawable.si_globe, BLUE, Prefs.lang(ctx),
                listOf("auto" to getString(R.string.lang_auto), "ro" to getString(R.string.lang_ro), "en" to getString(R.string.lang_en))) {
                Prefs.put(ctx, "lang", it)
                App.applyLang(it)
            }
            val last = Prefs.lastRefresh(ctx)
            row(getString(R.string.st_refresh), icon = R.drawable.ic_refresh, chip = GRAY,
                subtitle = getString(R.string.st_last) + ": " +
                    (if (last == 0L) getString(R.string.st_never) else DateFormat.getTimeFormat(ctx).format(Date(last))),
                accent = true) {
                Weather.refreshIfStale(ctx, force = true)
                changed(); rebuild()
                Toast.makeText(ctx, R.string.refreshed, Toast.LENGTH_SHORT).show()
            }
        }

        section(R.string.about) {
            row("ageNda ${version()}", icon = R.drawable.si_info, chip = INDIGO, subtitle = getString(R.string.about_text))
        }
    }

    private fun countLabel(n: Int) = if (n > 0) "✓ $n" else null

    // ================================================================== dialogs

    private fun pickCalendars(cals: List<Cal>) {
        if (cals.isEmpty()) return
        val hidden = Prefs.hidden(this).toMutableSet()
        val names = cals.map { if (it.account.isNotEmpty() && it.account != it.name) "${it.name} · ${it.account}" else it.name }
        val checked = cals.map { it.id !in hidden }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sec_calendars)
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, on ->
                if (on) hidden.remove(cals[i].id) else hidden.add(cals[i].id)
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> Prefs.setHidden(this, hidden); changed(); rebuild() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickLocation() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val til = TextInputLayout(this).apply {
            hint = getString(R.string.search_city)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
            startIconDrawable = getDrawable(R.drawable.si_search)
        }
        val et = EditText(this).apply { maxLines = 1; isSingleLine = true }
        til.addView(et)
        box.addView(til)
        val mine = MaterialButton(this, null, M.attr.borderlessButtonStyle).apply {
            text = getString(R.string.use_my_location)
            icon = getDrawable(R.drawable.si_mylocation)
        }
        box.addView(mine)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(results)

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .show()

        fun choose(name: String, lat: Double, lon: Double) {
            Prefs.setPlace(this, name, lat, lon)
            Weather.clear(this)
            Weather.refreshIfStale(this, force = true)
            changed(); rebuild(); dlg.dismiss()
        }

        var pending: Runnable? = null
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { ui.removeCallbacks(it) }
                val q = s?.toString()?.trim().orEmpty()
                if (q.length < 2) { results.removeAllViews(); return }
                val task = Runnable {
                    val lang = Loc.locale(this@MainActivity).language
                    Thread {
                        val list = Weather.search(q, lang)
                        ui.post {
                            results.removeAllViews()
                            if (list.isEmpty()) results.addView(text(getString(R.string.no_results),
                                M.attr.textAppearanceBodyMedium, attr(M.attr.colorOnSurfaceVariant)).apply { setPadding(dp(4), dp(12), 0, dp(12)) })
                            list.forEach { pl ->
                                results.addView(LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    setPadding(dp(4), dp(10), dp(4), dp(10))
                                    background = ripple()
                                    addView(text(pl.name, M.attr.textAppearanceBodyLarge, attr(M.attr.colorOnSurface)))
                                    if (pl.detail.isNotEmpty()) addView(text(pl.detail, M.attr.textAppearanceBodySmall, attr(M.attr.colorOnSurfaceVariant)))
                                    setOnClickListener { choose(pl.name, pl.lat, pl.lon) }
                                })
                            }
                        }
                    }.start()
                }
                pending = task
                ui.postDelayed(task, 400)
            }
        })

        mine.setOnClickListener {
            onLocPerm = { ok -> if (ok) locate { loc -> nameFor(loc) { choose(it, loc.latitude, loc.longitude) } } else failLoc() }
            locPerm.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun failLoc() = Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show()

    @SuppressLint("MissingPermission")
    private fun locate(done: (Location) -> Unit) {
        val lm = getSystemService(LocationManager::class.java)
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 3 * 60 * 60 * 1000L) { done(last); return }
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) { if (last != null) done(last) else failLoc(); return }
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                when { loc != null -> done(loc); last != null -> done(last); else -> failLoc() }
            }
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, { loc -> done(loc) }, Looper.getMainLooper())
        }
    }

    private fun nameFor(loc: Location, done: (String) -> Unit) {
        Thread {
            val name = try {
                @Suppress("DEPRECATION")
                Geocoder(this, Loc.locale(this)).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let {
                    it.locality ?: it.subAdminArea ?: it.adminArea
                }
            } catch (_: Exception) { null } ?: String.format(Locale.US, "%.2f, %.2f", loc.latitude, loc.longitude)
            ui.post { done(name) }
        }.start()
    }

    private fun pickWeatherApp() {
        val apps = Apps.launchable(this)
        val auto = Apps.detectedWeatherPkg(this)?.let { getString(R.string.weather_app_auto_found, Apps.label(this, it)) }
            ?: getString(R.string.weather_app_auto)
        val adapter = object : BaseAdapter() {
            override fun getCount() = apps.size + 1
            override fun getItem(p: Int): Any = p
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), dp(10), dp(24), dp(10))
                    addView(ImageView(this@MainActivity).apply {
                        if (p == 0) {
                            setImageResource(R.drawable.si_cloud)
                            imageTintList = ColorStateList.valueOf(attr(M.attr.colorPrimary))
                            setPadding(dp(6), dp(6), dp(6), dp(6))
                        } else setImageDrawable(apps[p - 1].icon)
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(16) }
                    })
                    addView(text(if (p == 0) auto else apps[p - 1].label, M.attr.textAppearanceBodyLarge, attr(M.attr.colorOnSurface)))
                }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.weather_app)
            .setAdapter(adapter) { _, p ->
                Prefs.put(this, "wx_app", if (p == 0) "" else apps[p - 1].pkg)
                changed(); rebuild()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pin(cls: Class<*>) {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, cls), null, AgendaWidget.refreshIntent(this))
        } else {
            Toast.makeText(this, R.string.pin_manual, Toast.LENGTH_LONG).show()
        }
    }

    // ================================================================== preview

    /** The real widget layouts filled with real data, on a dark "home screen". */
    private fun renderPreview() {
        if (!::previewHost.isInitialized) return
        previewHost.removeAllViews()
        val kind = Prefs.previewKind(this).let { if (it in listOf("agenda", "weather", "clock")) it else "clock" }
        val p = Palettes.of(this)

        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(previewSwitch(kind))

        val layout = when (kind) { "agenda" -> R.layout.widget_agenda; "weather" -> R.layout.widget_clockonly; else -> R.layout.widget_clock }
        val v = layoutInflater.inflate(layout, wrap, false)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(18); bottomMargin = dp(4) }
        if (kind == "clock") {
            v.findViewById<View>(R.id.panel).layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(24) }
        }
        if (kind != "agenda") fillWeather(v)
        if (kind != "weather") fillAgenda(v, p)
        wrap.addView(v)
        previewHost.addView(wrap)
    }

    private fun previewSwitch(kind: String): View {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x33FFFFFF) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        listOf("agenda" to R.string.preview_agenda, "weather" to R.string.preview_weather, "clock" to R.string.preview_all).forEach { (k, res) ->
            val on = k == kind
            pill.addView(TextView(this).apply {
                text = getString(res)
                textSize = 13f
                setTextColor(if (on) 0xFF1C1C1E.toInt() else 0xE6FFFFFF.toInt())
                setPadding(dp(14), dp(6), dp(14), dp(6))
                if (on) background = GradientDrawable().apply { cornerRadius = dp(15).toFloat(); setColor(0xF2FFFFFF.toInt()) }
                setOnClickListener { if (!on) { Prefs.put(this@MainActivity, "preview", k); renderPreview() } }
            })
        }
        return pill
    }

    private fun fillWeather(v: View) {
        val w = Weather.cached(this)
        val has = Weather.hasPlace(this)
        val icon = v.findViewById<ImageView>(R.id.wx_icon)
        val temp = v.findViewById<TextView>(R.id.wx_temp)
        val cond = v.findViewById<TextView>(R.id.wx_cond)
        val hilo = v.findViewById<TextView>(R.id.wx_hilo)
        when {
            !has -> { temp.text = "—"; cond.text = getString(R.string.wx_set_place); hilo.text = ""; icon.visibility = View.GONE }
            w == null -> { temp.text = "—"; cond.text = getString(R.string.wx_loading); hilo.text = Prefs.placeName(this); icon.visibility = View.GONE }
            else -> {
                temp.text = "${w.temp}°"
                cond.text = Loc.ctx(this).getString(Weather.text(w.sym))
                hilo.text = "↑${w.max}° ↓${w.min}° · ${Prefs.placeName(this)}"
                icon.setImageResource(Weather.icon(w.sym)); icon.visibility = View.VISIBLE
            }
        }
        val f = Prefs.textScale(this)
        AgendaWidget.CLOCK_SIZES.forEach { (id, sz) ->
            v.findViewById<TextView>(id)?.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sz * f)
        }
        v.findViewById<View>(R.id.wx_block).setOnClickListener { if (!has) pickLocation() }
    }

    private fun fillAgenda(v: View, p: Palette) {
        v.findViewById<ImageView>(R.id.bg).apply { imageTintList = null; setColorFilter(p.bg); imageAlpha = p.bgAlpha }
        val frame = v.findViewById<View>(R.id.list).parent as FrameLayout
        frame.removeView(v.findViewById(R.id.list))
        frame.removeView(v.findViewById(R.id.empty))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
            val f = Prefs.textScale(this)
            r.findViewById<TextView>(R.id.date).apply {
                text = row.date; setTextColor(if (row.today) p.accent else p.muted); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f * f)
            }
            r.findViewById<TextView>(R.id.title).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f * f)
            r.findViewById<TextView>(R.id.sub).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f * f)
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
                background = GradientDrawable().apply {
                    cornerRadius = dp(19).toFloat()
                    setStroke(dp(2), if (selected) attr(M.attr.colorPrimary) else 0x00000000)
                }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88))
            }
            val inner = FrameLayout(this).apply {
                background = if (st == "glass") wallpaper(dp(15).toFloat())
                else GradientDrawable().apply { cornerRadius = dp(15).toFloat(); setColor(0) }
                clipToOutline = true
            }
            val mini = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat()
                    setColor((p.bg and 0x00FFFFFF) or (p.bgAlpha shl 24))
                    if (st == "paper") setStroke(1, attr(M.attr.colorOutlineVariant))
                }
                setPadding(dp(9), dp(10), dp(9), dp(6))
            }
            fun bar(w: Int, color: Int, h: Int = 4) = View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(h))
            }
            mini.addView(bar(30, p.text, 5))
            listOf(0xFFC9A7F5.toInt(), 0xFF7BC96F.toInt(), 0xFFF26B5B.toInt()).forEachIndexed { i, dot ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = dp(if (i == 0) 9 else 6) }
                }
                line.addView(bar(8, if (i == 0) p.accent else p.muted, 3))
                line.addView(bar(if (i == 1) 18 else 24, p.text, 3).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(5)
                })
                line.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
                line.addView(View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) }
                    layoutParams = LinearLayout.LayoutParams(dp(5), dp(5))
                })
                mini.addView(line)
            }
            inner.addView(mini, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            tile.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            col.addView(tile)
            col.addView(text(getString(names.getValue(st)), M.attr.textAppearanceLabelMedium,
                if (selected) attr(M.attr.colorPrimary) else attr(M.attr.colorOnSurfaceVariant)).apply {
                setPadding(0, dp(6), 0, 0); maxLines = 1
            })
            row.addView(col)
        }
        return row
    }

    private fun wallpaper(radius: Float) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF55555B.toInt(), 0xFF1A1A1D.toInt(), 0xFF0B0B0C.toInt()),
    ).apply { cornerRadius = radius }

    // ================================================================== table UI

    private inner class Section(val box: LinearLayout) {
        private var count = 0

        fun add(v: View) {
            if (count++ > 0) box.addView(View(this@MainActivity).apply {
                setBackgroundColor(attr(M.attr.colorOutlineVariant))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(62); marginEnd = dp(16)
                }
            })
            box.addView(v)
        }

        fun row(
            title: String,
            icon: Int? = null,
            chip: Int = GRAY,
            value: String? = null,
            valueColor: Int? = null,
            subtitle: String? = null,
            accent: Boolean = false,
            chevron: Boolean = false,
            trailing: View? = null,
            onClick: (() -> Unit)? = null,
        ): LinearLayout {
            val r = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(58)
                setPadding(dp(16), dp(10), dp(12), dp(10))
                if (onClick != null) { background = ripple(); setOnClickListener { onClick() } }
            }
            if (icon != null) r.addView(FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(chip) }
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14) }
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            })
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(text(title, M.attr.textAppearanceBodyLarge,
                if (accent) attr(M.attr.colorPrimary) else attr(M.attr.colorOnSurface)))
            if (subtitle != null) col.addView(text(subtitle, M.attr.textAppearanceBodySmall, attr(M.attr.colorOnSurfaceVariant))
                .apply { setPadding(0, dp(1), 0, 0) })
            r.addView(col)
            if (value != null) r.addView(text(value, M.attr.textAppearanceBodyMedium,
                valueColor ?: attr(M.attr.colorOnSurfaceVariant)).apply {
                setPadding(dp(12), 0, if (chevron) dp(2) else dp(4), 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(170)
            })
            if (trailing != null) r.addView(trailing)
            if (chevron) r.addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(attr(M.attr.colorOnSurfaceVariant))
                alpha = 0.55f
            })
            add(r)
            return r
        }

        fun choiceRow(
            title: String, icon: Int, chip: Int, current: String,
            options: List<Pair<String, String>>, short: Map<String, String>? = null,
            onPick: (String) -> Unit,
        ) {
            val idx = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
            val shown = short?.get(options[idx].first) ?: options[idx].second
            row(title, icon = icon, chip = chip, value = shown, chevron = true) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(title)
                    .setSingleChoiceItems(options.map { it.second }.toTypedArray(), idx) { d, which ->
                        d.dismiss()
                        val v = options[which].first
                        if (v != current) { onPick(v); changed(); rebuild() }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        fun switchRow(title: String, icon: Int, chip: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
            val sw = MaterialSwitch(this@MainActivity).apply { isChecked = checked }
            val r = row(title, icon = icon, chip = chip, trailing = sw) { sw.toggle() }
            r.setPadding(r.paddingLeft, dp(4), dp(8), dp(4))
            sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        }
    }

    private fun section(titleRes: Int, build: Section.() -> Unit) {
        sections.addView(text(getString(titleRes).uppercase(Loc.locale(this)), M.attr.textAppearanceLabelMedium,
            attr(M.attr.colorOnSurfaceVariant)).apply {
            letterSpacing = 0.06f
            setPadding(dp(18), dp(26), dp(16), dp(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(attr(M.attr.colorSurfaceContainerLow))
            }
            clipToOutline = true
        }
        Section(card).build()
        sections.addView(card)
    }

    // ================================================================== helpers

    private fun text(s: String, appearanceAttr: Int, color: Int) = TextView(this).apply {
        val tv = TypedValue()
        theme.resolveAttribute(appearanceAttr, tv, true)
        if (tv.resourceId != 0) setTextAppearance(tv.resourceId)
        text = s
        setTextColor(color)
    }

    private fun ripple() = TypedValue().let {
        theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        getDrawable(it.resourceId)
    }

    private fun attr(a: Int) = MaterialColors.getColor(this, a, 0xFF888888.toInt())
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
