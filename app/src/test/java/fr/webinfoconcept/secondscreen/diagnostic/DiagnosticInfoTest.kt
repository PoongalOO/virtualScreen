package fr.webinfoconcept.secondscreen.diagnostic

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticInfoTest {

    @Test
    fun `chooseAbis uses modern list from API 21`() {
        val result = chooseAbis(
            sdkInt = Build.VERSION_CODES.LOLLIPOP,
            modernAbis = { listOf("arm64-v8a", "armeabi-v7a") },
            legacyAbis = { listOf("armeabi-v7a") }
        )

        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), result)
    }

    @Test
    fun `chooseAbis falls back to legacy fields below API 21`() {
        val result = chooseAbis(
            sdkInt = 17,
            modernAbis = { listOf("arm64-v8a") },
            legacyAbis = { listOf("armeabi-v7a") }
        )

        assertEquals(listOf("armeabi-v7a"), result)
    }

    @Test
    fun `formatMebibytes renders one decimal`() {
        assertEquals("1.0 MiB", formatMebibytes(1024L * 1024L))
        assertEquals("0.0 MiB", formatMebibytes(0L))
        assertEquals("2.5 MiB", formatMebibytes((2.5 * 1024 * 1024).toLong()))
    }
}
