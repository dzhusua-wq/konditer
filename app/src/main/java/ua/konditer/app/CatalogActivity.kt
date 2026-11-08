package ua.konditer.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ua.konditer.app.databinding.ActivityCatalogBinding
import ua.konditer.app.databinding.ItemCatalogBinding
import java.util.concurrent.Executors

class CatalogActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ENROLL_ID = "enroll_id"
    }

    private lateinit var b: ActivityCatalogBinding
    private var items: List<Item> = emptyList()

    private val worker = Executors.newSingleThreadExecutor()
    private var recognizer: Recognizer? = null
    private var pendingItemId: String? = null

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
            val id = pendingItemId ?: return@registerForActivityResult
            pendingItemId = null
            if (uris.isEmpty()) {
                dropIfEmpty(id); reload()
            } else importPhotos(id, uris)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(Catalog.toJson().toByteArray()) }
                toast("Каталог збережено у файл")
            } catch (e: Exception) {
                toast("Помилка збереження: ${e.message}")
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val json = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: throw IllegalStateException("порожній файл")
                val n = Catalog.importJson(json)
                Catalog.save(this)
                reload()
                toast("Імпортовано виробів: $n")
            } catch (e: Exception) {
                toast("Це не файл каталогу: ${e.message}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityCatalogBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }

        Catalog.load(this)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        b.list.adapter = adapter

        b.btnBack.setOnClickListener { finish() }
        b.btnAddPhoto.setOnClickListener {
            ItemDialog.show(this, "Новий виріб з фото", null, "Далі: вибрати фото") { name, weight, note ->
                val item = Catalog.create(name, weight, note)
                Catalog.save(this)
                pickPhotos(item.id)
            }
        }
        b.btnMore.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Файл каталогу")
                .setItems(
                    arrayOf(
                        "Зберегти каталог у файл (резервна копія / на інший телефон)",
                        "Завантажити каталог з файлу"
                    )
                ) { _, which ->
                    if (which == 0) exportLauncher.launch("konditer-catalog.json")
                    else importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                }
                .show()
        }
        reload()
    }

    private fun reload() {
        items = Catalog.all()
        val shots = items.sumOf { it.shots.size }
        b.txtTitle.text = "Каталог (${items.size})"
        b.txtEmpty.isVisible = items.isEmpty()
        adapter.notifyDataSetChanged()
        if (items.isNotEmpty()) b.txtTitle.contentDescription = "Виробів ${items.size}, знімків $shots"
    }

    private fun openItem(item: Item) {
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setItems(
                arrayOf("Дозняти камерою", "Додати фото з галереї", "Редагувати дані", "Видалити")
            ) { _, which ->
                when (which) {
                    0 -> {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_ENROLL_ID, item.id))
                        finish()
                    }
                    1 -> pickPhotos(item.id)
                    2 -> ItemDialog.show(this, "Редагування", item, "Зберегти") { name, weight, note ->
                        Catalog.get(item.id)?.apply {
                            this.name = name; this.weight = weight; this.note = note
                        }
                        Catalog.save(this)
                        reload()
                    }
                    3 -> MaterialAlertDialogBuilder(this)
                        .setTitle("Видалити «${item.name}»?")
                        .setMessage("Усі знімки цього виробу буде видалено.")
                        .setPositiveButton("Видалити") { _, _ ->
                            Catalog.remove(item.id)
                            Catalog.save(this)
                            reload()
                        }
                        .setNegativeButton("Скасувати", null)
                        .show()
                }
            }
            .show()
    }

    // ---------------- Еталони з галереї / файлів друку ----------------

    private fun pickPhotos(itemId: String) {
        pendingItemId = itemId
        pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun dropIfEmpty(id: String) {
        val item = Catalog.get(id) ?: return
        if (item.shots.isEmpty()) {
            Catalog.remove(id)
            Catalog.save(this)
        }
    }

    private fun importPhotos(id: String, uris: List<Uri>) {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle("Обробка фото…")
            .setMessage("0 з ${uris.size}")
            .setCancelable(false)
            .show()
        worker.execute {
            var added = 0
            var error: String? = null
            try {
                val rec = recognizer ?: Recognizer(applicationContext).also { recognizer = it }
                for ((i, uri) in uris.withIndex()) {
                    val bmp = loadBitmap(uri)
                    if (bmp != null) {
                        for (v in variants(bmp)) Catalog.addShot(id, rec.describe(v))
                        added++
                    }
                    runOnUiThread { progress.setMessage("${i + 1} з ${uris.size}") }
                }
                Catalog.save(applicationContext)
            } catch (e: Throwable) {
                error = e.message
            }
            runOnUiThread {
                progress.dismiss()
                dropIfEmpty(id)
                reload()
                toast(if (error != null) "Помилка: $error" else "Додано фото: $added")
            }
        }
    }

    /** Завантажує картинку зменшеною (до ~1024 px) з урахуванням повороту. */
    private fun loadBitmap(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val k = 1024f / maxOf(w, h)
                if (k < 1f) decoder.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Камера бачить виріб у квадратній рамці на світлому фоні, тому картинку кладемо
     * на білий квадрат: один варіант впритул, другий — з полями (виріб трохи менший у рамці).
     */
    private fun variants(src: Bitmap): List<Bitmap> {
        fun onWhiteSquare(marginFraction: Float): Bitmap {
            val side = (maxOf(src.width, src.height) * (1f + 2 * marginFraction)).toInt()
            val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.WHITE)
            c.drawBitmap(
                src,
                ((side - src.width) / 2f),
                ((side - src.height) / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            return out
        }
        return listOf(onWhiteSquare(0.02f), onWhiteSquare(0.15f))
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.execute { recognizer?.close(); recognizer = null }
        worker.shutdown()
    }

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCatalogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = items[position]
            h.v.txtName.text = item.name
            h.v.txtSub.text = buildString {
                if (item.weight.isNotBlank()) append(item.weight).append(" · ")
                append("знімків: ").append(item.shots.size)
                if (item.shots.size < 8) append(" — варто дозняти")
            }
            h.v.root.setOnClickListener { openItem(item) }
        }
    }

    class Holder(val v: ItemCatalogBinding) : RecyclerView.ViewHolder(v.root)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
