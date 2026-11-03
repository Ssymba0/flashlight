package com.ssymba0.flashlight

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Single place that talks to the camera flash.
 *
 * On Android 13+ it uses the torch strength API, which exposes every brightness
 * step the flash hardware supports. Older phones fall back to plain on/off.
 * Smooth mode fills in between the steps (and below the lowest one) with [Pulser].
 */
object Torch {
    private const val PREFS = "torch"
    private const val KEY_LAST_BRIGHTNESS = "last_brightness"
    private const val KEY_SMOOTH = "smooth_mode"

    private lateinit var app: Context
    private lateinit var cameraManager: CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var initialized = false
    private var pulser: Pulser? = null

    var cameraId: String? = null
        private set

    /** Highest hardware brightness step. 1 means the phone only supports on/off. */
    var maxLevel = 1
        private set
    private var defaultLevel = 1

    var isOn = false
        private set

    /** Brightness 0..1 while on. In step mode this is always an exact step. */
    var brightness = 0f
        private set

    /** False while another app (usually the camera) holds the flash. */
    var isAvailable = true
        private set

    val hasFlash get() = cameraId != null
    val supportsLevels get() = maxLevel > 1
    val isPulsing get() = pulser != null && (pulser?.pulseRate ?: 0f) > 0f
    val pulseRate get() = pulser?.pulseRate ?: 0f
    val switchMs get() = pulser?.switchMs ?: 0f

    /** Brightness as 0..1, 0 when off. */
    val fraction get() = if (isOn) brightness else 0f
    val percent get() = (fraction * 100).roundToInt()

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Smooth mode pulses the light to make brightness between the hardware steps. */
    var smoothMode: Boolean
        get() = prefs.getBoolean(KEY_SMOOTH, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SMOOTH, value).apply()
            if (isOn) setBrightness(brightness) else notifyListeners()
        }

    private var lastBrightness: Float
        get() = prefs.getFloat(KEY_LAST_BRIGHTNESS, defaultLevel.toFloat() / maxLevel).coerceIn(0.01f, 1f)
        set(value) { prefs.edit().putFloat(KEY_LAST_BRIGHTNESS, value).apply() }

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
        if (maxLevel == 1) defaultLevel = 1
        // Registering also reports the current torch state right away.
        cameraManager.registerTorchCallback(torchCallback, mainHandler)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyListeners() { listeners.toList().forEach { it() } }

    /** Hardware step used for brightness [b] when not in smooth mode. */
    fun stepFor(b: Float): Int =
        if (b <= 0f) 0 else ceil(b * maxLevel).toInt().coerceIn(1, maxLevel)

    /** The brightness the light will actually show for slider value [b]. */
    fun effectiveBrightness(b: Float): Float =
        if (b <= 0f) 0f else if (smoothMode) b.coerceIn(0f, 1f) else stepFor(b).toFloat() / maxLevel

    /** Sets brightness 0..1 (0 turns it off). Returns false if the flash couldn't be used. */
    fun setBrightness(b: Float): Boolean {
        val id = cameraId ?: return false
        if (b <= 0f) return turnOff()
        val v = b.coerceIn(0f, 1f)
        if (!smoothMode) {
            val wasPulsing = pulser != null
            stopPulser()
            return setLevel(stepFor(v), force = wasPulsing)
        }
        val x = v * maxLevel
        val p = pulser
        if (p != null) {
            p.target = x
        } else {
            val first = ceil(x).toInt().coerceIn(1, maxLevel)
            if (!command(id, first)) return false
            pulser = Pulser(cameraManager, id, maxLevel, x, first) {
                mainHandler.post { onPulseFailed() }
            }.also { it.start() }
        }
        markOn(v)
        return true
    }

    private fun setLevel(l: Int, force: Boolean): Boolean {
        val id = cameraId ?: return false
        val lv = l.coerceIn(1, maxLevel)
        val alreadyThere = !force && isOn && brightness == lv.toFloat() / maxLevel
        if (!alreadyThere && !command(id, lv)) return false
        markOn(lv.toFloat() / maxLevel)
        return true
    }

    private fun command(id: String, level: Int): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33 && maxLevel > 1) {
            cameraManager.turnOnTorchWithStrengthLevel(id, level)
        } else {
            cameraManager.setTorchMode(id, true)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun markOn(b: Float) {
        val wasOn = isOn
        isOn = true
        brightness = b
        isAvailable = true
        lastBrightness = b
        // Keeps our process alive so Android doesn't switch the light off behind our back.
        if (!wasOn || !TorchService.running) TorchService.start(app)
        notifyListeners()
    }

    fun turnOff(): Boolean {
        val id = cameraId ?: return false
        stopPulser()
        try {
            cameraManager.setTorchMode(id, false)
        } catch (_: Exception) {
            return false
        }
        isOn = false
        brightness = 0f
        notifyListeners()
        return true
    }

    fun toggle(): Boolean = if (isOn) turnOff() else setBrightness(lastBrightness)

    private fun stopPulser() {
        pulser?.shutdown()
        pulser = null
    }

    private fun onPulseFailed() {
        if (pulser == null) return
        stopPulser()
        isOn = false
        brightness = 0f
        isAvailable = false
        notifyListeners()
    }

    /** Everything the phone reports about flash brightness control, for troubleshooting. */
    fun diagnostics(): String {
        val sb = StringBuilder()
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
        sb.append("Using camera: ").append(cameraId).append("\n")
        sb.append("Smooth mode: ").append(if (smoothMode) "on" else "off")
        pulser?.let { sb.append(", ").append(it.pulseRate.roundToInt()).append(" pulses/s, switch ")
            .append("%.1f".format(it.switchMs)).append(" ms") }
        sb.append("\n")
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
            if (id != cameraId || pulser != null) return // our own pulses
            isAvailable = true
            isOn = enabled
            brightness = if (enabled) readHardwareLevel(id).toFloat() / maxLevel else 0f
            notifyListeners()
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id != cameraId) return
            stopPulser()
            isAvailable = false
            isOn = false
            brightness = 0f
            notifyListeners()
        }

        override fun onTorchStrengthLevelChanged(id: String, newStrengthLevel: Int) {
            if (id != cameraId || !isOn || pulser != null) return
            brightness = newStrengthLevel.coerceIn(1, maxLevel).toFloat() / maxLevel
            notifyListeners()
        }
    }
}
