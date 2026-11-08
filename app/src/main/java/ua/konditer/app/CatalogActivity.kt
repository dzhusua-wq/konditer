package ua.konditer.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ua.konditer.app.databinding.ActivityCatalogBinding
import ua.konditer.app.databinding.ItemCatalogBinding
import java.util.concurrent.Executors

class CatalogActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ENROLL_ID = "enroll_id"

        private val thumbs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        /** Мініатюра еталона (зменшена вдвічі), з кешем. */
        fun thumb(sampleId: String): Bitmap? {
            thumbs.get(sampleId)?.let { return it }
            val f = Catalog.imageFile(sampleId)
            if (!f.exists()) return null
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return null
            thumbs.put(sampleId, bmp)
            return bmp
        }
    }

    private sealed class Row {
        class Header(val title: String) : Row()
        class Entry(val item: Item) : Row()
    }

    private lateinit var b: ActivityCatalogBinding
    private var rows: List<Row> = emptyList()
    private var query = ""

    private val worker = Executors.newSingleThreadExecutor()
    private var embedder: Embedder? = null
    private var pendingItemId: String? = null

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
            val id = pendingItemId ?: return@registerForActivityResult
            pendingItemId = null
            if (uris.isEmpty()) { dropIfEmpty(id); reload() } else importPhotos(id, uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityCatalogBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }
        Catalog.load(this)

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.btnAddPhoto.setOnClickListener {
            ItemDialog.edit(this, null) { item -> pickPhotos(item.id) }
        }
        b.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase().orEmpty(); reload()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val all = Catalog.all()
        val shown = if (query.isEmpty()) all else all.filter {
            it.name.lowercase().contains(query) || it.category.lowercase().contains(query) ||
                it.article.lowercase().contains(query) || it.description.lowercase().contains(query)
        }
        val out = ArrayList<Row>()
        shown.groupBy { it.category.ifBlank { "Без категорії" } }.forEach { (cat, list) ->
            out.add(Row.Header("$cat · ${list.size}"))
            list.forEach { out.add(Row.Entry(it)) }
        }
        rows = out
        b.txtTitle.text = "Каталог · ${all.size}"
        b.txtEmpty.isVisible = shown.isEmpty()
        b.txtEmpty.text = if (all.isEmpty())
            "Поки що порожньо.\n\nДодайте виріб на екрані камери («+ Додати») або тут з готових фото («+ З фото»)."
        else "Нічого не знайдено"
        adapter.notifyDataSetChanged()
    }

    private fun openItem(item: Item) {
        val actions = arrayOf(
            "Дозняти камерою",
            "Додати фото з галереї",
            "Переглянути еталони (${item.samples.size})",
            "Редагувати дані",
            "Видалити виріб"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> { setResult(RESULT_OK, Intent().putExtra(EXTRA_ENROLL_ID, item.id)); finish() }
                    1 -> pickPhotos(item.id)
                    2 -> showSamples(item)
                    3 -> ItemDialog.edit(this, item) { reload() }
                    4 -> MaterialAlertDialogBuilder(this)
                        .setTitle("Видалити «${item.name}»?")
                        .setMessage("Буде видалено виріб і всі його еталонні фото (${item.samples.size}).")
                        .setPositiveButton("Видалити") { _, _ -> Catalog.removeItem(item.id); reload() }
                        .setNegativeButton("Скасувати", null)
                        .show()
                }
            }
            .show()
    }

    /** Сітка еталонних фото: торкніться фото, щоб видалити невдале (розмите, не той виріб). */
    private fun showSamples(item0: Item) {
        val item = Catalog.get(item0.id) ?: return
        if (item.samples.isEmpty()) { toast("Еталонів немає — дозніміть камерою"); return }
        val dp = resources.displayMetrics.density
        val grid = RecyclerView(this)
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), 0)
        var samples = item.samples.toList()
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Еталони «${item.name}»")
            .setMessage("Торкніться фото, щоб видалити невдале.")
            .setView(grid)
            .setPositiveButton("Закрити") { _, _ -> reload() }
            .create()
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val iv = ImageView(parent.context).apply {
                    val s = (parent.width / 4).coerceAtLeast((64 * dp).toInt())
                    layoutParams = ViewGroup.LayoutParams(s, s)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setPadding((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
                }
                return object : RecyclerView.ViewHolder(iv) {}
            }
            override fun getItemCount() = samples.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val s = samples[pos]
                (h.itemView as ImageView).setImageBitmap(thumb(s.id))
                h.itemView.setOnClickListener {
                    MaterialAlertDialogBuilder(this@CatalogActivity)
                        .setTitle("Видалити цей еталон?")
                        .setPositiveButton("Видалити") { _, _ ->
                            Catalog.removeSample(item.id, s.id)
                            samples = Catalog.get(item.id)?.samples?.toList() ?: emptyList()
                            notifyDataSetChanged()
                            if (samples.isEmpty()) dlg.dismiss()
                        }
                        .setNegativeButton("Скасувати", null)
                        .show()
                }
            }
        }
        dlg.setOnDismissListener { reload() }
        dlg.show()
    }

    // ---------------- Еталони з галереї (файли друку, фото) ----------------

    private fun pickPhotos(itemId: String) {
        pendingItemId = itemId
        pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun dropIfEmpty(id: String) {
        val item = Catalog.get(id) ?: return
        if (item.samples.isEmpty()) Catalog.removeItem(id)
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
                val emb = embedder ?: Embedder.create(applicationContext, Prefs.model(this), Prefs.isolate(this))
                    .also { embedder = it }
                Catalog.switchKey(emb.key)
                // З виділенням виробу поля не важливі; без нього — два варіанти, як буває при зйомці
                val margins = if (emb.isolate) listOf(0.06f) else listOf(0.03f, 0.15f)
                for ((i, uri) in uris.withIndex()) {
                    val bmp = loadBitmap(uri)
                    if (bmp != null) {
                        for (margin in margins) {
                            val sq = ImageOps.onWhiteSquare(bmp, margin)
                            val hi = ImageOps.onWhiteSquare(bmp, margin, ImageOps.HI)
                            Catalog.addSample(id, sq, hi, emb.describeAugmented(sq), Sample.SRC_GALLERY)
                        }
                        added++
                    }
                    runOnUiThread { progress.setMessage("${i + 1} з ${uris.size}") }
                }
            } catch (e: Throwable) {
                error = e.message
                App.logError(this, "Імпорт фото", e)
            }
            runOnUiThread {
                progress.dismiss()
                dropIfEmpty(id)
                reload()
                toast(error?.let { "Помилка: $it" } ?: "Додано фото: $added. Порада: дозніміть ще 3–5 разів камерою.")
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
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: Exception) {
        null
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.execute { embedder?.close(); embedder = null }
        worker.shutdown()
    }

    // ---------------- Список ----------------

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val dp = parent.resources.displayMetrics.density
                val tv = TextView(parent.context).apply {
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                    alpha = 0.7f
                    setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            return Holder(ItemCatalogBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (h.itemView as TextView).text = row.title.uppercase()
                is Row.Entry -> {
                    val v = (h as Holder).v
                    val item = row.item
                    v.txtName.text = item.name
                    v.txtSub.text = item.shortInfo().ifEmpty { if (item.kind == Kind.FLAT) "плоский" else "об'ємний" }
                    v.imgThumb.setImageBitmap(item.samples.firstOrNull()?.let { thumb(it.id) })
                    val n = item.samples.size
                    v.txtBadge.text = when {
                        n == 0 -> "⚠ немає фото"
                        n < item.targetSamples -> "⚠ $n фото"
                        else -> "✓ $n фото"
                    }
                    v.root.setOnClickListener { openItem(item) }
                }
            }
        }
    }

    private class Holder(val v: ItemCatalogBinding) : RecyclerView.ViewHolder(v.root)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
