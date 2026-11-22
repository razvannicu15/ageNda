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
import com.google.android.material.button.MaterialButtonToggleGroup
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

        header(R.string.sec_widgets)
        listOf(
            Triple(AgendaWidget::class.java, R.string.preview_agenda, R.drawable.so_agenda),
            Triple(WeatherWidget::class.java, R.string.widget_weather_name, R.drawable.si_clock),
            Triple(ClockWidget::class.java, R.string.preview_all, R.drawable.so_widgets),
        ).forEach { (cls, title, ic) ->
            val n = AgendaWidget.countOf(ctx, cls)
            item(ic, getString(title), value = if (n > 0) "✓" else null, valueColor = attr(M.attr.colorPrimary),
                trailing = ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_add)
                    imageTintList = ColorStateList.valueOf(attr(M.attr.colorPrimary))
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(12) }
                }) { pin(cls) }
        }

        header(R.string.sec_look)
        sections.addView(themeTiles())
        segmented(R.drawable.so_text, getString(R.string.text_size), Prefs.size(ctx),
            listOf("normal" to getString(R.string.size_normal), "large" to getString(R.string.size_large))) { Prefs.put(ctx, "size", it) }
        segmented(R.drawable.si_dot, getString(R.string.dot_pos), Prefs.dot(ctx),
            listOf("start" to getString(R.string.dot_start_s), "mid" to getString(R.string.dot_mid_s), "end" to getString(R.string.dot_end_s))) { Prefs.put(ctx, "dot", it) }

        header(R.string.sec_agenda)
        if (!perm) {
            item(R.drawable.si_lock, getString(R.string.st_access), value = getString(R.string.perm_btn), valueColor = attr(M.attr.colorPrimary)) {
                calPerm.launch(Manifest.permission.READ_CALENDAR)
            }
        } else {
            val cals = Agenda.calendars(ctx)
            val hidden = Prefs.hidden(ctx)
            val on = cals.count { it.id !in hidden }
            item(R.drawable.si_calendar, getString(R.string.sec_calendars),
                value = if (on == cals.size) getString(R.string.calendars_all) else "$on / ${cals.size}") { pickCalendars(cals) }
        }
        segmented(R.drawable.si_hourglass, getString(R.string.days), Prefs.days(ctx).toString(),
            listOf("7" to "7", "14" to "14", "30" to "30")) { Prefs.put(ctx, "days", it.toInt()) }
        switchItem(R.drawable.ic_pin_outline, getString(R.string.show_location), Prefs.showLocation(ctx)) { Prefs.put(ctx, "location", it) }
        switchItem(R.drawable.so_agenda, getString(R.string.show_countdown), Prefs.showCountdown(ctx)) { Prefs.put(ctx, "countdown", it) }

        header(R.string.sec_weather_data)
        item(R.drawable.ic_pin_outline, getString(R.string.location),
            value = Prefs.placeName(ctx).ifEmpty { getString(R.string.wx_set_place) },
            valueColor = if (Weather.hasPlace(ctx)) null else attr(M.attr.colorPrimary)) { pickLocation() }
        segmented(R.drawable.si_thermo, getString(R.string.units), Prefs.units(ctx), listOf("c" to "°C", "f" to "°F")) {
            Prefs.put(ctx, "units", it); Weather.clear(ctx); Weather.refreshIfStale(ctx, force = true)
        }
        val chosen = Prefs.weatherApp(ctx)
        item(R.drawable.so_cloud, getString(R.string.weather_app),
            value = if (chosen.isNotEmpty()) Apps.label(ctx, chosen)
            else Apps.detectedWeatherPkg(ctx)?.let { Apps.label(ctx, it) } ?: getString(R.string.weather_app_auto)) { pickWeatherApp() }

        header(R.string.sec_language)
        segmented(null, null, Prefs.lang(ctx),
            listOf("auto" to getString(R.string.lang_auto), "ro" to getString(R.string.lang_ro), "en" to getString(R.string.lang_en))) {
            Prefs.put(ctx, "lang", it); App.applyLang(it)
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
            val dot = Prefs.dot(this)
            RowParts.DOTS.forEach { (k, id) ->
                r.findViewById<ImageView>(id).apply { visibility = if (k == dot) View.VISIBLE else View.GONE; setColorFilter(row.item.color) }
            }
            RowParts.LINES.forEach { (k, id) ->
                r.findViewById<ImageView>(id).apply {
                    imageTintList = null; setColorFilter(p.lineRgb); imageAlpha = p.lineAlpha
                    visibility = if (k != dot) View.GONE else if (i == rows.lastIndex) View.INVISIBLE else View.VISIBLE
                }
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
            setPadding(dp(12), dp(6), dp(12), dp(10))
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
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
            })
            row.addView(col)
        }
        return row
    }

    private fun wallpaper(radius: Float) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF55555B.toInt(), 0xFF1A1A1D.toInt(), 0xFF0B0B0C.toInt()),
    ).apply { cornerRadius = radius }

    // ================================================================== list UI (same style as NetScan)

    private fun header(res: Int) {
        sections.addView(text(getString(res), M.attr.textAppearanceTitleSmall, attr(M.attr.colorPrimary)).apply {
            setPadding(dp(16), dp(28), dp(16), dp(6))
        })
    }

    private fun item(
        icon: Int, title: String, value: String? = null, valueColor: Int? = null,
        trailing: View? = null, onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            if (onClick != null) { background = ripple(); setOnClickListener { onClick() } }
        }
        r.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(attr(M.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(20) }
        })
        r.addView(text(title, M.attr.textAppearanceBodyLarge, attr(M.attr.colorOnSurface)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (value != null) r.addView(text(value, M.attr.textAppearanceBodyMedium, valueColor ?: attr(M.attr.colorOnSurfaceVariant)).apply {
            setPadding(dp(12), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(160)
        })
        if (trailing != null) r.addView(trailing)
        sections.addView(r)
        return r
    }

    private fun switchItem(icon: Int, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = MaterialSwitch(this).apply { isChecked = checked }
        item(icon, title, trailing = sw) { sw.toggle() }
        sw.setOnCheckedChangeListener { _, on -> onChange(on); changed() }
    }

    /** Title row + full-width segmented buttons, like NetScan's language/theme pickers. */
    private fun segmented(icon: Int?, title: String?, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
        if (icon != null && title != null) item(icon, title).apply {
            minimumHeight = dp(44); setPadding(paddingLeft, dp(6), paddingRight, dp(2))
        }
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(10)
            }
        }
        val ids = options.map { (k, label) ->
            val b = MaterialButton(this, null, M.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = label
                isAllCaps = false
                maxLines = 1
                minWidth = 0; minimumWidth = 0
                this.icon = null; iconPadding = 0
                setPaddingRelative(dp(4), paddingTop, dp(4), paddingBottom)
                // never truncate: shrink the label instead when the system font is large
                setAutoSizeTextTypeUniformWithConfiguration(10, 16, 1, TypedValue.COMPLEX_UNIT_SP)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            group.addView(b)
            b.id to k
        }.toMap()
        ids.entries.firstOrNull { it.value == current }?.let { group.check(it.key) }
        group.addOnButtonCheckedListener { _, id, checked ->
            val k = ids[id]
            if (checked && k != null && k != current) { onPick(k); changed(); ui.post { rebuild() } }
        }
        sections.addView(group)
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
