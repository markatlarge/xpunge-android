package com.xpunge.example

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.xpunge.android.XpungeDetector
import com.xpunge.android.XpungeException

class MainActivity : Activity() {

    // Dev key — no bundle restriction, no expiry
    private val API_KEY = "xp1.eyJ2IjoxLCJ0aWVyIjoiZnJlZSIsInBrZyI6IiIsImV4cCI6MH0.GHucbTqHCoMglMHhk682sw"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val output = findViewById<TextView>(R.id.output)
        val sb = StringBuilder()

        val detector = XpungeDetector(this)

        // 1. Initialize
        try {
            detector.initialize(API_KEY)
            sb.appendLine("✓ Initialized  tier=${detector.tier}")
        } catch (e: XpungeException) {
            sb.appendLine("✗ Init failed: ${e.message}")
            output.text = sb
            return
        }

        // 2. Load test image from assets and analyze
        try {
            val bytes = assets.open("test.jpg").use { it.readBytes() }
            sb.appendLine("✓ Image loaded  ${bytes.size} bytes")

            val detections = detector.analyzeImage(bytes)
            sb.appendLine("✓ Analysis done  ${detections.size} detection(s)\n")

            if (detections.isEmpty()) {
                sb.appendLine("No detections.")
            } else {
                for (d in detections) {
                    sb.appendLine("${d.label}  conf=${(d.confidence * 100).toInt()}%")
                    sb.appendLine("  box=(${fmtF(d.x)}, ${fmtF(d.y)})  ${fmtF(d.width)}×${fmtF(d.height)}")
                }
            }
        } catch (e: XpungeException) {
            sb.appendLine("✗ Analysis failed: ${e.message}")
        }

        detector.dispose()
        output.text = sb
    }

    private fun fmtF(v: Double) = "%.3f".format(v)
}
