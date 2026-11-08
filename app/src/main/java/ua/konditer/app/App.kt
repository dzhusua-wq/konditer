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
        opencvReady = try {
            org.opencv.android.OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            logError(this, "OpenCV не завантажився", e); false
        }
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
        @Volatile
        var opencvReady = false
            private set

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

    fun model(ctx: Context): ModelChoice =
        runCatching { ModelChoice.valueOf(sp(ctx).getString("model", null) ?: "") }.getOrDefault(ModelChoice.ACCURATE)

    fun setModel(ctx: Context, m: ModelChoice) = sp(ctx).edit().putString("model", m.name).apply()

    /** Автоматично відокремлювати виріб від фону. */
    fun isolate(ctx: Context) = sp(ctx).getBoolean("isolate", true)
    fun setIsolate(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("isolate", v).apply()

    /** Перевірка дрібних деталей для плоских виробів. */
    fun orb(ctx: Context) = sp(ctx).getBoolean("orb", true)
    fun setOrb(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("orb", v).apply()

    /** Ключ векторів для поточних налаштувань (має збігатися з Embedder.key). */
    fun vectorKey(ctx: Context) = model(ctx).id + if (isolate(ctx)) "+iso" else ""

    /** Чи вже показували підказку про навчання фону. */
    fun bgHintShown(ctx: Context) = sp(ctx).getBoolean("bg_hint", false)
    fun setBgHintShown(ctx: Context) = sp(ctx).edit().putBoolean("bg_hint", true).apply()
}
