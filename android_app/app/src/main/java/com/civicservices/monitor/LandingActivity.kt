package com.civicservices.monitor

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

private val DETECTABLE_CLASSES = listOf(
    "Garbage piles",
    "Potholes",
    "Open/broken manholes",
    "Non-working streetlights",
    "Encroachments",
    "Billboards & hoardings",
    "Cattle on road",
)

class LandingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applySavedMode(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_landing)

        val startBtn = findViewById<Button>(R.id.startMonitoringButton)
        val reportsBtn = findViewById<Button>(R.id.viewReportsButton)
        val heroLogo = findViewById<android.widget.ImageView>(R.id.heroLogo)

        startBtn.setOnClickListener {
            Anim.tap(it)
            it.postDelayed({
                startActivity(Intent(this, MainActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }, 140)
        }
        reportsBtn.setOnClickListener {
            Anim.tap(it)
            it.postDelayed({
                startActivity(Intent(this, ReportsActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }, 140)
        }
        findViewById<ImageButton>(R.id.aboutButton).setOnClickListener {
            Anim.tap(it)
            it.postDelayed({ startActivity(Intent(this, AboutActivity::class.java)) }, 140)
        }

        // Staggered entrance so the screen assembles smoothly.
        Anim.entrance(heroLogo, 60)
        Anim.entrance(startBtn, 220)
        Anim.entrance(reportsBtn, 300)

        val chipGroup = findViewById<ChipGroup>(R.id.chipContainer)
        DETECTABLE_CLASSES.forEach { label ->
            val chip = Chip(this).apply {
                text = label
                isClickable = false
                isCheckable = false
                setChipBackgroundColorResource(android.R.color.transparent)
                chipStrokeWidth = 2f
                setChipStrokeColorResource(R.color.brand_orange)
                setTextColor(getColor(R.color.brand_orange))
            }
            chipGroup.addView(chip)
        }
    }
}
