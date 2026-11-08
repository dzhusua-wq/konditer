package ua.konditer.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ua.konditer.app.databinding.ActivitySettingsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_TRAIN_BG = "train_bg"
        const val ACTION_REINDEX = "reindex"
    }

    private lateinit var b: ActivitySettingsBinding
    private val worker = Executors.newSingleThreadExecutor()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) return@registerForActivityResult
            worker.execute {
                val msg = try {
                    contentResolver.openOutputStream(uri)?.use { Catalog.exportZip(it) }
                    "Копію збережено"
                } catch (e: Exception) {
                    App.logError(this, "Експорт", e); "Помилка: ${e.message}"
                }
                runOnUiThread { toast(msg) }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            worker.execute {
                val msg = try {
                    val n = contentResolver.openInputStream(uri)?.use { Catalog.importZip(it) } ?: 0
                    "Завантажено виробів: $n"
                } catch (e: Exception) {
                    App.logError(this, "Імпорт", e); "Це не копія каталогу: ${e.message}"
                }
                runOnUiThread { toast(msg); refresh() }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        Catalog.load(this)

        b.btnBack.setOnClickListener { finish() }

        // Чутливість
        val radios = mapOf(
            Sensitivity.STRICT to b.sensStrict,
            Sensitivity.NORMAL to b.sensNormal,
            Sensitivity.SOFT to b.sensSoft
        )
        radios.forEach { (s, r) -> r.text = s.label }
        radios[Prefs.sensitivity(this)]?.isChecked = true
        b.groupSensitivity.setOnCheckedChangeListener { _, id ->
            radios.entries.find { it.value.id == id }?.let { Prefs.setSensitivity(this, it.key) }
        }

        // Нейромережа
        val models = mapOf(ModelChoice.ACCURATE to b.modelAccurate, ModelChoice.FAST to b.modelFast)
        models.forEach { (m, r) -> r.text = m.label }
        models[Prefs.model(this)]?.isChecked = true
        b.groupModel.setOnCheckedChangeListener { _, id ->
            models.entries.find { it.value.id == id }?.let { Prefs.setModel(this, it.key); keyChanged() }
        }
        b.switchIsolate.isChecked = Prefs.isolate(this)
        b.switchIsolate.setOnCheckedChangeListener { _, v -> Prefs.setIsolate(this, v); keyChanged() }
        b.switchOrb.isChecked = Prefs.orb(this)
        b.switchOrb.isEnabled = App.opencvReady
        b.switchOrb.setOnCheckedChangeListener { _, v -> Prefs.setOrb(this, v) }

        b.switchVibrate.isChecked = Prefs.vibrate(this)
        b.switchVibrate.setOnCheckedChangeListener { _, v -> Prefs.setVibrate(this, v) }
        b.switchDebug.isChecked = Prefs.debug(this)
        b.switchDebug.setOnCheckedChangeListener { _, v -> Prefs.setDebug(this, v) }

        // Фон
        b.btnTrainBg.setOnClickListener { finishWith(ACTION_TRAIN_BG) }
        b.btnClearBg.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Скинути фон?")
                .setMessage("Знімки порожньої поверхні буде видалено.")
                .setPositiveButton("Скинути") { _, _ -> Catalog.clearBackground(); refresh() }
                .setNegativeButton("Скасувати", null)
                .show()
        }

        // Перевірка
        b.btnCheck.setOnClickListener { runSelfTest() }

        // Дані
        b.btnExport.setOnClickListener {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            exportLauncher.launch("konditer-$date.zip")
        }
        b.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        b.btnReindex.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Перерахувати еталони?")
                .setMessage("Вектори всіх еталонів буде заново пораховано з фото. Потрібно лише якщо щось працює дивно.")
                .setPositiveButton("Перерахувати") { _, _ -> finishWith(ACTION_REINDEX) }
                .setNegativeButton("Скасувати", null)
                .show()
        }

        // Журнал
        b.btnShareLog.setOnClickListener {
            val f = App.logFile(this)
            if (!f.exists() || f.length() == 0L) { toast("Помилок не було"); return@setOnClickListener }
            val text = f.readText().takeLast(60_000)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Кондитер — журнал помилок")
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Надіслати журнал"))
        }
        b.btnClearLog.setOnClickListener { App.logFile(this).delete(); refresh() }

        b.txtVersion.text = "Кондитер ${BuildConfig.VERSION_NAME}\nвектори: ${Catalog.activeKey} · OpenCV: ${if (App.opencvReady) "так" else "ні"}"
        refresh()
    }

    private fun refresh() {
        val bg = Catalog.backgroundCount()
        b.txtBg.text = if (bg == 0)
            "Фон не навчено. Зніміть 5 разів порожню поверхню — тоді додаток розумітиме, що в рамці нічого немає."
        else "Знімків фону: $bg"
        val items = Catalog.all()
        val weak = items.count { it.samples.size < it.targetSamples }
        b.txtStats.text = "Виробів: ${items.size} · еталонних фото: ${Catalog.sampleCount()}" +
            if (weak > 0) "\nМало фото у виробів: $weak" else ""
        val log = App.logFile(this)
        val errors = if (log.exists()) log.readLines().count { it.startsWith("===") } else 0
        b.txtLog.text = if (errors == 0) "Помилок не зафіксовано" else "Записів про помилки: $errors"
    }

    private fun runSelfTest() {
        val idx = Catalog.index
        b.btnCheck.isEnabled = false
        b.txtCheck.isVisible = true
        b.txtCheck.text = "Перевіряю…"
        worker.execute {
            val r = idx.selfTest(perItem = 3) { i, n ->
                if (i % 5 == 0 || i == n) runOnUiThread { b.txtCheck.text = "Перевіряю… $i з $n" }
            }
            val text = if (r.checked == 0) {
                "Нема що перевіряти: потрібно хоча б 2 вироби з 2+ фото."
            } else buildString {
                val pct = (100f * r.correct / r.checked).roundToInt()
                append("Точність: $pct% (${r.correct} з ${r.checked})")
                if (r.confusions.isEmpty()) append("\nПлутанини не виявлено ✓")
                else {
                    append("\n\nПлутаються:")
                    r.confusions.take(8).forEach { (a, c, n) -> append("\n• «${a.name}» → «${c.name}» ($n)") }
                    append("\n\nДозніміть ці вироби з різних боків або при іншому світлі.")
                }
            }
            runOnUiThread { b.txtCheck.text = text; b.btnCheck.isEnabled = true }
        }
    }

    private fun keyChanged() {
        toast("Зміниться після повернення на екран камери. Еталони перерахуються з фото автоматично.")
    }

    private fun finishWith(action: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action))
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}
