package org.fossify.keyboard.helpers

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import org.fossify.keyboard.views.KeyDetector
import org.fossify.keyboard.views.KeyGeometry
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * On-device touch personalization engine managing hierarchical spatial models,
 * backspace error filtering, and debounced asynchronous persistence.
 *
 * All data remains strictly local to the device.
 */
class TouchPersonalizationManager private constructor(private val context: Context) {

    companion object {
        private const val PERSISTENCE_FILENAME = "touch_personalization.json"
        private const val SAVE_DEBOUNCE_DELAY_MS = 5000L

        @Volatile
        private var INSTANCE: TouchPersonalizationManager? = null

        fun getInstance(context: Context): TouchPersonalizationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TouchPersonalizationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val settingsCache = SettingsCache.getInstance(context)
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Separate models partitioned by: "${orientation}_${layoutId}"
    private val models = ConcurrentHashMap<String, HierarchicalOffsetModel>()

    private var isDirty = false

    // Pending tap buffer for the backspace rejection filter
    private var pendingTap: PendingTap? = null

    data class PendingTap(
        val modelKey: String,
        val keyCode: Int,
        val rowIndex: Int,
        val ndx: Double,
        val ndy: Double,
        val isSpace: Boolean,
        val timestampMs: Long
    )

    private val saveRunnable = Runnable {
        if (isDirty) {
            saveToDiskAsync()
        }
    }

    init {
        loadFromDiskAsync()
    }

    /**
     * Formulates a partition key for the active orientation and layout.
     */
    fun getModelKey(orientation: Int, layoutId: String, heightPreset: Int = settingsCache.keyboardHeightPreset): String {
        val orientStr = if (orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"
        return "${orientStr}_${layoutId}_h${heightPreset}"
    }

    fun getModel(orientation: Int, layoutId: String): HierarchicalOffsetModel {
        val key = getModelKey(orientation, layoutId, settingsCache.keyboardHeightPreset)
        return models.getOrPut(key) { HierarchicalOffsetModel(key) }
    }

    /**
     * Records a key tap with backspace rejection filtering.
     *
     * If the tap was a normal character or space:
     * - Any previously buffered pending tap is committed (as it was NOT followed by backspace).
     * - The new tap becomes the pending tap.
     *
     * If the tap is a backspace (KEYCODE_DELETE):
     * - The pending tap is immediately discarded (it was an error tap).
     */
    fun onKeyCommitted(
        orientation: Int,
        layoutId: String,
        key: MyKeyboard.Key,
        rowIndex: Int,
        touchX: Float,
        touchY: Float
    ) {
        if (!settingsCache.personaliseTouch) return

        if (key.code == MyKeyboard.KEYCODE_DELETE) {
            // Error detected: discard the preceding key tap so mis-taps do NOT train the model
            pendingTap = null
            return
        }

        // Only learn from standard characters and spacebar (exclude shift, symbols mode, etc.)
        if (key.code <= 0 && key.code != MyKeyboard.KEYCODE_SPACE) {
            commitPendingTap()
            return
        }

        // Commit previous pending tap if present
        commitPendingTap()

        // Calculate normalized offset from key drawn center
        val keyCenterX = (key.x + key.width / 2f)
        val keyCenterY = (key.y + key.height / 2f)

        val dx = (touchX - keyCenterX).toDouble()
        val dy = (touchY - keyCenterY).toDouble()

        val ndx = if (key.width > 0) dx / key.width else 0.0
        val ndy = if (key.height > 0) dy / key.height else 0.0

        val modelKey = getModelKey(orientation, layoutId)
        pendingTap = PendingTap(
            modelKey = modelKey,
            keyCode = key.code,
            rowIndex = rowIndex,
            ndx = ndx,
            ndy = ndy,
            isSpace = (key.code == MyKeyboard.KEYCODE_SPACE),
            timestampMs = System.currentTimeMillis()
        )

        scheduleDebouncedSave()
    }

    /**
     * Flushes the pending tap into its hierarchical model.
     */
    fun commitPendingTap() {
        val tap = pendingTap ?: return
        pendingTap = null

        val model = models.getOrPut(tap.modelKey) { HierarchicalOffsetModel(tap.modelKey) }
        model.recordTap(
            keyCode = tap.keyCode,
            rowIndex = tap.rowIndex,
            normalizedDx = tap.ndx,
            normalizedDy = tap.ndy,
            timestampMs = tap.timestampMs
        )
        isDirty = true
    }

    /**
     * Applies learned center offsets and variances to all key geometries in a [KeyDetector].
     */
    fun applyOffsetsToDetector(
        detector: KeyDetector,
        orientation: Int,
        layoutId: String
    ) {
        val isEnabled = settingsCache.personaliseTouch
        val model = if (isEnabled) getModel(orientation, layoutId) else null

        // Group geometries by approximate row to determine rowIndex
        var currentRowY = -1000f
        var currentRowIndex = -1

        for (geom in detector.keyGeometries) {
            if (geom.drawnTop - currentRowY > 16f || currentRowIndex == -1) {
                currentRowIndex++
                currentRowY = geom.drawnTop
            }

            if (!isEnabled || model == null) {
                geom.setCenterOffset(0f, 0f)
                geom.sigmaX = kotlin.math.max(geom.width * 0.50f, 1f)
                geom.sigmaY = kotlin.math.max(geom.height * 0.50f, 1f)
            } else {
                val isSpace = (geom.key.code == MyKeyboard.KEYCODE_SPACE)
                val (ox, oy) = model.getOffset(
                    keyCode = geom.key.code,
                    rowIndex = currentRowIndex,
                    keyWidth = geom.width,
                    keyHeight = geom.height,
                    isSpace = isSpace
                )
                geom.setCenterOffset(ox, oy)

                val (sx, sy) = model.getSigma(
                    keyCode = geom.key.code,
                    rowIndex = currentRowIndex,
                    keyWidth = geom.width,
                    keyHeight = geom.height
                )
                geom.sigmaX = sx
                geom.sigmaY = sy
            }
        }
    }

    /**
     * Schedules a debounced background save to avoid disk writes during active typing.
     */
    fun scheduleDebouncedSave() {
        mainHandler.removeCallbacks(saveRunnable)
        mainHandler.postDelayed(saveRunnable, SAVE_DEBOUNCE_DELAY_MS)
    }

    /**
     * Asynchronously serializes models to JSON on a dedicated background thread.
     */
    fun saveToDiskAsync() {
        commitPendingTap()
        val snapshot = HashMap(models)
        isDirty = false

        backgroundExecutor.execute {
            try {
                val root = JSONObject()
                root.put("version", 1)

                val modelsObj = JSONObject()
                for ((key, model) in snapshot) {
                    modelsObj.put(key, model.toJson())
                }
                root.put("models", modelsObj)

                val file = File(context.filesDir, PERSISTENCE_FILENAME)
                file.writeText(root.toString())
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Asynchronously loads existing models from disk.
     */
    private fun loadFromDiskAsync() {
        backgroundExecutor.execute {
            try {
                val file = File(context.filesDir, PERSISTENCE_FILENAME)
                if (!file.exists()) return@execute

                val text = file.readText()
                val root = JSONObject(text)
                val modelsObj = root.optJSONObject("models") ?: return@execute

                val loaded = HashMap<String, HierarchicalOffsetModel>()
                val keys = modelsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val mJson = modelsObj.getJSONObject(key)
                    loaded[key] = HierarchicalOffsetModel.fromJson(key, mJson)
                }

                models.putAll(loaded)
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Permanently wipes all learned touch data from memory and device disk.
     */
    fun resetAllData(callback: (() -> Unit)? = null) {
        pendingTap = null
        models.clear()
        isDirty = false

        backgroundExecutor.execute {
            try {
                val file = File(context.filesDir, PERSISTENCE_FILENAME)
                if (file.exists()) {
                    file.delete()
                }
            } catch (ignored: Exception) {
            }
            if (callback != null) {
                mainHandler.post(callback)
            }
        }
    }

    fun flushOnPause() {
        commitPendingTap()
        if (isDirty) {
            saveToDiskAsync()
        }
    }
}
