package ua.konditer.app

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Записує збої у файл, щоб їх можна було переслати розробнику з екрана налаштувань. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                logError(this, "Збій у потоці ${t.name}", e)
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        fun logFile(ctx: Context) = File(ctx.filesDir, "errors.log")

        fun logError(ctx: Context, what: String, e: Throwable?) {
            val f = logFile(ctx)
            if (f.length() > 200_000) f.writeText("")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText(
                "=== $time · v${BuildConfig.VERSION_NAME} · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                    "$what\n${e?.stackTraceToString() ?: ""}\n"
            )
        }
    }
}

/** Налаштування користувача. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun sensitivity(ctx: Context): Sensitivity =
        runCatching { Sensitivity.valueOf(sp(ctx).getString("sensitivity", null) ?: "") }
            .getOrDefault(Sensitivity.NORMAL)

    fun setSensitivity(ctx: Context, s: Sensitivity) = sp(ctx).edit().putString("sensitivity", s.name).apply()

    fun vibrate(ctx: Context) = sp(ctx).getBoolean("vibrate", true)
    fun setVibrate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("vibrate", v).apply()

    fun debug(ctx: Context) = sp(ctx).getBoolean("debug", false)
    fun setDebug(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("debug", v).apply()

    fun autoCapture(ctx: Context) = sp(ctx).getBoolean("auto_capture", true)
    fun setAutoCapture(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("auto_capture", v).apply()

    /** Чи вже показували підказку про навчання фону. */
    fun bgHintShown(ctx: Context) = sp(ctx).getBoolean("bg_hint", false)
    fun setBgHintShown(ctx: Context) = sp(ctx).edit().putBoolean("bg_hint", true).apply()
}
