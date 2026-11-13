package dk.nota.flutterreadium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FlutterPdfPreferencesTest {
    @Test
    fun `preferences are read from the bridge map`() {
        val prefs =
            FlutterPdfPreferences.fromMap(
                mapOf("layout" to "paginated", "fit" to "page", "readingProgression" to "rtl", "pageSpacing" to 8, "spread" to "always"),
            )

        assertEquals("paginated", prefs.layout)
        assertEquals(8.0, prefs.pageSpacing!!, 0.0)
        assertEquals("always", prefs.spread)
        assertTrue(prefs.isPaginated)
        assertTrue(prefs.isRtl)
    }

    @Test
    fun `book mode always shows whole pages`() {
        assertTrue(FlutterPdfPreferences(layout = "paginated", fit = "width").fitsPage)
        assertTrue(FlutterPdfPreferences(layout = "scrollVertical", fit = "page").fitsPage)
        assertTrue(FlutterPdfPreferences(fit = "contain").fitsPage)
        assertFalse(FlutterPdfPreferences(layout = "scrollVertical", fit = "width").fitsPage)
        assertFalse(FlutterPdfPreferences().fitsPage)
    }

    @Test
    fun `a partial update keeps the values it does not name`() {
        val current = FlutterPdfPreferences(layout = "paginated", fit = "page", pageSpacing = 8.0)

        val merged = current.merge(FlutterPdfPreferences(layout = "scrollVertical"))

        assertEquals("scrollVertical", merged.layout)
        assertEquals("page", merged.fit)
        assertEquals(8.0, merged.pageSpacing!!, 0.0)
        assertFalse(merged.isPaginated)
    }

    @Test
    fun `missing values stay unset`() {
        val prefs = FlutterPdfPreferences.fromMap(emptyMap())

        assertNull(prefs.layout)
        assertNull(prefs.pageSpacing)
        assertFalse(prefs.isPaginated)
        assertFalse(prefs.isRtl)
    }
}
