package dk.nota.flutterreadium

import org.readium.r2.shared.publication.Locator
import java.util.Locale

/**
 * A rectangle in PDF page coordinates: points, origin at the top-left of the page.
 * Same convention as `androidx.pdf.PdfRect` and as the `viewrect` fragment on iOS.
 */
internal data class PdfBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width get() = right - left
    val height get() = bottom - top

    fun intersectionArea(other: PdfBox): Float {
        val w = minOf(right, other.right) - maxOf(left, other.left)
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        return if (w > 0f && h > 0f) w * h else 0f
    }

    fun contains(
        x: Float,
        y: Float,
        padding: Float = 0f,
    ): Boolean = x >= left - padding && x <= right + padding && y >= top - padding && y <= bottom + padding
}

private const val PAGE_PREFIX = "page="
private const val VIEWRECT_PREFIX = "viewrect="

internal fun List<PdfBox>.union(): PdfBox? {
    if (isEmpty()) return null
    return PdfBox(
        left = minOf { it.left },
        top = minOf { it.top },
        right = maxOf { it.right },
        bottom = maxOf { it.bottom },
    )
}

/** RFC 3778 `viewrect=left,top,width,height`, the format the iOS reader writes. */
internal fun viewRectFragment(box: PdfBox): String = String.format(Locale.ROOT, "viewrect=%.2f,%.2f,%.2f,%.2f", box.left, box.top, box.width, box.height)

internal fun parseViewRect(fragments: List<String>): PdfBox? {
    val raw = fragments.firstOrNull { it.startsWith(VIEWRECT_PREFIX) } ?: return null
    val values = raw.removePrefix(VIEWRECT_PREFIX).split(',').mapNotNull { it.trim().toFloatOrNull() }
    if (values.size != 4 || values[2] <= 0f || values[3] <= 0f) return null
    return PdfBox(values[0], values[1], values[0] + values[2], values[1] + values[3])
}

/** 1-based page number from a `page=N` fragment, falling back to `locations.position`. */
internal fun pdfPageNumber(locator: Locator): Int? {
    locator.locations.fragments
        .firstOrNull { it.startsWith(PAGE_PREFIX) }
        ?.removePrefix(PAGE_PREFIX)
        ?.toIntOrNull()
        ?.let { if (it >= 1) return it }
    return locator.locations.position?.takeIf { it >= 1 }
}

/**
 * Picks the occurrence of a phrase that a saved locator refers to. A phrase can occur
 * several times on a page, so the one that overlaps the saved `viewrect` most wins.
 * Without a usable area the first occurrence is used.
 */
internal fun bestMatchIndex(
    matches: List<List<PdfBox>>,
    area: PdfBox?,
): Int {
    if (matches.isEmpty()) return -1
    if (area == null) return 0
    val scores = matches.map { lines -> lines.sumOf { it.intersectionArea(area).toDouble() } }
    val best = scores.indices.maxByOrNull { scores[it] } ?: return 0
    return if (scores[best] > 0.0) best else 0
}

/** Collapses the line breaks and repeated spaces that a multi-line selection carries. */
internal fun normalizeSelectionText(text: CharSequence): String = text.toString().replace(Regex("\\s+"), " ").trim()

/** The thin strip drawn for an underline decoration, along the bottom of a text line. */
internal fun underlineBox(
    line: PdfBox,
    thickness: Float = 1.5f,
): PdfBox = PdfBox(line.left, maxOf(line.top, line.bottom - thickness), line.right, line.bottom)
