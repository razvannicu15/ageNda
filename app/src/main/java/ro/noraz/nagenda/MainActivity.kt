package ro.noraz.nagenda

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermUi()
        buildCalendarList()
        AgendaWidget.updateAll(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Opened as the widget's (optional) configuration screen: always accept.
        val wid = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (wid != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
        }

        setContentView(R.layout.activity_main)
        val content = findViewById<View>(R.id.content)
        val pad = content.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scroll)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(content.paddingLeft, pad + bars.top, content.paddingRight, pad + bars.bottom)
            insets
        }

        findViewById<MaterialButton>(R.id.btn_perm).setOnClickListener {
            permLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
        findViewById<MaterialButton>(R.id.btn_pin).setOnClickListener { pinWidget() }

        bindToggle(R.id.tg_theme, mapOf(R.id.th_auto to "auto", R.id.th_light to "light", R.id.th_dark to "dark"),
            Prefs.theme(this)) { v ->
            Prefs.put(this, "theme", v); AgendaWidget.updateAll(this); App.applyTheme(v)
        }
        bindToggle(R.id.tg_colors, mapOf(R.id.co_dynamic to "dynamic", R.id.co_neutral to "neutral"),
            Prefs.colors(this)) { v ->
            Prefs.put(this, "colors", v); AgendaWidget.updateAll(this)
        }
        bindToggle(R.id.tg_days, mapOf(R.id.d7 to "7", R.id.d14 to "14", R.id.d30 to "30"),
            Prefs.days(this).toString()) { v ->
            Prefs.put(this, "days", v.toInt()); AgendaWidget.updateAll(this)
        }
        bindToggle(R.id.tg_lang, mapOf(R.id.la_auto to "auto", R.id.la_ro to "ro", R.id.la_en to "en"),
            Prefs.lang(this)) { v ->
            Prefs.put(this, "lang", v); AgendaWidget.updateAll(this); App.applyLang(v)
        }

        findViewById<Slider>(R.id.sl_opacity).apply {
            value = (Prefs.opacity(this@MainActivity).coerceIn(30, 100) / 5 * 5).toFloat()
            setLabelFormatter { "${it.toInt()}%" }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    Prefs.put(this@MainActivity, "opacity", slider.value.toInt())
                    AgendaWidget.updateAll(this@MainActivity)
                }
            })
        }

        findViewById<MaterialSwitch>(R.id.sw_location).apply {
            isChecked = Prefs.showLocation(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.put(this@MainActivity, "location", on); AgendaWidget.updateAll(this@MainActivity)
            }
        }
        findViewById<MaterialSwitch>(R.id.sw_countdown).apply {
            isChecked = Prefs.showCountdown(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.put(this@MainActivity, "countdown", on); AgendaWidget.updateAll(this@MainActivity)
            }
        }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        findViewById<TextView>(R.id.footer).text = getString(R.string.footer, version)
    }

    override fun onResume() {
        super.onResume()
        refreshPermUi()
        buildCalendarList()
    }

    private fun bindToggle(groupId: Int, map: Map<Int, String>, current: String, onPick: (String) -> Unit) {
        val g = findViewById<MaterialButtonToggleGroup>(groupId)
        map.entries.firstOrNull { it.value == current }?.let { g.check(it.key) }
        var last = current
        g.addOnButtonCheckedListener { _, id, checked ->
            val v = map[id]
            if (checked && v != null && v != last) {
                last = v
                onPick(v)
            }
        }
    }

    private fun refreshPermUi() {
        findViewById<MaterialCardView>(R.id.card_perm).visibility =
            if (Agenda.hasPerm(this)) View.GONE else View.VISIBLE
    }

    private fun buildCalendarList() {
        val box = findViewById<LinearLayout>(R.id.cal_list)
        box.removeAllViews()
        if (!Agenda.hasPerm(this)) {
            box.addView(TextView(this).apply {
                setText(R.string.calendars_need_perm)
                alpha = 0.7f
            })
            return
        }
        val hidden = Prefs.hidden(this).toMutableSet()
        Agenda.calendars(this).forEach { cal ->
            box.addView(MaterialCheckBox(this).apply {
                text = if (cal.account.isNotEmpty() && cal.account != cal.name) "${cal.name}  ·  ${cal.account}" else cal.name
                buttonTintList = ColorStateList.valueOf(cal.color)
                isChecked = cal.id !in hidden
                setOnCheckedChangeListener { _, on ->
                    if (on) hidden.remove(cal.id) else hidden.add(cal.id)
                    Prefs.setHidden(this@MainActivity, hidden)
                    AgendaWidget.updateAll(this@MainActivity)
                }
            })
        }
    }

    private fun pinWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, AgendaWidget::class.java), null, null)
        } else {
            Toast.makeText(this, R.string.pin_manual, Toast.LENGTH_LONG).show()
        }
    }
}
