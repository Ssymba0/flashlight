package com.ssymba0.flashlight

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Compact brightness slider that floats over whatever is on screen, like long-pressing
 * the flashlight in iOS Control Center. Opened by long-pressing the Quick Settings tile.
 * Tap outside the slider to close it.
 */
class PopupActivity : Activity() {

    private lateinit var controls: FlashlightControls
    private lateinit var content: View
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_popup)

        content = findViewById(R.id.content)
        findViewById<View>(R.id.root).setOnClickListener { close() }
        content.setOnClickListener { } // taps on the panel itself don't close it

        controls = FlashlightControls(
            this,
            slider = findViewById(R.id.slider),
            power = findViewById(R.id.power),
            percentText = findViewById(R.id.percent),
        )

        content.scaleX = 0.85f
        content.scaleY = 0.85f
        content.alpha = 0f
        content.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(300).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    override fun onStart() {
        super.onStart()
        controls.start()
    }

    override fun onStop() {
        controls.stop()
        super.onStop()
        // Don't linger in the background once the user leaves.
        if (!isChangingConfigurations) finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = close()

    private fun close() {
        if (closing) return
        closing = true
        content.animate().scaleX(0.9f).scaleY(0.9f).alpha(0f)
            .setDuration(160).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, android.R.anim.fade_out)
            }.start()
    }
}
