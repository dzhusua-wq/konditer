package ua.konditer.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Нейромережа, що перетворює фото на вектор-«відбиток».
 * Перед цим (за налаштуванням) виріб відокремлюється від фону.
 * Не потокобезпечний: використовувати з одного потоку.
 */
abstract class Embedder(val isolate: Boolean) : Closeable {

    abstract val model: ModelChoice

    /** Ключ, під яким зберігаються вектори: різні моделі/режими не змішуються. */
    val key: String get() = model.id + if (isolate) "+iso" else ""

    protected abstract fun embed(bmp: Bitmap): FloatArray

    private fun prep(bmp: Bitmap): Bitmap = if (isolate) ImageOps.isolate(bmp) else bmp

    fun describe(bmp: Bitmap): Shot {
        val p = prep(bmp)
        return Shot(embed(p), ImageOps.colorDescriptor(p))
    }

    /** Оригінал + повороти на 90/180/270°: виріб упізнається під будь-яким кутом. */
    fun describeAugmented(bmp: Bitmap): List<Shot> {
        val p = prep(bmp)
        val color = ImageOps.colorDescriptor(p)
        return listOf(0f, 90f, 180f, 270f).map { Shot(embed(ImageOps.rotate(p, it)), color) }
    }

    companion object {
        fun create(ctx: Context, model: ModelChoice, isolate: Boolean): Embedder = when (model) {
            ModelChoice.ACCURATE -> DinoEmbedder(ctx, isolate)
            ModelChoice.FAST -> MobileNetEmbedder(ctx, isolate)
        }

        fun l2(v: FloatArray): FloatArray {
            var s = 0f
            for (x in v) s += x * x
            val n = sqrt(s)
            if (n > 0f) for (i in v.indices) v[i] /= n
            return v
        }
    }
}

/** Швидкий режим: MobileNetV3-large через MediaPipe (~25 мс на кадр). */
class MobileNetEmbedder(context: Context, isolate: Boolean) : Embedder(isolate) {
    override val model = ModelChoice.FAST

    private val embedder: ImageEmbedder = ImageEmbedder.createFromOptions(
        context,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("mobilenet_v3_large.tflite").build())
            .setL2Normalize(true)
            .setQuantize(false)
            .setRunningMode(RunningMode.IMAGE)
            .build()
    )

    override fun embed(bmp: Bitmap): FloatArray =
        embedder.embed(BitmapImageBuilder(bmp).build()).embeddingResult().embeddings()[0].floatEmbedding()

    override fun close() = embedder.close()
}

/**
 * Точний режим: DINOv2-small (Meta) через ONNX Runtime.
 * Навчена розрізняти саме конкретні об'єкти, тож краще бачить різницю між схожими виробами.
 * Вектор = [CLS-токен ‖ середнє патч-токенів], кожна половина нормована.
 */
class DinoEmbedder(context: Context, isolate: Boolean) : Embedder(isolate) {
    override val model = ModelChoice.ACCURATE

    private val size = 224
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val input = ByteBuffer.allocateDirect(4 * 3 * size * size).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val px = IntArray(size * size)

    init {
        val bytes = context.assets.open("dinov2_small_q.onnx").use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.first()
    }

    override fun embed(bmp: Bitmap): FloatArray {
        val s = Bitmap.createScaledBitmap(bmp, size, size, true)
        s.getPixels(px, 0, size, 0, 0, size, size)
        // Нормалізація ImageNet, формат CHW
        val plane = size * size
        input.rewind()
        for (c in 0 until 3) {
            val mean = MEAN[c]; val std = STD[c]
            val shift = 16 - 8 * c
            for (i in 0 until plane) input.put((((px[i] shr shift) and 0xFF) / 255f - mean) / std)
        }
        input.rewind()

        return OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val value = res.get("last_hidden_state").orElse(null) ?: res.get(0)
                toVector(value as OnnxTensor)
            }
        }
    }

    private fun toVector(tensor: OnnxTensor): FloatArray {
        val shape = (tensor.info as TensorInfo).shape
        val fb = tensor.floatBuffer
        if (shape.size == 2) {
            // Лише pooled-вектор [1, D]
            return Embedder.l2(FloatArray(shape[1].toInt()) { fb.get(it) })
        }
        val tokens = shape[1].toInt()
        val dim = shape[2].toInt()
        val cls = FloatArray(dim) { fb.get(it) }
        val mean = FloatArray(dim)
        for (tk in 1 until tokens) {
            val base = tk * dim
            for (d in 0 until dim) mean[d] += fb.get(base + d)
        }
        Embedder.l2(cls); Embedder.l2(mean)
        val out = FloatArray(dim * 2)
        System.arraycopy(cls, 0, out, 0, dim)
        System.arraycopy(mean, 0, out, dim, dim)
        return Embedder.l2(out)
    }

    override fun close() {
        session.close()
    }

    private companion object {
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
