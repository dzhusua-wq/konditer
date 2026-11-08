package ua.konditer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import ua.konditer.app.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Konditer"
        /** Нижче цієї оцінки показуємо «Не впізнано». Можна підлаштувати після тестів. */
        private const val THRESHOLD = 0.75f
        /** Як часто аналізувати кадр, мс. */
        private const val FRAME_INTERVAL_MS = 150L
        private val GREEN = Color.rgb(102, 220, 140)
        private val AMBER = Color.rgb(255, 200, 80)
    }

    private lateinit var b: ActivityMainBinding
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0
    @Volatile private var latestShot: Shot? = null
    @Volatile private var latestShotTime = 0L
    @Volatile private var enrollId: String? = null

    private var lastRun = 0L
    private var lastAddedShot: Shot? = null
    private var camera: Camera? = null
    private var torchOn = false
    /** Згладжені оцінки, щоб результат не «стрибав» між кадрами. */
    private val smooth = HashMap<String, Float>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                b.txtName.text = "Немає доступу до камери"
                b.txtDetails.text = "Дозвольте камеру: Налаштування → Додатки → Кондитер → Дозволи."
            }
        }

    private val catalogLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            res.data?.getStringExtra(CatalogActivity.EXTRA_ENROLL_ID)?.let { startEnroll(it) }
        }

    private val backDuringEnroll = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = finishEnroll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        b.preview.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            viewW = v.width; viewH = v.height
        }
        onBackPressedDispatcher.addCallback(this, backDuringEnroll)

        Catalog.load(this)

        b.btnCatalog.setOnClickListener {
            catalogLauncher.launch(Intent(this, CatalogActivity::class.java))
        }
        b.btnAdd.setOnClickListener {
            ItemDialog.show(this, "Новий виріб", null, "Далі: знімки") { name, weight, note ->
                val item = Catalog.create(name, weight, note)
                Catalog.save(this)
                startEnroll(item.id)
            }
        }
        b.btnTorch.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (!cam.cameraInfo.hasFlashUnit()) {
                toast("Спалаху немає"); return@setOnClickListener
            }
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
            b.btnTorch.text = if (torchOn) "Світло ✓" else "Світло"
        }
        b.btnShot.setOnClickListener { takeShot() }
        b.btnDone.setOnClickListener { finishEnroll() }

        // Модель завантажується у фоні (1–2 с)
        analysisExecutor.execute {
            try {
                recognizer = Recognizer(applicationContext)
                runOnUiThread { showIdle() }
            } catch (e: Throwable) {
                Log.e(TAG, "model", e)
                runOnUiThread {
                    b.txtName.text = "Помилка завантаження моделі"
                    b.txtDetails.text = e.message
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------------- Камера ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 960),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .build()
            preview.setSurfaceProvider(b.preview.surfaceProvider)

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { analyze(it) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                torchOn = false
                b.btnTorch.text = "Світло"
            } catch (e: Exception) {
                Log.e(TAG, "camera", e)
                b.txtName.text = "Не вдалося запустити камеру"
                b.txtDetails.text = e.message
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Викликається у фоновому потоці для кожного кадру. */
    private fun analyze(proxy: ImageProxy) {
        try {
            val rec = recognizer ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < FRAME_INTERVAL_MS) return
            lastRun = now

            val frame = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            val upright = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
            } else frame

            val shot = rec.describe(cropToFrame(upright))
            latestShot = shot
            latestShotTime = SystemClock.elapsedRealtime()

            if (enrollId == null) {
                val ranked = Recognizer.rank(shot, Catalog.snapshot)
                runOnUiThread { showResults(ranked) }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "analyze", e)
        } finally {
            proxy.close()
        }
    }

    /** Вирізає з кадру саме ту область, яку користувач бачить у рамці. */
    private fun cropToFrame(img: Bitmap): Bitmap {
        val iw = img.width
        val ih = img.height
        val vw = viewW
        val vh = viewH
        val side = if (vw > 0 && vh > 0) {
            // PreviewView у режимі fillCenter масштабує кадр так, щоб заповнити екран
            val scale = maxOf(vw.toFloat() / iw, vh.toFloat() / ih)
            (vw * FrameOverlayView.FRACTION / scale).roundToInt()
        } else {
            (min(iw, ih) * 0.6f).roundToInt()
        }.coerceIn(32, min(iw, ih))
        val left = (iw - side) / 2
        val top = (ih - side) / 2
        return Bitmap.createBitmap(img, left, top, side, side)
    }

    // ---------------- Розпізнавання ----------------

    private fun showIdle() {
        if (enrollId != null) return
        if (Catalog.snapshot.none { it.shots.isNotEmpty() }) {
            b.txtName.text = "Каталог порожній"
            b.txtDetails.text = "Натисніть «+ Додати», введіть назву й вагу, " +
                "потім зробіть 10–20 знімків виробу з різних боків."
            b.txtAlt.text = ""
        } else {
            b.txtName.text = "Наведіть камеру на виріб"
            b.txtDetails.text = ""
            b.txtAlt.text = ""
        }
    }

    private fun showResults(ranked: List<Recognizer.Companion.Match>) {
        if (enrollId != null) return
        if (ranked.isEmpty()) {
            smooth.clear(); b.overlay.accent = Color.WHITE; showIdle(); return
        }

        val ids = ranked.map { it.item.id }.toHashSet()
        smooth.keys.retainAll(ids)
        for (m in ranked) {
            val prev = smooth[m.item.id]
            smooth[m.item.id] = if (prev == null) m.score else prev * 0.6f + m.score * 0.4f
        }
        val top = ranked
            .map { it.item to (smooth[it.item.id] ?: it.score) }
            .sortedByDescending { it.second }
            .take(3)
        val (best, bestScore) = top[0]

        if (bestScore < THRESHOLD) {
            b.overlay.accent = Color.WHITE
            b.txtName.text = "Не впізнано"
            b.txtDetails.text = "Найближче: ${best.name} — ${pct(bestScore)}"
            b.txtAlt.text = "Помістіть виріб у рамку або додайте його в каталог"
            return
        }

        b.txtName.text = best.name
        b.txtDetails.text = buildString {
            if (best.weight.isNotBlank()) append("Вага: ").append(best.weight)
            if (best.note.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(best.note)
            }
        }
        val others = top.drop(1).filter { it.second >= THRESHOLD - 0.1f }
        val close = others.isNotEmpty() && bestScore - others[0].second < 0.02f
        b.overlay.accent = if (close) AMBER else GREEN
        b.txtAlt.text = buildString {
            append("Збіг ").append(pct(bestScore))
            if (others.isNotEmpty()) {
                append(" · Також схоже: ")
                append(others.joinToString(", ") { "${it.first.name} ${pct(it.second)}" })
            }
            if (close) append("\nВироби дуже схожі — змініть ракурс або світло")
        }
    }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

    // ---------------- Навчання виробу ----------------

    private fun startEnroll(id: String) {
        val item = Catalog.get(id) ?: return
        enrollId = id
        lastAddedShot = null
        backDuringEnroll.isEnabled = true
        b.resultCard.isVisible = false
        b.enrollCard.isVisible = true
        b.btnAdd.isEnabled = false
        b.btnCatalog.isEnabled = false
        b.overlay.accent = AMBER
        b.overlay.hint = "Виріб у рамці → «Знімок»"
        b.txtEnrollTitle.text = item.name
        updateEnrollCount(item)
    }

    private fun updateEnrollCount(item: Item) {
        val n = item.shots.size
        b.txtEnrollHint.text = "Знято: $n. Плоскі (топери, печиво з малюнком) — 5–8 знімків, " +
            "об'ємні — 10–20 з різних боків. Міняйте нахил і відстань, уникайте відблисків."
    }

    private fun takeShot() {
        val id = enrollId ?: return
        val shot = latestShot
        val fresh = SystemClock.elapsedRealtime() - latestShotTime < 1500
        if (shot == null || !fresh || recognizer == null) {
            toast("Камера ще не готова, зачекайте секунду"); return
        }
        if (shot === lastAddedShot) {
            toast("Трохи зачекайте між знімками"); return
        }
        lastAddedShot = shot
        Catalog.addShot(id, shot)
        Catalog.save(this)
        b.btnShot.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        b.overlay.animate().alpha(0.3f).setDuration(80).withEndAction {
            b.overlay.animate().alpha(1f).setDuration(120).start()
        }.start()
        Catalog.get(id)?.let { updateEnrollCount(it) }
    }

    private fun finishEnroll() {
        val id = enrollId ?: return
        val item = Catalog.get(id)
        if (item != null && item.shots.isEmpty()) {
            Catalog.remove(id)
            toast("Виріб не збережено: немає жодного знімка")
        } else if (item != null) {
            toast("Збережено: ${item.name} (${item.shots.size} фото)")
        }
        Catalog.save(this)
        enrollId = null
        backDuringEnroll.isEnabled = false
        b.enrollCard.isVisible = false
        b.resultCard.isVisible = true
        b.btnAdd.isEnabled = true
        b.btnCatalog.isEnabled = true
        b.overlay.accent = Color.WHITE
        b.overlay.hint = "Розмістіть виріб у рамці"
        smooth.clear()
        showIdle()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.execute {
            recognizer?.close()
            recognizer = null
        }
        analysisExecutor.shutdown()
    }
}
