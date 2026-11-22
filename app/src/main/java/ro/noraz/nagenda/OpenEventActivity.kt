package ro.noraz.nagenda

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Invisible hop: the widget's list needs an explicit target (Android 14+ rule), we forward to Calendar. */
class OpenEventActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val view = Intent(Intent.ACTION_VIEW, intent.data)
            intent.extras?.let { view.putExtras(it) }
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(view)
        } catch (_: Exception) { }
        finish()
    }
}
