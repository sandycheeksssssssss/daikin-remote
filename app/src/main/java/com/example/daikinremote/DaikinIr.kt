package com.example.daikinremote

import android.content.SharedPreferences
import java.util.Calendar

enum class Mode(val bits: Int, val label: String) {
    AUTO(0, "AUTO"), DRY(2, "DRY"), COOL(3, "COOL"), HEAT(4, "HEAT"), FAN(6, "FAN")
}

enum class Fan(val code: Int, val label: String) {
    AUTO(0xA, "AUTO"), QUIET(0xB, "QUIET"), L1(3, "1"), L2(4, "2"), L3(5, "3"), L4(6, "4"), L5(7, "5")
}

data class AcState(
    var power: Boolean = false,
    var mode: Mode = Mode.COOL,
    var temp: Int = 24,
    var fan: Fan = Fan.AUTO,
    var swing: Boolean = false,
    var powerchill: Boolean = false,
    var econo: Boolean = false,
    var coanda: Boolean = false,
    var goodSleep: Boolean = false,
    var onTimerH: Int = 0,   // 0 = no timer
    var offTimerH: Int = 0
) {
    fun save(p: SharedPreferences) {
        p.edit().putBoolean("power", power).putInt("mode", mode.ordinal).putInt("temp", temp)
            .putInt("fan", fan.ordinal).putBoolean("swing", swing).putBoolean("pc", powerchill)
            .putBoolean("econo", econo).putBoolean("coanda", coanda).putBoolean("sleep", goodSleep)
            .putInt("on", onTimerH).putInt("off", offTimerH).apply()
    }
    companion object {
        fun load(p: SharedPreferences) = AcState(
            p.getBoolean("power", false), Mode.values()[p.getInt("mode", Mode.COOL.ordinal)],
            p.getInt("temp", 24), Fan.values()[p.getInt("fan", 0)], p.getBoolean("swing", false),
            p.getBoolean("pc", false), p.getBoolean("econo", false), p.getBoolean("coanda", false),
            p.getBoolean("sleep", false), p.getInt("on", 0), p.getInt("off", 0)
        )
    }
}

/** Daikin ARC-family encoder: 3 frames (fixed, clock, state), 38 kHz, LSB first. */
object DaikinIr {
    private const val HDR_M = 3650
    private const val HDR_S = 1623
    private const val BIT_M = 428
    private const val ONE_S = 1280
    private const val ZERO_S = 428
    private const val GAP = 29428

    // ---- UNVERIFIED mappings for ARC484B12 (adjust here after testing) ----
    private const val COANDA_BYTE = 16;  private const val COANDA_MASK = 0x10
    private const val SLEEP_BYTE = 13;   private const val SLEEP_MASK = 0x08
    // -----------------------------------------------------------------------

    fun tempRange(m: Mode): IntRange = when (m) {
        Mode.COOL, Mode.DRY -> 18..32
        Mode.HEAT -> 10..30
        Mode.AUTO -> 18..30
        Mode.FAN -> 25..25
    }

    fun pattern(s: AcState): IntArray {
        val out = ArrayList<Int>()
        // preamble: 5 zero bits, then gap
        repeat(5) { out += BIT_M; out += ZERO_S }
        out += BIT_M; out += GAP
        frame(out, withSum(byteArrayOf(0x11, 0xDA.toByte(), 0x27, 0x00, 0xC5.toByte(), 0, 0, 0)))
        frame(out, clockFrame())
        frame(out, stateFrame(s))
        return out.toIntArray()
    }

    private fun withSum(b: ByteArray): ByteArray {
        var sum = 0
        for (i in 0 until b.size - 1) sum += b[i].toInt() and 0xFF
        b[b.size - 1] = (sum and 0xFF).toByte()
        return b
    }

    private fun clockFrame(): ByteArray {
        val c = Calendar.getInstance()
        val v = (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)) or (c.get(Calendar.DAY_OF_WEEK) shl 12)
        return withSum(byteArrayOf(0x11, 0xDA.toByte(), 0x27, 0x00, 0x42, (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), 0))
    }

    private fun stateFrame(s: AcState): ByteArray {
        val b = ByteArray(19)
        b[0] = 0x11; b[1] = 0xDA.toByte(); b[2] = 0x27
        b[5] = (0x08 or (s.mode.bits shl 4) or (if (s.power) 1 else 0) or
                (if (s.onTimerH > 0) 2 else 0) or (if (s.offTimerH > 0) 4 else 0)).toByte()
        b[6] = (if (s.mode == Mode.FAN) 0x32 else s.temp * 2).toByte()
        b[8] = ((s.fan.code shl 4) or (if (s.swing) 0xF else 0)).toByte()
        b[9] = (if (s.swing) 0x0F else 0).toByte()
        val on = if (s.onTimerH > 0) s.onTimerH * 60 else 0x600
        val off = if (s.offTimerH > 0) s.offTimerH * 60 else 0x600
        b[10] = (on and 0xFF).toByte()
        b[11] = (((on shr 8) and 0xF) or ((off and 0xF) shl 4)).toByte()
        b[12] = ((off shr 4) and 0xFF).toByte()
        b[13] = (if (s.powerchill) 0x01 else 0).toByte()
        b[15] = 0xC1.toByte()
        b[16] = (if (s.econo) 0x84 else 0x80).toByte()
        if (s.coanda) b[COANDA_BYTE] = (b[COANDA_BYTE].toInt() or COANDA_MASK).toByte()
        if (s.goodSleep) b[SLEEP_BYTE] = (b[SLEEP_BYTE].toInt() or SLEEP_MASK).toByte()
        return withSum(b)
    }

    private fun frame(out: MutableList<Int>, data: ByteArray) {
        out += HDR_M; out += HDR_S
        for (by in data) for (i in 0..7) {
            out += BIT_M
            out += if (((by.toInt() shr i) and 1) == 1) ONE_S else ZERO_S
        }
        out += BIT_M; out += GAP
    }
}
