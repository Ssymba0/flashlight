package com.ssymba0.flashlight

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt

/**
 * Single place that talks to the camera flash.
 *
 * On Android 13+ it uses the torch strength API, which exposes every brightness
 * step the flash hardware supports. Older phones fall back to plain on/off.
 */
object Torch {
    private const val PREFS = "torch"
    private const val KEY_LAST_LEVEL = "last_level"

    private lateinit var app: Context
    private lateinit var cameraManager: CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var initialized = false

    var cameraId: String? = null
        private set

    /** Highest hardware brightness step. 1 means the phone only supports on/off. */
    var maxLevel = 1
        private set

    var isOn = false
        private set

    /** Current hardware step while on, 0 while off. */
    var level = 0
        private set

    /** False while another app (usually the camera) holds the flash. */
    var isAvailable = true
        private set

    val hasFlash get() = cameraId != null
    val supportsLevels get() = maxLevel > 1

    /** Brightness as 0..1, 0 when off. */
    val fraction get() = if (isOn) level.toFloat() / maxLevel else 0f
    val percent get() = (fraction * 100).roundToInt()

    private var lastLevel: Int
        get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_LEVEL, defaultLevel).coerceIn(1, maxLevel)
        set(value) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LAST_LEVEL, value).apply()
        }
    private var defaultLevel = 1

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        cameraManager = app.getSystemService(CameraManager::class.java)
        cameraId = findFlashCamera()
        val id = cameraId
        if (id != null && Build.VERSION.SDK_INT >= 33) {
            try {
                val c = cameraManager.getCameraCharacteristics(id)
                maxLevel = (c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1).coerceAtLeast(1)
                defaultLevel = (c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) ?: maxLevel)
                    .coerceIn(1, maxLevel)
            } catch (_: Exception) {
            }
        }
        if (defaultLevel == 1) defaultLevel = maxLevel
        // Registering also reports the current torch state right away.
        cameraManager.registerTorchCallback(torchCallback, mainHandler)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyListeners() { listeners.toList().forEach { it() } }

    /** Turns the light on at [newLevel] (1..maxLevel), or off for 0. Returns false on failure. */
    fun setLevel(newLevel: Int): Boolean {
        val id = cameraId ?: return false
        if (newLevel <= 0) return turnOff()
        val l = newLevel.coerceIn(1, maxLevel)
        try {
            if (Build.VERSION.SDK_INT >= 33 && maxLevel > 1) {
                cameraManager.turnOnTorchWithStrengthLevel(id, l)
            } else {
                cameraManager.setTorchMode(id, true)
            }
        } catch (_: Exception) {
            return false
        }
        val wasOn = isOn
        isOn = true
        level = l
        isAvailable = true
        lastLevel = l
        // Keeps our process alive so Android doesn't switch the light off behind our back.
        if (!wasOn || !TorchService.running) TorchService.start(app)
        notifyListeners()
        return true
    }

    fun turnOff(): Boolean {
        val id = cameraId ?: return false
        try {
            cameraManager.setTorchMode(id, false)
        } catch (_: Exception) {
            return false
        }
        isOn = false
        level = 0
        notifyListeners()
        return true
    }

    fun toggle(): Boolean = if (isOn) turnOff() else setLevel(lastLevel)

    /** Everything the phone reports about flash brightness control, for troubleshooting. */
    fun diagnostics(): String {
        val sb = StringBuilder()
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
        sb.append("Using camera: ").append(cameraId).append("\n")
        val ids = try { cameraManager.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        for (id in ids) {
            val c = try { cameraManager.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                else -> "other"
            }
            sb.append("\nCamera ").append(id).append(" (").append(facing).append(")")
            sb.append(" flash=").append(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
            if (Build.VERSION.SDK_INT >= 33) {
                sb.append("\n  torch max=").append(c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL))
                sb.append(" default=").append(c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL))
            }
            if (Build.VERSION.SDK_INT >= 35) {
                sb.append("\n  session torch max=").append(c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL))
                sb.append(" default=").append(c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_DEFAULT_LEVEL))
                sb.append("\n  session flash max=").append(c.get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_MAX_LEVEL))
            }
            // Any other flash/torch keys, including manufacturer-specific ones.
            for (key in c.keys) {
                val name = key.name
                if (!name.contains("flash", true) && !name.contains("torch", true)) continue
                if (name.startsWith("android.flash.")) continue
                val v = try { describe(c.get(key)) } catch (_: Exception) { "?" }
                sb.append("\n  ").append(name).append('=').append(v)
            }
        }
        return sb.toString()
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.joinToString(",", "[", "]")
        is FloatArray -> v.joinToString(",", "[", "]")
        is LongArray -> v.joinToString(",", "[", "]")
        is ByteArray -> v.joinToString(",", "[", "]")
        is BooleanArray -> v.joinToString(",", "[", "]")
        is Array<*> -> v.joinToString(",", "[", "]")
        else -> v.toString()
    }

    private fun readHardwareLevel(id: String): Int {
        if (Build.VERSION.SDK_INT >= 33 && maxLevel > 1) {
            try {
                return cameraManager.getTorchStrengthLevel(id).coerceIn(1, maxLevel)
            } catch (_: Exception) {
            }
        }
        return maxLevel
    }

    private fun findFlashCamera(): String? {
        val ids = try { cameraManager.cameraIdList } catch (_: Exception) { return null }
        var fallback: String? = null
        for (id in ids) {
            val c = try { cameraManager.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            isAvailable = true
            isOn = enabled
            level = if (enabled) readHardwareLevel(id) else 0
            notifyListeners()
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id != cameraId) return
            isAvailable = false
            isOn = false
            level = 0
            notifyListeners()
        }

        override fun onTorchStrengthLevelChanged(id: String, newStrengthLevel: Int) {
            if (id != cameraId || !isOn) return
            level = newStrengthLevel.coerceIn(1, maxLevel)
            notifyListeners()
        }
    }
}
