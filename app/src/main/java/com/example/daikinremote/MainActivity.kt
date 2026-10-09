package com.example.daikinremote

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.*

class MainActivity : Activity() {
    private lateinit var s: AcState
    private var ir: ConsumerIrManager? = null
    private var editing = 0      // 0 none, 1 = ON timer, 2 = OFF timer
    private var editH = 1
    private lateinit var lcd: LinearLayout
    private lateinit var tvTemp: TextView
    private lateinit var tvInfo: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = AcState.load(getSharedPreferences("ac", MODE_PRIVATE))
        ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        buildUi()
        render()
        if (ir?.hasIrEmitter() != true)
            Toast.makeText(this, "No IR blaster found on this phone", Toast.LENGTH_LONG).show()
    }

    private fun transmit() {
        try { ir?.takeIf { it.hasIrEmitter() }?.transmit(38000, DaikinIr.pattern(s)) } catch (e: Exception) {
            Toast.makeText(this, "IR send failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun btn(label: String, color: Int = 0xFFF4F1EA.toInt(), a: () -> Boolean): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF222222.toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(color); setStroke(dp(1), 0xFF999999.toInt())
        }
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (a()) transmit()
            s.save(getSharedPreferences("ac", MODE_PRIVATE))
            render()
        }
    }

    private fun row(h: Int, vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h))
        v.forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            addView(it)
        }
    }

    private fun col(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        v.forEach {
            it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { setMargins(0, dp(2), 0, dp(2)) }
            addView(it)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xFFEDE8DA.toInt()) }
        }
        root.addView(TextView(this).apply {
            text = "DAIKIN"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(0xFF222222.toInt())
        })
        tvTemp = TextView(this).apply {
            textSize = 56f; typeface = Typeface.MONOSPACE; gravity = Gravity.END
            setTextColor(0xFF1E1E1E.toInt())
        }
        tvInfo = TextView(this).apply { textSize = 12f; setTextColor(0xFF1E1E1E.toInt()) }
        lcd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFFC9CFC4.toInt()) }
            addView(tvTemp); addView(tvInfo)
        }
        root.addView(lcd, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(12), 0, dp(16))
        })

        root.addView(row(64,
            btn("POWERCHILL", 0xFFBFE8D6.toInt()) { s.powerchill = !s.powerchill; if (s.powerchill) { s.econo = false; s.goodSleep = false }; true },
            btn("ON/OFF", 0xFFF3EC9A.toInt()) { s.power = !s.power; true }))

        root.addView(row(150,
            col(btn("MODE") { cycleMode(); true },
                btn("ECONO") { s.econo = !s.econo; if (s.econo) s.powerchill = false; true }),
            col(btn("TEMP ▲") { tempStep(+1) },
                btn("TEMP ▼") { tempStep(-1) }),
            col(btn("FAN") { cycleFan(); true },
                btn("SWING") { s.swing = !s.swing; true })))

        root.addView(row(56,
            btn("COANDA") { s.coanda = !s.coanda; true },
            btn("GOOD SLEEP") { s.goodSleep = !s.goodSleep; if (s.goodSleep) s.powerchill = false; true },
            View(this)))

        root.addView(row(56,
            btn("OFF") { timerButton(2) },
            btn("CANCEL") { editing = 0; s.onTimerH = 0; s.offTimerH = 0; true },
            btn("ON") { timerButton(1) }))
        root.addView(TextView(this).apply {
            text = "TIMER"; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF222222.toInt())
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF2B2B2B.toInt()); setPadding(dp(16), dp(24), dp(16), dp(24)); addView(root)
        })
    }

    private fun cycleMode() {
        val v = Mode.values(); s.mode = v[(s.mode.ordinal + 1) % v.size]
        s.temp = s.temp.coerceIn(DaikinIr.tempRange(s.mode))
    }

    private fun cycleFan() {
        val v = Fan.values(); s.fan = v[(s.fan.ordinal + 1) % v.size]; s.powerchill = false
    }

    /** returns true if an IR signal should be sent */
    private fun tempStep(d: Int): Boolean {
        if (editing != 0) { editH = (editH + d).coerceIn(1, 12); return false }
        s.temp = (s.temp + d).coerceIn(DaikinIr.tempRange(s.mode)); return true
    }

    private fun timerButton(which: Int): Boolean {
        if (editing == which) {                       // second press confirms
            if (which == 1) s.onTimerH = editH else s.offTimerH = editH
            editing = 0; return true
        }
        editing = which
        editH = (if (which == 1) s.onTimerH else s.offTimerH).takeIf { it > 0 } ?: 1
        return false
    }

    private fun render() {
        if (editing != 0) {
            tvTemp.text = "${editH}h"
            tvInfo.text = (if (editing == 1) "TIMER ON" else "TIMER OFF") + " · use TEMP ▲▼ · press again to set"
            lcd.alpha = 1f; return
        }
        tvTemp.text = if (s.mode == Mode.FAN) "--" else "${s.temp}°C"
        val p = mutableListOf<String>()
        if (!s.power) p += "OFF"
        p += s.mode.label; p += "FAN ${s.fan.label}"
        if (s.swing) p += "SWING"
        if (s.powerchill) p += "POWERCHILL"
        if (s.econo) p += "ECONO"
        if (s.coanda) p += "COANDA"
        if (s.goodSleep) p += "SLEEP"
        if (s.onTimerH > 0) p += "ON ${s.onTimerH}h"
        if (s.offTimerH > 0) p += "OFF ${s.offTimerH}h"
        tvInfo.text = p.joinToString(" · ")
        lcd.alpha = if (s.power) 1f else 0.45f
    }
}
