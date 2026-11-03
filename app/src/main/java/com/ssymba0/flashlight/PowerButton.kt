package com.ssymba0.flashlight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Round on/off button: white with a dark glyph when on, dark grey with a white glyph when off. */
class PowerButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var isOn = false
        set(value) {
            if (field == value) return
            field = value
            contentDescription = context.getString(if (value) R.string.turn_off else R.string.turn_on)
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = context.getDrawable(R.drawable.ic_power)!!.mutate()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.turn_on)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.92f else 1f).scaleY(if (pressed) 0.92f else 1f)
            .setDuration(120).start()
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        paint.color = if (isOn) 0xFFFFFFFF.toInt() else 0xFF2C2C2E.toInt()
        canvas.drawCircle(width / 2f, height / 2f, r, paint)
        val s = (r * 0.9f).toInt()
        val cx = width / 2
        val cy = height / 2
        icon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        icon.setTint(if (isOn) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
        icon.draw(canvas)
    }
}
