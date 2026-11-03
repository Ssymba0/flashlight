package com.ssymba0.flashlight

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sign

/**
 * Tall rounded slider modelled on the iOS Control Center flashlight.
 * Drag anywhere to adjust relative to where you started; tap to jump to a spot.
 * Dragging past either end stretches the pill a little, like iOS.
 */
class BrightnessSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Called continuously while the user drags or taps, with the new value 0..1. */
    var onValueChange: ((Float) -> Unit)? = null

    /** Called when the finger lifts. */
    var onRelease: (() -> Unit)? = null

    /** Shown value, 0..1. */
    var value = 0f
        private set

    var isTracking = false
        private set

    private val dp = resources.displayMetrics.density
    private val stretchMax = 14 * dp
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2E.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val icon = context.getDrawable(R.drawable.ic_flashlight)!!.mutate()
    private val pill = RectF()
    private val clip = Path()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var stretch = 0f // >0 pulled past the top, <0 pulled past the bottom
    private var downY = 0f
    private var downValue = 0f
    private var dragging = false
    private var valueAnim: ValueAnimator? = null
    private var stretchAnim: ValueAnimator? = null

    init {
        isClickable = true
        contentDescription = context.getString(R.string.brightness)
    }

    fun setValue(v: Float, animate: Boolean) {
        val target = v.coerceIn(0f, 1f)
        valueAnim?.cancel()
        if (!animate || target == value) {
            value = target
            invalidate()
            return
        }
        valueAnim = ValueAnimator.ofFloat(value, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { value = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Vertical space the pill occupies when not stretched. */
    private val trackTop get() = paddingTop + stretchMax
    private val trackBottom get() = height - paddingBottom - stretchMax
    private val trackHeight get() = (trackBottom - trackTop).coerceAtLeast(1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                valueAnim?.cancel()
                stretchAnim?.cancel()
                parent?.requestDisallowInterceptTouchEvent(true)
                isTracking = true
                dragging = false
                downY = event.y
                downValue = value
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    downY = event.y // start from here so the fill doesn't jump by the slop
                    downValue = value
                }
                if (dragging) {
                    val raw = downValue + (downY - event.y) / trackHeight
                    val clamped = raw.coerceIn(0f, 1f)
                    stretch = rubberBand((raw - clamped) * trackHeight)
                    if (clamped != value) {
                        value = clamped
                        onValueChange?.invoke(value)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    var v = (trackBottom - event.y) / trackHeight
                    if (v < 0.04f) v = 0f
                    v = v.coerceIn(0f, 1f)
                    onValueChange?.invoke(v)
                    setValue(v, animate = true)
                    performClick()
                }
                finishTouch()
            }
            MotionEvent.ACTION_CANCEL -> finishTouch()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun finishTouch() {
        isTracking = false
        dragging = false
        isPressed = false
        if (stretch != 0f) {
            stretchAnim = ValueAnimator.ofFloat(stretch, 0f).apply {
                duration = 350
                interpolator = OvershootInterpolator(2f)
                addUpdateListener { stretch = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        onRelease?.invoke()
    }

    /** Resists more the further you pull, never exceeding [stretchMax]. */
    private fun rubberBand(over: Float): Float {
        if (over == 0f) return 0f
        return sign(over) * stretchMax * (1f - exp(-abs(over) / (stretchMax * 3f)))
    }

    override fun onDraw(canvas: Canvas) {
        val up = if (stretch > 0) stretch else 0f
        val down = if (stretch < 0) -stretch else 0f
        val narrow = abs(stretch) * 0.35f
        pill.set(
            paddingLeft + narrow, trackTop - up,
            width - paddingRight - narrow, trackBottom + down
        )
        val radius = min(pill.width() * 0.3f, 40 * dp)
        clip.reset()
        clip.addRoundRect(pill, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(pill, trackPaint)
        val fillTop = pill.bottom - value * pill.height()
        canvas.drawRect(pill.left, fillTop, pill.right, pill.bottom, fillPaint)

        // Flashlight glyph: white over the dark part, grey over the white fill.
        val size = (34 * dp).toInt()
        val cx = pill.centerX().toInt()
        val bottom = (pill.bottom - 26 * dp).toInt()
        icon.setBounds(cx - size / 2, bottom - size, cx + size / 2, bottom)
        canvas.save()
        canvas.clipRect(pill.left, pill.top, pill.right, fillTop)
        icon.setTint(0xE6FFFFFF.toInt())
        icon.draw(canvas)
        canvas.restore()
        canvas.save()
        canvas.clipRect(pill.left, fillTop, pill.right, pill.bottom)
        icon.setTint(0xFF6E6E73.toInt())
        icon.draw(canvas)
        canvas.restore()

        canvas.restore()
    }
}
