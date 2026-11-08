package ua.konditer.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
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
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ua.konditer.app.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Konditer"
        private const val FRAME_INTERVAL_MS = 140L
        /** Нижче — кадр явно розмитий (дисперсія лапласіана). */
        private const val SHARP_MIN = 10f
        private const val DARK_MAX = 45f
        private const val BG_TARGET = 5
        private val GREEN = Color.rgb(102, 220, 140)
        private val AMBER = Color.rgb(255, 200, 80)
    }

    /** Результат обробки одного кадру (створюється у фоновому потоці). */
    private class FrameResult(
        val crop: Bitmap,
        val hi: Bitmap,
        val shot: Shot,
        val sharp: Float,
        val bright: Float,
        val decision: MatchIndex.Decision?,
        val ranked: List<MatchIndex.Ranked>,
        val time: Long
    )

    private enum class CardState { LOADING, EMPTY_CATALOG, EMPTY_FRAME, SEARCHING, UNKNOWN, ITEM }

    private lateinit var b: ActivityMainBinding
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // --- стан фонового потоку ---
    @Volatile private var embedder: Embedder? = null
    @Volatile private var busy = false
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0
    @Volatile private var frameCenterY = 0f
    @Volatile private var sensitivity = Sensitivity.NORMAL
    @Volatile private var enrollTarget: String? = null
    @Volatile private var useOrb = true
    private var orb: OrbVerifier? = null
    private var lastRun = 0L
    private var errorLogged = false

    // --- стан інтерфейсу ---
    private val stabilizer = Stabilizer()
    private var lastFrame: FrameResult? = null
    private var cardState = CardState.LOADING
    private var shownItemId: String? = null
    private var camera: Camera? = null
    private var torchOn = false
    private var pending = 0
    private var lastCaptureAt = 0L
    private var lastCapturedEmb: FloatArray? = null
    private var debug = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showCard(
                CardState.LOADING, "Немає доступу до камери",
                "Дозвольте камеру: Налаштування → Додатки → Кондитер → Дозволи."
            )
        }

    private val catalogLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            res.data?.getStringExtra(CatalogActivity.EXTRA_ENROLL_ID)?.let { startEnroll(it) }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            when (res.data?.getStringExtra(SettingsActivity.EXTRA_ACTION)) {
                SettingsActivity.ACTION_TRAIN_BG -> startEnroll(Catalog.BACKGROUND)
                SettingsActivity.ACTION_REINDEX -> reindex(force = true)
            }
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
        b.overlay.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            viewW = v.width; viewH = v.height; frameCenterY = b.overlay.frameCenterY()
        }
        onBackPressedDispatcher.addCallback(this, backDuringEnroll)

        Catalog.load(this)
        setupButtons()

        loadEmbedder()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        sensitivity = Prefs.sensitivity(this)
        debug = Prefs.debug(this)
        b.txtDebug.isVisible = debug
        stabilizer.reset()
        shownItemId = null
        useOrb = Prefs.orb(this) && App.opencvReady
        val emb = embedder
        if (emb != null && !busy && enrollTarget == null) {
            if (emb.key != Prefs.vectorKey(this)) loadEmbedder()          // змінили модель у налаштуваннях
            else if (Catalog.needsReindex()) reindex(force = false)      // після імпорту копії
        }
    }

    /** Завантажує (або замінює) нейромережу згідно з налаштуваннями. */
    private fun loadEmbedder() {
        val model = Prefs.model(this)
        val isolate = Prefs.isolate(this)
        busy = true
        showCard(CardState.LOADING, "Завантаження нейромережі…", model.label)
        executor.execute {
            try {
                embedder?.close()
                embedder = null
                val e = Embedder.create(applicationContext, model, isolate)
                Catalog.switchKey(e.key)
                embedder = e
                runOnUiThread { busy = false; reindex(force = false) }
            } catch (e: Throwable) {
                App.logError(this, "Завантаження моделі ${model.id}", e)
                runOnUiThread {
                    busy = false
                    showCard(CardState.LOADING, "Помилка завантаження нейромережі", e.message ?: "")
                    if (model != ModelChoice.FAST) {
                        toast("Перемикаю на швидку модель")
                        Prefs.setModel(this, ModelChoice.FAST)
                        loadEmbedder()
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        b.btnCatalog.setOnClickListener { catalogLauncher.launch(Intent(this, CatalogActivity::class.java)) }
        b.btnSettings.setOnClickListener { settingsLauncher.launch(Intent(this, SettingsActivity::class.java)) }
        b.btnAdd.setOnClickListener {
            ItemDialog.edit(this, null) { item -> startEnroll(item.id) }
        }
        b.btnTorch.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (!cam.cameraInfo.hasFlashUnit()) { toast("Спалаху немає"); return@setOnClickListener }
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
            b.btnTorch.text = if (torchOn) "Світло ✓" else "Світло"
        }
        b.zoomGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val want = when (id) { b.zoom2.id -> 2f; b.zoom3.id -> 3f; else -> 1f }
            val cam = camera ?: return@addOnButtonCheckedListener
            val maxZ = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            cam.cameraControl.setZoomRatio(min(want, maxZ))
            stabilizer.reset()
        }
        // Дотик — фокус у цій точці
        b.preview.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val point = b.preview.meteringPointFactory.createPoint(ev.x, ev.y)
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(point).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
                )
                v.performClick()
            }
            true
        }
        b.btnRight.setOnClickListener {
            when (cardState) {
                CardState.ITEM -> shownItemId?.let { confirmItem(it) }
                CardState.UNKNOWN -> lastFrame?.let { newItemFromFrame(it) }
                else -> {}
            }
        }
        b.btnWrong.setOnClickListener { lastFrame?.let { chooseCorrect(it) } }
        b.btnShot.setOnClickListener { manualCapture() }
        b.btnDone.setOnClickListener { finishEnroll() }
        b.switchAuto.setOnCheckedChangeListener { _, on -> Prefs.setAutoCapture(this, on) }
    }

    // =====================================================================
    // Камера і обробка кадрів
    // =====================================================================

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
            preview.setSurfaceProvider(b.preview.surfaceProvider)

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { analyze(it) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                torchOn = false
                b.btnTorch.text = "Світло"
                b.zoomGroup.check(b.zoom1.id)
            } catch (e: Exception) {
                App.logError(this, "Запуск камери", e)
                showCard(CardState.LOADING, "Не вдалося запустити камеру", e.message ?: "")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Фоновий потік: кадр → кроп рамки → вектор → пошук у каталозі. */
    private fun analyze(proxy: ImageProxy) {
        try {
            val emb = embedder ?: return
            if (busy) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < FRAME_INTERVAL_MS) return
            lastRun = now

            val frame = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val upright = if (rot != 0) {
                Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            } else frame
            val native = cropToFrame(upright)
            val crop = ImageOps.scaleTo(native, ImageOps.CROP)
            val hi = if (native.width > ImageOps.HI) ImageOps.scaleTo(native, ImageOps.HI) else native
            val shot = emb.describe(crop)
            val sharp = ImageOps.sharpness(crop)
            val bright = ImageOps.brightness(crop)

            var decision: MatchIndex.Decision? = null
            var ranked: List<MatchIndex.Ranked> = emptyList()
            if (enrollTarget == null) {
                val idx = Catalog.index
                val r = idx.query(shot)
                ranked = r.ranked.take(8)
                var d = idx.decide(r, sensitivity)
                if (useOrb) {
                    val v = orb ?: OrbVerifier().also { orb = it }
                    d = v.refine(d, r.ranked, hi)
                }
                decision = d
            }
            val fr = FrameResult(crop, hi, shot, sharp, bright, decision, ranked, SystemClock.elapsedRealtime())
            runOnUiThread { onFrame(fr) }
        } catch (e: Throwable) {
            Log.e(TAG, "analyze", e)
            if (!errorLogged) { errorLogged = true; App.logError(this, "Обробка кадру", e) }
        } finally {
            proxy.close()
        }
    }

    /** Вирізає з кадру рівно ту область, яку видно в рамці (у рідній роздільності). */
    private fun cropToFrame(img: Bitmap): Bitmap {
        val iw = img.width
        val ih = img.height
        val vw = viewW
        val vh = viewH
        var side: Int
        var cy: Int
        if (vw > 0 && vh > 0) {
            // PreviewView (fillCenter) масштабує кадр так, щоб заповнити екран; центри збігаються
            val scale = max(vw.toFloat() / iw, vh.toFloat() / ih)
            side = (vw * FrameOverlayView.FRACTION / scale).roundToInt()
            cy = (ih / 2f + (frameCenterY - vh / 2f) / scale).roundToInt()
        } else {
            side = (min(iw, ih) * 0.6f).roundToInt()
            cy = ih / 2
        }
        side = side.coerceIn(32, min(iw, ih))
        val left = ((iw - side) / 2).coerceIn(0, iw - side)
        val top = (cy - side / 2).coerceIn(0, ih - side)
        return Bitmap.createBitmap(img, left, top, side, side)
    }

    // =====================================================================
    // Відображення результату
    // =====================================================================

    private fun onFrame(fr: FrameResult) {
        if (busy) return
        lastFrame = fr
        b.overlay.warning = when {
            fr.bright < DARK_MAX -> "Темно — увімкніть «Світло»"
            fr.sharp < SHARP_MIN -> "Розмито — тримайте телефон нерухомо"
            else -> ""
        }

        if (enrollTarget != null) {
            if (debug) b.txtDebug.text = "різкість=${fr.sharp.roundToInt()} яскравість=${fr.bright.roundToInt()}"
            autoCapture(fr)
            return
        }
        val d = fr.decision ?: return
        if (debug) {
            b.txtDebug.text = "${d.state}\n${d.debug}\nрізкість=${fr.sharp.roundToInt()} яскравість=${fr.bright.roundToInt()}" +
                "\nвекторів=${Catalog.index.vectorCount} фон=${Catalog.backgroundCount()}"
        }
        if (d.state == MatchIndex.State.NO_CATALOG) {
            stabilizer.reset()
            showCard(
                CardState.EMPTY_CATALOG, "Каталог порожній",
                "Натисніть «+ Додати»: введіть назву й вагу, потім зніміть виріб з різних боків."
            )
            return
        }
        if (stabilizer.push(d.itemId, d.confidence)) haptic()

        val lockedId = stabilizer.lockedId
        val locked = lockedId?.let { Catalog.get(it) }
        when {
            locked != null -> showItem(locked, stabilizer.confidence, d)
            d.state == MatchIndex.State.EMPTY -> showCard(CardState.EMPTY_FRAME, "Покладіть виріб у рамку", "")
            stabilizer.unknownStreak >= 5 -> {
                showCard(
                    CardState.UNKNOWN, "Не впізнано",
                    d.best?.let { "Найближче: ${it.item.name}" } ?: ""
                )
            }
            else -> showCard(CardState.SEARCHING, "Шукаю…", "Тримайте виріб у рамці")
        }
    }

    private fun showCard(state: CardState, title: String, details: String) {
        cardState = state
        shownItemId = null
        b.txtName.text = title
        b.txtDetails.text = details
        b.txtDetails.isVisible = details.isNotEmpty()
        b.imgThumb.isVisible = false
        b.progConf.isVisible = false
        b.txtConf.isVisible = false
        b.chipsAlt.isVisible = false
        b.chipsAlt.tag = null
        b.rowActions.isVisible = state == CardState.UNKNOWN
        if (state == CardState.UNKNOWN) {
            b.btnWrong.text = "Що це?"
            b.btnRight.text = "+ Новий виріб"
        }
        b.overlay.accent = Color.WHITE
    }

    private fun showItem(item: Item, conf: Float, d: MatchIndex.Decision) {
        cardState = CardState.ITEM
        if (shownItemId != item.id) {
            shownItemId = item.id
            b.txtName.text = item.name
            val details = buildString {
                if (item.weight.isNotBlank()) append("Вага: ").append(item.weight)
                if (item.article.isNotBlank()) { if (isNotEmpty()) append(" · "); append("арт. ").append(item.article) }
                if (item.price.isNotBlank()) { if (isNotEmpty()) append('\n'); append("Ціна: ").append(item.price).append(" грн") }
                if (item.description.isNotBlank()) { if (isNotEmpty()) append('\n'); append(item.description) }
                if (isEmpty() && item.category.isNotBlank()) append(item.category)
            }
            b.txtDetails.text = details
            b.txtDetails.isVisible = details.isNotEmpty()
            val thumb = Catalog.thumbFile(item)?.let { BitmapFactory.decodeFile(it.path) }
            b.imgThumb.setImageBitmap(thumb)
            b.imgThumb.isVisible = thumb != null
            b.btnWrong.text = "Не те"
            b.btnRight.text = "Вірно ✓"
            b.rowActions.isVisible = true
            b.progConf.isVisible = true
            b.txtConf.isVisible = true
        }
        val pct = (conf * 100).roundToInt().coerceIn(0, 100)
        b.progConf.setProgressCompat(pct, true)
        b.txtConf.text = "Впевненість $pct%"
        b.overlay.accent = if (conf >= 0.65f) GREEN else AMBER

        // Альтернативи: торкнутись = «насправді це він»
        val alts = (listOfNotNull(d.best) + d.alternatives).filter { it.item.id != item.id }.take(2)
        val key = alts.joinToString { it.item.id }
        if (b.chipsAlt.tag != key) {
            b.chipsAlt.tag = key
            b.chipsAlt.removeAllViews()
            for (a in alts) {
                b.chipsAlt.addView(Chip(this).apply {
                    text = "Чи це: ${a.item.name}?"
                    setOnClickListener { lastFrame?.let { fr -> teach(a.item.id, fr, "Додано до «${a.item.name}»") } }
                })
            }
        }
        b.chipsAlt.isVisible = alts.isNotEmpty()
    }

    // =====================================================================
    // Навчання на ходу: «Вірно» / «Не те»
    // =====================================================================

    private fun freshFrame(): FrameResult? {
        val fr = lastFrame
        if (fr == null || SystemClock.elapsedRealtime() - fr.time > 2000) {
            toast("Камера ще не готова, спробуйте ще раз"); return null
        }
        return fr
    }

    private fun confirmItem(itemId: String) {
        val fr = freshFrame() ?: return
        val item = Catalog.get(itemId) ?: return
        if (item.samples.size >= Catalog.MAX_SAMPLES) { toast("Еталонів уже достатньо"); return }
        teach(itemId, fr, "Запам'ятав цей ракурс «${item.name}»")
    }

    /** Додає поточний кадр як еталон виробу у фоні. */
    private fun teach(target: String, fr: FrameResult, message: String, source: String = Sample.SRC_FEEDBACK, then: (() -> Unit)? = null) {
        addSampleAsync(target, fr, source) { ok ->
            if (ok) { toast(message); haptic() } else toast("Досягнуто ліміту еталонів (${Catalog.MAX_SAMPLES})")
            stabilizer.reset()
            shownItemId = null
            then?.invoke()
        }
    }

    private fun addSampleAsync(target: String, fr: FrameResult, source: String, done: (Boolean) -> Unit) {
        val emb = embedder ?: return
        pending++
        executor.execute {
            val ok = try {
                Catalog.addSample(target, fr.crop, fr.hi, emb.describeAugmented(fr.crop), source) != null
            } catch (e: Throwable) {
                App.logError(this, "Збереження еталона", e); false
            }
            runOnUiThread { pending--; done(ok) }
        }
    }

    private fun chooseCorrect(fr0: FrameResult) {
        val fr = freshFrame() ?: fr0
        val candidates = fr.ranked.map { it.item }.filter { it.id != shownItemId }.take(6)
        val labels = candidates.map { it.name } + listOf("Інший виріб з каталогу…", "+ Новий виріб…")
        MaterialAlertDialogBuilder(this)
            .setTitle("Що насправді в рамці?")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < candidates.size -> teach(candidates[which].id, fr, "Додано до «${candidates[which].name}»")
                    which == candidates.size -> ItemPicker.show(this, "Виберіть виріб") { item ->
                        teach(item.id, fr, "Додано до «${item.name}»")
                    }
                    else -> newItemFromFrame(fr)
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun newItemFromFrame(fr: FrameResult) {
        ItemDialog.edit(this, null) { item ->
            teach(item.id, fr, "Створено «${item.name}». Зніміть ще кілька ракурсів", Sample.SRC_CAMERA) {
                startEnroll(item.id)
            }
        }
    }

    // =====================================================================
    // Навчання виробу / фону
    // =====================================================================

    private fun enrollTargetCount(target: String): Int =
        if (target == Catalog.BACKGROUND) BG_TARGET else Catalog.get(target)?.targetSamples ?: 8

    private fun enrollCount(target: String): Int =
        (if (target == Catalog.BACKGROUND) Catalog.backgroundCount() else Catalog.get(target)?.samples?.size ?: 0)

    private fun startEnroll(target: String) {
        val title: String
        val hint: String
        if (target == Catalog.BACKGROUND) {
            title = "Навчання фону"
            hint = "Приберіть вироби з рамки й знімайте порожню поверхню, на яку зазвичай кладете вироби: " +
                "різні місця, при різному світлі. Так додаток розумітиме, коли в рамці нічого немає."
        } else {
            val item = Catalog.get(target) ?: return
            title = item.name
            hint = if (item.kind == Kind.FLAT)
                "Плоский виріб: знімайте під невеликими нахилами, з різної відстані, уникайте відблисків."
            else
                "Об'ємний виріб: обійдіть його з різних боків і зверху, покрутіть, змініть відстань."
        }
        enrollTarget = target
        stabilizer.reset()
        lastCaptureAt = 0L
        lastCapturedEmb = null
        backDuringEnroll.isEnabled = true
        b.resultCard.isVisible = false
        b.enrollCard.isVisible = true
        b.btnAdd.isEnabled = false
        b.btnCatalog.isEnabled = false
        b.btnSettings.isEnabled = false
        b.overlay.accent = AMBER
        b.overlay.hint = "Виріб у рамці"
        b.txtEnrollTitle.text = title
        b.txtEnrollHint.text = hint
        b.switchAuto.isChecked = Prefs.autoCapture(this)
        updateEnrollUi()
    }

    private fun updateEnrollUi() {
        val t = enrollTarget ?: return
        val n = enrollCount(t) + pending
        val target = enrollTargetCount(t)
        b.progEnroll.max = target
        b.progEnroll.setProgressCompat(min(n, target), true)
        b.txtEnrollCount.text = if (n >= target) "Знято $n — достатньо, натисніть «Готово» (можна й більше)"
        else "Знято $n з $target"
    }

    private fun manualCapture() {
        val t = enrollTarget ?: return
        val fr = freshFrame() ?: return
        capture(t, fr)
    }

    private fun autoCapture(fr: FrameResult) {
        val t = enrollTarget ?: return
        if (!b.switchAuto.isChecked || pending > 0) return
        if (fr.sharp < SHARP_MIN * 1.5f || fr.bright < DARK_MAX) return
        if (SystemClock.elapsedRealtime() - lastCaptureAt < 700) return
        val prev = lastCapturedEmb
        if (prev != null && MatchIndex.cosine(prev, fr.shot.emb) > 0.92f) return // ракурс майже не змінився
        val count = enrollCount(t)
        if (count >= enrollTargetCount(t)) {
            b.switchAuto.isChecked = false
            toast("Знято достатньо. Натисніть «Готово» або знімайте вручну")
            return
        }
        capture(t, fr)
    }

    private fun capture(target: String, fr: FrameResult) {
        lastCaptureAt = SystemClock.elapsedRealtime()
        lastCapturedEmb = fr.shot.emb
        b.overlay.animate().alpha(0.3f).setDuration(70).withEndAction {
            b.overlay.animate().alpha(1f).setDuration(110).start()
        }.start()
        haptic()
        addSampleAsync(target, fr, Sample.SRC_CAMERA) { ok ->
            if (!ok) toast("Досягнуто ліміту еталонів (${Catalog.MAX_SAMPLES})")
            updateEnrollUi()
        }
        updateEnrollUi()
    }

    private fun finishEnroll() {
        val t = enrollTarget ?: return
        if (pending > 0) { b.root.postDelayed({ finishEnroll() }, 150); return }
        enrollTarget = null
        backDuringEnroll.isEnabled = false
        b.enrollCard.isVisible = false
        b.resultCard.isVisible = true
        b.btnAdd.isEnabled = true
        b.btnCatalog.isEnabled = true
        b.btnSettings.isEnabled = true
        b.overlay.hint = "Розмістіть виріб у рамці"
        b.overlay.accent = Color.WHITE
        stabilizer.reset()
        shownItemId = null

        if (t == Catalog.BACKGROUND) {
            toast("Фон: ${Catalog.backgroundCount()} знімків")
            return
        }
        val item = Catalog.get(t) ?: return
        if (item.samples.isEmpty()) {
            Catalog.removeItem(t)
            toast("Виріб не збережено: немає жодного знімка")
            return
        }
        toast("Збережено: ${item.name} (${item.samples.size} еталонів)")
        // Перевірка: чи не схожий новий виріб на вже наявні
        executor.execute {
            val conflicts = Catalog.index.conflictsFor(t)
            if (conflicts.isNotEmpty()) runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Схожі вироби")
                    .setMessage(
                        "«${item.name}» дуже схожий на: ${conflicts.joinToString { "«${it.name}»" }}.\n\n" +
                            "Щоб їх не плутати, дозніміть обидва з різних боків і при тому самому світлі, " +
                            "де будете розпізнавати. Можна й просто вибирати правильний варіант кнопкою «Не те»."
                    )
                    .setPositiveButton("Зрозуміло", null)
                    .show()
            }
        }
        maybeSuggestBackground()
    }

    private fun maybeSuggestBackground() {
        if (Catalog.backgroundCount() > 0 || Prefs.bgHintShown(this) || Catalog.all().size < 1) return
        Prefs.setBgHintShown(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Порада: навчіть фон")
            .setMessage(
                "Зніміть 5 разів порожній стіл чи підкладку, на яку кладете вироби. " +
                    "Тоді додаток не «впізнаватиме» виріб, коли рамка порожня, і точніше рахуватиме впевненість."
            )
            .setPositiveButton("Навчити зараз") { _, _ -> startEnroll(Catalog.BACKGROUND) }
            .setNegativeButton("Пізніше", null)
            .show()
    }

    // =====================================================================
    // Перерахунок еталонів (після зміни моделі)
    // =====================================================================

    private fun reindex(force: Boolean) {
        val emb = embedder ?: return
        if (!force && !Catalog.needsReindex()) {
            showCard(CardState.SEARCHING, "Наведіть камеру на виріб", "")
            return
        }
        busy = true
        showCard(CardState.LOADING, "Оновлення еталонів…", "Нова версія розпізнавання. Це разова дія.")
        executor.execute {
            try {
                Catalog.reindex(emb, force) { i, n ->
                    runOnUiThread { b.txtDetails.text = "Оброблено $i з $n" }
                }
            } catch (e: Throwable) {
                App.logError(this, "Перерахунок еталонів", e)
            }
            runOnUiThread {
                busy = false
                stabilizer.reset()
                showCard(CardState.SEARCHING, "Готово", "Наведіть камеру на виріб")
            }
        }
    }

    // =====================================================================

    private fun haptic() {
        if (!Prefs.vibrate(this)) return
        val c = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        b.root.performHapticFeedback(c)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        executor.execute { embedder?.close(); embedder = null; orb?.clear() }
        executor.shutdown()
    }
}
