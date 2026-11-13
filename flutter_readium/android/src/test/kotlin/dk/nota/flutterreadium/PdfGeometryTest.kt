package dk.nota.flutterreadium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class PdfGeometryTest {
    @Test
    fun `viewrect is written as left, top, width, height and read back`() {
        val box = PdfBox(left = 72f, top = 140.5f, right = 382.2f, bottom = 168.5f)

        val fragment = viewRectFragment(box)

        assertEquals("viewrect=72.00,140.50,310.20,28.00", fragment)
        val parsed = parseViewRect(listOf("page=5", fragment))
        assertNotNull(parsed)
        assertEquals(box.left, parsed!!.left, 0.01f)
        assertEquals(box.top, parsed.top, 0.01f)
        assertEquals(box.right, parsed.right, 0.01f)
        assertEquals(box.bottom, parsed.bottom, 0.01f)
    }

    @Test
    fun `viewrect from iOS is understood`() {
        val parsed = parseViewRect(listOf("page=3", "viewrect=10.00,20.00,100.00,15.00"))

        assertEquals(PdfBox(10f, 20f, 110f, 35f), parsed)
    }

    @Test
    fun `malformed viewrect is ignored`() {
        assertNull(parseViewRect(listOf("page=1")))
        assertNull(parseViewRect(listOf("viewrect=1,2,3")))
        assertNull(parseViewRect(listOf("viewrect=1,2,0,5")))
        assertNull(parseViewRect(listOf("viewrect=a,b,c,d")))
    }

    @Test
    fun `union covers every line`() {
        val union = listOf(PdfBox(10f, 10f, 50f, 20f), PdfBox(5f, 22f, 60f, 32f)).union()

        assertEquals(PdfBox(5f, 10f, 60f, 32f), union)
        assertNull(emptyList<PdfBox>().union())
    }

    @Test
    fun `the occurrence that overlaps the saved area wins`() {
        val first = listOf(PdfBox(10f, 10f, 100f, 20f))
        val second = listOf(PdfBox(10f, 300f, 100f, 310f))
        val saved = PdfBox(8f, 298f, 102f, 312f)

        assertEquals(1, bestMatchIndex(listOf(first, second), saved))
    }

    @Test
    fun `the first occurrence is used without a usable area`() {
        val matches = listOf(listOf(PdfBox(0f, 0f, 1f, 1f)), listOf(PdfBox(5f, 5f, 6f, 6f)))

        assertEquals(0, bestMatchIndex(matches, null))
        assertEquals(0, bestMatchIndex(matches, PdfBox(500f, 500f, 600f, 600f)))
        assertEquals(-1, bestMatchIndex(emptyList(), null))
    }

    @Test
    fun `selection text loses line breaks and repeated spaces`() {
        assertEquals("judíos rojizos de rostro", normalizeSelectionText("  judíos rojizos\nde   rostro \n"))
    }

    @Test
    fun `underline is a thin strip along the bottom of the line`() {
        val strip = underlineBox(PdfBox(10f, 100f, 90f, 112f))

        assertEquals(PdfBox(10f, 110.5f, 90f, 112f), strip)
        assertTrue(strip.height <= 1.5f)
    }

    @Test
    fun `a tap inside a box, with padding, is a hit`() {
        val box = PdfBox(10f, 10f, 20f, 20f)

        assertTrue(box.contains(15f, 15f))
        assertTrue(!box.contains(21f, 15f))
        assertTrue(box.contains(21f, 15f, padding = 2f))
    }
}
