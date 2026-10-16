package app.olo.calendar

import android.content.Context
import app.olo.calendar.model.Preferences

/** OLO's own settings in SharedPreferences. */
class AndroidPreferences(context: Context) : Preferences {
    private val sp = context.applicationContext.getSharedPreferences("olo_calendar", Context.MODE_PRIVATE)
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String?) {
        sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
