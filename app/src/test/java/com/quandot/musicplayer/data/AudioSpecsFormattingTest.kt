package com.quandot.musicplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AudioSpecsFormattingTest {

    @Test
    fun formatKhz_stripsTrailingZeroForEvenRatesAndUsesDotForOddRatesAcrossLocales() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("vi-VN"))) {
                Locale.setDefault(locale)

                assertEquals("48 kHz", AudioSpecsHelper.formatKhz(48_000))
                assertEquals("96 kHz", AudioSpecsHelper.formatKhz(96_000))
                assertEquals("192 kHz", AudioSpecsHelper.formatKhz(192_000))
                assertEquals("384 kHz", AudioSpecsHelper.formatKhz(384_000))

                assertEquals("44.1 kHz", AudioSpecsHelper.formatKhz(44_100))
                assertEquals("88.2 kHz", AudioSpecsHelper.formatKhz(88_200))
                assertEquals("176.4 kHz", AudioSpecsHelper.formatKhz(176_400))
                assertEquals("352.8 kHz", AudioSpecsHelper.formatKhz(352_800))

                assertEquals("", AudioSpecsHelper.formatKhz(0))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun formatMhz_usesDotDecimalAcrossLocales() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("vi-VN"))) {
                Locale.setDefault(locale)

                assertEquals("2.8 MHz", AudioSpecsHelper.formatMhz(2_822_400))
                assertEquals("5.6 MHz", AudioSpecsHelper.formatMhz(5_644_800))
                assertEquals("11.3 MHz", AudioSpecsHelper.formatMhz(11_289_600))
                assertEquals("22.6 MHz", AudioSpecsHelper.formatMhz(22_579_200))
                assertEquals("", AudioSpecsHelper.formatMhz(0))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun isHiRes_recognizesIntegerAndDecimalKhzFormats() {
        assertTrue(AudioSpecsHelper.isHiRes("FLAC • 24-bit/96 kHz"))
        assertTrue(AudioSpecsHelper.isHiRes("FLAC • 16-bit/192 kHz"))
        assertTrue(AudioSpecsHelper.isHiRes("WAV • 24-bit/44.1 kHz"))
        assertTrue(AudioSpecsHelper.isHiRes("WAV • 32-bit float/48 kHz"))
        assertTrue(AudioSpecsHelper.isHiRes("DSD64 • 1-bit/2.8 MHz"))
        assertFalse(AudioSpecsHelper.isHiRes("FLAC • 16-bit/44.1 kHz"))
    }
}
