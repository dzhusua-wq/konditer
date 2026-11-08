package ua.konditer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Half
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Каталог виробів на телефоні (офлайн).
 *
 *  catalog/catalog.json        — дані виробів (назва, вага, ціна…) і список еталонів
 *  catalog/samples/<id>.jpg    — фото кожного еталона (кроп рамки 256×256)
 *  catalog/samples/<id>.vec    — вектори нейромережі (float16), рахуються з фото
 *
 * Фото зберігаються навмисно: якщо в новій версії зміниться нейромережа,
 * вектори перерахуються з фото автоматично, переснімати нічого не доведеться.
 */
object Catalog {
    const val BACKGROUND = "#background"
    const val MAX_SAMPLES = 40

    val DEFAULT_CATEGORIES = listOf(
        "Квіти", "Бантики", "Листочки", "Безе", "Фігурки", "Топери",
        "Печиво з малюнком", "Посипка", "Цукерки", "Інше"
    )

    private val items = mutableListOf<Item>()
    private val background = mutableListOf<Sample>()
    private var modelId = Embedder.MODEL_ID
    private var loaded = false
    private lateinit var root: File
    private lateinit var samplesDir: File

    /** Актуальний зліпок для пошуку. */
    @Volatile
    var index: MatchIndex = MatchIndex.NONE
        private set

    /** Збільшується при кожній зміні — екрани можуть перевірити, чи треба оновитись. */
    @Volatile
    var version = 0
        private set

    // ---------------- Завантаження / збереження ----------------

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        root = File(ctx.filesDir, "catalog").apply { mkdirs() }
        samplesDir = File(root, "samples").apply { mkdirs() }
        val f = File(root, "catalog.json")
        try {
            if (f.exists()) parse(JSONObject(f.readText()), replace = true)
            else migrateV1(ctx)
        } catch (e: Exception) {
            File(root, "catalog.json").copyTo(File(root, "catalog.broken.json"), overwrite = true)
        }
        rebuild()
    }

    @Synchronized
    private fun save() {
        val tmp = File(root, "catalog.json.tmp")
        tmp.writeText(toJson().toString())
        tmp.renameTo(File(root, "catalog.json"))
    }

    private fun toJson(): JSONObject {
        val arr = JSONArray()
        for (it in items) arr.put(itemJson(it))
        return JSONObject()
            .put("format", 2)
            .put("model", modelId)
            .put("items", arr)
            .put("background", samplesJson(background))
    }

    private fun itemJson(it: Item) = JSONObject()
        .put("id", it.id).put("name", it.name).put("category", it.category)
        .put("kind", it.kind.name).put("weight", it.weight).put("article", it.article)
        .put("price", it.price).put("description", it.description)
        .put("createdAt", it.createdAt).put("samples", samplesJson(it.samples))

    private fun samplesJson(list: List<Sample>): JSONArray {
        val a = JSONArray()
        for (s in list) a.put(JSONObject().put("id", s.id).put("source", s.source).put("createdAt", s.createdAt))
        return a
    }

    private fun samplesFrom(a: JSONArray?): MutableList<Sample> {
        val out = mutableListOf<Sample>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val s = Sample(o.getString("id"), o.optString("source", Sample.SRC_CAMERA), o.optLong("createdAt"))
            if (imageFile(s.id).exists()) {
                s.shots = readVec(s.id)
                out.add(s)
            }
        }
        return out
    }

    /** Розбирає catalog.json. replace=false — злиття (імпорт). Повертає кількість виробів. */
    private fun parse(j: JSONObject, replace: Boolean): Int {
        val fileModel = j.optString("model", "")
        if (replace) {
            items.clear(); background.clear()
            modelId = fileModel.ifEmpty { Embedder.MODEL_ID }
        }
        val arr = j.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val item = Item(
                id = o.getString("id"),
                name = o.optString("name"),
                category = o.optString("category"),
                kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.VOLUME),
                weight = o.optString("weight"),
                article = o.optString("article"),
                price = o.optString("price"),
                description = o.optString("description"),
                samples = samplesFrom(o.optJSONArray("samples")),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
            items.find { it.id == item.id }?.let { old ->
                old.samples.filter { s -> item.samples.none { it.id == s.id } }.forEach { deleteFiles(it.id) }
                items.remove(old)
            }
            items.add(item)
        }
        val bg = samplesFrom(j.optJSONArray("background"))
        if (replace || background.isEmpty()) background.addAll(bg)
        sortItems()
        return arr.length()
    }

    /** Перша версія зберігала тільки вектори без фото: переносимо дані виробів, знімки треба зробити заново. */
    private fun migrateV1(ctx: Context) {
        val old = File(ctx.filesDir, "catalog.json")
        if (!old.exists()) return
        try {
            val arr = JSONObject(old.readText()).getJSONArray("items")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    Item(
                        id = o.getString("id"), name = o.optString("name"),
                        weight = o.optString("weight"), description = o.optString("note")
                    )
                )
            }
            sortItems()
            save()
        } catch (_: Exception) {
        }
        old.renameTo(File(ctx.filesDir, "catalog_v1_backup.json"))
    }

    private fun sortItems() = items.sortWith(compareBy({ it.category.lowercase() }, { it.name.lowercase() }))

    @Synchronized
    private fun rebuild() {
        val copies = items.map {
            Item(it.id, it.name, it.category, it.kind, it.weight, it.article, it.price,
                it.description, it.samples.toMutableList(), it.createdAt)
        }
        index = MatchIndex.build(copies, background.toList())
        version++
    }

    // ---------------- Читання ----------------

    @Synchronized fun all(): List<Item> = items.toList()
    @Synchronized fun get(id: String): Item? = items.find { it.id == id }
    @Synchronized fun backgroundCount(): Int = background.size
    @Synchronized fun backgroundSamples(): List<Sample> = background.toList()
    @Synchronized fun sampleCount(): Int = items.sumOf { it.samples.size } + background.size

    @Synchronized
    fun categories(): List<String> =
        (items.map { it.category }.filter { it.isNotBlank() } + DEFAULT_CATEGORIES).distinct()

    fun imageFile(sampleId: String) = File(samplesDir, "$sampleId.jpg")
    private fun vecFile(sampleId: String) = File(samplesDir, "$sampleId.vec")

    /** Фото для мініатюри виробу (перший еталон). */
    @Synchronized
    fun thumbFile(item: Item): File? = item.samples.firstOrNull()?.let { imageFile(it.id) }?.takeIf { it.exists() }

    // ---------------- Зміни ----------------

    @Synchronized
    fun create(name: String): Item {
        val item = Item(UUID.randomUUID().toString(), name)
        items.add(item)
        sortItems(); save(); rebuild()
        return item
    }

    @Synchronized
    fun update(id: String, block: Item.() -> Unit) {
        get(id)?.block()
        sortItems(); save(); rebuild()
    }

    @Synchronized
    fun removeItem(id: String) {
        val item = get(id) ?: return
        item.samples.forEach { deleteFiles(it.id) }
        items.remove(item)
        save(); rebuild()
    }

    /** Додає еталон виробу (або фону, якщо target == BACKGROUND). Повертає null, якщо ліміт вичерпано. */
    fun addSample(target: String, crop: Bitmap, shots: List<Shot>, source: String): Sample? {
        val s = Sample(UUID.randomUUID().toString(), source, System.currentTimeMillis(), shots)
        FileOutputStream(imageFile(s.id)).use { crop.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        writeVec(s.id, shots)
        synchronized(this) {
            val list = if (target == BACKGROUND) background else get(target)?.samples
            if (list == null || list.size >= MAX_SAMPLES) {
                deleteFiles(s.id); return null
            }
            list.add(s)
            save(); rebuild()
        }
        return s
    }

    @Synchronized
    fun removeSample(target: String, sampleId: String) {
        val list = if (target == BACKGROUND) background else get(target)?.samples ?: return
        if (list.removeAll { it.id == sampleId }) {
            deleteFiles(sampleId)
            save(); rebuild()
        }
    }

    @Synchronized
    fun clearBackground() {
        background.forEach { deleteFiles(it.id) }
        background.clear()
        save(); rebuild()
    }

    private fun deleteFiles(id: String) {
        imageFile(id).delete(); vecFile(id).delete()
    }

    // ---------------- Перерахунок векторів (нова модель) ----------------

    @Synchronized
    fun needsReindex(): Boolean {
        val all = items.flatMap { it.samples } + background
        return all.isNotEmpty() && (modelId != Embedder.MODEL_ID || all.any { it.shots.isEmpty() })
    }

    /** Перераховує вектори з фото. Викликати у фоновому потоці з тим самим [embedder]. */
    fun reindex(embedder: Embedder, progress: (Int, Int) -> Unit) {
        val todo: List<Sample> = synchronized(this) {
            val all = items.flatMap { it.samples } + background
            if (modelId != Embedder.MODEL_ID) all else all.filter { it.shots.isEmpty() }
        }
        todo.forEachIndexed { i, s ->
            val bmp = BitmapFactory.decodeFile(imageFile(s.id).path)
            if (bmp != null) {
                val shots = embedder.describeAugmented(bmp)
                writeVec(s.id, shots)
                s.shots = shots
            }
            progress(i + 1, todo.size)
        }
        synchronized(this) {
            modelId = Embedder.MODEL_ID
            save(); rebuild()
        }
    }

    // ---------------- Вектори на диску (float16) ----------------

    private const val MAGIC = 0x4B4E4431 // "KND1"

    private fun writeVec(id: String, shots: List<Shot>) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(vecFile(id)))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(shots.size)
            out.writeInt(shots.firstOrNull()?.emb?.size ?: 0)
            out.writeInt(shots.firstOrNull()?.color?.size ?: 0)
            for (s in shots) {
                for (v in s.emb) out.writeShort(Half.toHalf(v).toInt())
                for (v in s.color) out.writeFloat(v)
            }
        }
    }

    private fun readVec(id: String): List<Shot> {
        val f = vecFile(id)
        if (!f.exists()) return emptyList()
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(f))).use { inp ->
                if (inp.readInt() != MAGIC) return emptyList()
                val n = inp.readInt(); val dim = inp.readInt(); val cdim = inp.readInt()
                List(n) {
                    val e = FloatArray(dim) { Half.toFloat(inp.readShort()) }
                    val c = FloatArray(cdim) { inp.readFloat() }
                    Shot(e, c)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------- Експорт / імпорт (zip) ----------------

    private val SAFE_NAME = Regex("[A-Za-z0-9-]+\\.(jpg|vec)")

    @Synchronized
    fun exportZip(out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("catalog.json"))
            zip.write(toJson().toString(2).toByteArray())
            zip.closeEntry()
            val ids = items.flatMap { it.samples }.map { it.id } + background.map { it.id }
            for (id in ids) for (f in listOf(imageFile(id), vecFile(id))) {
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("samples/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Імпорт: вироби з тим самим id замінюються, нові додаються. Повертає кількість виробів у файлі. */
    fun importZip(input: InputStream): Int {
        var json: JSONObject? = null
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                when {
                    e.name == "catalog.json" -> json = JSONObject(zip.readBytes().decodeToString())
                    e.name.startsWith("samples/") && SAFE_NAME.matches(name) ->
                        FileOutputStream(File(samplesDir, name)).use { zip.copyTo(it) }
                }
            }
        }
        val j = json ?: throw IllegalArgumentException("у файлі немає catalog.json")
        // Вектори іншої моделі непридатні — видаляємо, вони перерахуються з фото
        if (j.optString("model") != Embedder.MODEL_ID) {
            val ids = mutableListOf<String>()
            val arr = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val ss = arr.getJSONObject(i).optJSONArray("samples") ?: continue
                for (k in 0 until ss.length()) ids.add(ss.getJSONObject(k).getString("id"))
            }
            val bg = j.optJSONArray("background") ?: JSONArray()
            for (k in 0 until bg.length()) ids.add(bg.getJSONObject(k).getString("id"))
            ids.forEach { vecFile(it).delete() }
        }
        synchronized(this) {
            val n = parse(j, replace = false)
            save(); rebuild()
            return n
        }
    }
}
