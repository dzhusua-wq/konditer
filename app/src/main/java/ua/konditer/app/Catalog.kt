package ua.konditer.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Один еталонний знімок виробу: «відбиток» нейромережі + колірний опис. */
class Shot(val emb: FloatArray, val color: FloatArray)

/** Виріб у каталозі. */
class Item(
    val id: String,
    var name: String,
    var weight: String,
    var note: String,
    val shots: MutableList<Shot> = mutableListOf()
)

/** Каталог виробів, що зберігається у JSON-файлі на телефоні. Працює повністю офлайн. */
object Catalog {
    private const val FILE = "catalog.json"
    private val items = mutableListOf<Item>()
    private var loaded = false

    /** Незмінна копія для потоку розпізнавання. Оновлюється після кожної зміни. */
    @Volatile
    var snapshot: List<Item> = emptyList()
        private set

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) {
            try {
                items.clear()
                items.addAll(fromJson(f.readText()))
            } catch (_: Exception) {
            }
        }
        refresh()
    }

    @Synchronized
    fun save(ctx: Context) {
        refresh()
        val f = File(ctx.filesDir, FILE)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(toJson())
        tmp.renameTo(f)
    }

    @Synchronized
    fun all(): List<Item> = items.toList()

    @Synchronized
    fun get(id: String): Item? = items.find { it.id == id }

    @Synchronized
    fun create(name: String, weight: String, note: String): Item {
        val item = Item(UUID.randomUUID().toString(), name, weight, note)
        items.add(item)
        items.sortBy { it.name.lowercase() }
        refresh()
        return item
    }

    @Synchronized
    fun addShot(id: String, shot: Shot) {
        get(id)?.shots?.add(shot)
        refresh()
    }

    @Synchronized
    fun remove(id: String) {
        items.removeAll { it.id == id }
        refresh()
    }

    /** Імпорт: вироби з тим самим id замінюються, нові додаються. Повертає кількість. */
    @Synchronized
    fun importJson(json: String): Int {
        val incoming = fromJson(json)
        for (it in incoming) {
            items.removeAll { old -> old.id == it.id }
            items.add(it)
        }
        items.sortBy { it.name.lowercase() }
        refresh()
        return incoming.size
    }

    @Synchronized
    fun toJson(): String {
        val arr = JSONArray()
        for (it in items) {
            val shots = JSONArray()
            for (s in it.shots) {
                shots.put(JSONObject().put("e", encode(s.emb)).put("c", encode(s.color)))
            }
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("weight", it.weight)
                    .put("note", it.note)
                    .put("shots", shots)
            )
        }
        return JSONObject().put("version", 1).put("items", arr).toString()
    }

    private fun fromJson(json: String): List<Item> {
        val root = JSONObject(json)
        val arr = root.getJSONArray("items")
        val out = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val shots = mutableListOf<Shot>()
            val sa = o.optJSONArray("shots") ?: JSONArray()
            for (j in 0 until sa.length()) {
                val so = sa.getJSONObject(j)
                shots.add(Shot(decode(so.getString("e")), decode(so.getString("c"))))
            }
            out.add(
                Item(
                    o.getString("id"),
                    o.optString("name"),
                    o.optString("weight"),
                    o.optString("note"),
                    shots
                )
            )
        }
        return out
    }

    private fun refresh() {
        snapshot = items.map { Item(it.id, it.name, it.weight, it.note, it.shots.toMutableList()) }
    }

    private fun encode(v: FloatArray): String {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putFloat(x)
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    private fun decode(s: String): FloatArray {
        val bb = ByteBuffer.wrap(Base64.decode(s, Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bb.remaining() / 4) { bb.getFloat() }
    }
}
