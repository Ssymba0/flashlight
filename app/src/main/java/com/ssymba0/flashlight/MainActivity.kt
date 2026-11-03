package com.ssymba0.flashlight

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowInsets

class MainActivity : Activity() {

    private lateinit var controls: FlashlightControls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        controls = FlashlightControls(
            this,
            slider = findViewById(R.id.slider),
            power = findViewById(R.id.power),
            percentText = findViewById(R.id.percent),
            infoText = findViewById(R.id.info),
            smoothText = findViewById(R.id.smooth),
            tileText = findViewById(R.id.tile_tap),
        )
    }

    override fun onStart() {
        super.onStart()
        controls.start()
    }

    override fun onStop() {
        controls.stop()
        super.onStop()
    }
}
