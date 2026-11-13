package dk.nota.flutterreadium

/**
 * The PDF preferences that androidx.pdf's `PdfView` can honour.
 *
 * `PdfView` always scrolls vertically. `layout: paginated` is emulated by the reader: a whole
 * page is shown, a horizontal swipe turns the page, and vertical drags are ignored. Spacing is
 * in dp, and `spread: always` puts two pages in a row.
 */
data class FlutterPdfPreferences(
    val layout: String? = null,
    val fit: String? = null,
    val readingProgression: String? = null,
    val pageSpacing: Double? = null,
    val spread: String? = null,
) {
    /** Values set in [other] replace these; values it leaves unset are kept. */
    fun merge(other: FlutterPdfPreferences): FlutterPdfPreferences =
        FlutterPdfPreferences(
            layout = other.layout ?: layout,
            fit = other.fit ?: fit,
            readingProgression = other.readingProgression ?: readingProgression,
            pageSpacing = other.pageSpacing ?: pageSpacing,
            spread = other.spread ?: spread,
        )

    /** Book mode: one page at a time, turned with a horizontal swipe. */
    val isPaginated: Boolean get() = layout == "paginated"

    /** Whole pages are shown, rather than fitted to the width. */
    val fitsPage: Boolean get() = isPaginated || fit == "page" || fit == "contain"

    val isRtl: Boolean get() = readingProgression == "rtl"

    companion object {
        fun fromMap(map: Map<String, Any>): FlutterPdfPreferences =
            FlutterPdfPreferences(
                layout = map["layout"] as? String,
                fit = map["fit"] as? String,
                readingProgression = map["readingProgression"] as? String,
                pageSpacing = (map["pageSpacing"] as? Number)?.toDouble(),
                spread = map["spread"] as? String,
            )
    }
}
