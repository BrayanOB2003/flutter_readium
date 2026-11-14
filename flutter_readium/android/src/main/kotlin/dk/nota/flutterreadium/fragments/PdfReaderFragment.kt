package dk.nota.flutterreadium.fragments

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Size
import android.util.SparseArray
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.pdf.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.content.ExternalLink
import androidx.pdf.selection.ContextMenuComponent
import androidx.pdf.selection.PdfSelectionMenuKeys
import androidx.pdf.selection.SelectionMenuComponent
import androidx.pdf.selection.model.TextSelection
import androidx.pdf.view.PdfView
import dk.nota.flutterreadium.PdfBox
import dk.nota.flutterreadium.PdfDocumentFile
import dk.nota.flutterreadium.PluginLog
import dk.nota.flutterreadium.R
import dk.nota.flutterreadium.ReadiumReader
import dk.nota.flutterreadium.FlutterPdfPreferences
import dk.nota.flutterreadium.bestMatchIndex
import dk.nota.flutterreadium.events.ReadiumReaderStatus
import dk.nota.flutterreadium.models.PdfReaderViewModel
import dk.nota.flutterreadium.normalizeSelectionText
import dk.nota.flutterreadium.parseViewRect
import dk.nota.flutterreadium.pdfPageNumber
import dk.nota.flutterreadium.underlineBox
import dk.nota.flutterreadium.union
import dk.nota.flutterreadium.viewRectFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "PdfReaderFragment"
private const val SELECTION_SETTLE_MS = 250L
private const val SELECTION_MENU_DELAY_MS = 350L
private const val DECORATION_HIT_PADDING = 2f
private const val OPAQUE_HIGHLIGHT_ALPHA = 0x66
private const val HANDLE_TOUCH_INSET_PX = 2f
private const val ZOOM_EPSILON = 0.01f
private const val ZOOMED_IN_FACTOR = 1.05f
private const val PAGE_SWIPE_MIN_DP = 48f
private const val PAGE_SWIPE_MIN_FRACTION = 0.12f
private const val BAND_STEP_PX = 2
private const val PAGE_TURN_MS = 200L
private const val PAGE_SETTLE_MS = 220L
private const val HANDLE_RADIUS_DP = 9f
private const val HANDLE_COLOR = 0xFF1A73E8.toInt()
private const val DEFAULT_HIGHLIGHT_COLOR = 0x66FFEB3B
private val SHARE_KEY = Any()

private var instanceNo = 0

/**
 * Hosts androidx.pdf's [PdfView], which draws the pages, selects text and paints
 * highlights. Readium's own PDF navigator can do none of the three on Android.
 *
 * Locators use the same `page=` and `viewrect=` fragments as the iOS reader.
 */
@ExperimentalCoroutinesApi
class PdfReaderFragment : Fragment() {
    interface Listener {
        fun onPageChanged(
            pageIndex: Int,
            totalPages: Int,
            locator: Locator,
        )

        fun onExternalLinkActivated(url: AbsoluteUrl)
    }

    var vm: PdfReaderViewModel? = null
    var listener: Listener? = null

    val started = MutableStateFlow(false)

    private val locatorFlow = MutableStateFlow<Locator?>(null)
    val currentLocator: StateFlow<Locator?> get() = locatorFlow

    private val instance = ++instanceNo
    private val handler = Handler(Looper.getMainLooper())

    private var pdfView: PdfView? = null
    private var handlesView: HandlesView? = null
    private var leftNeighbour: ImageView? = null
    private var rightNeighbour: ImageView? = null
    private val neighbourBitmaps = HashMap<Int, Bitmap>()
    private var turningPage = false
    private var modeAnchor = 0
    private var document: PdfDocument? = null
    private var resourceLink: Link? = null
    private var pageCount = 0
    private var currentPage = 0
    private var pendingPage: Int? = null
    private var preferences: FlutterPdfPreferences? = null

    private val decorationsByGroup = LinkedHashMap<String, List<Decoration>>()
    private var hitAreas: List<HitArea> = emptyList()
    private var paintJob: Job? = null

    private val channel get() = ReadiumReader.currentReaderWidget?.channel

    // True while a finger is down, and once the long press has made a selection under it.
    private var fingerDown = false
    private var selectionStartedUnderFinger = false
    private var injectingTouch = false
    private var keepingPageInView = false
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val pendingTap = arrayOfNulls<Runnable>(1)
    private var hadSelectionAtDown = false
    private var bridgedThisGesture = false

    // PDF points of the first page, used to fit whole pages to the view.
    private var pageWidthPt = 0f
    private var pageHeightPt = 0f
    private var fitPageZoom = 0f
    private var fitPageApplied = false
    private var defaultMinZoom = -1f
    private var appliedPaged: Boolean? = null
    private var defaultVerticalSpacing = -1
    private var defaultHorizontalSpacing = -1

    private class HitArea(
        val id: String,
        val group: String,
        val locator: Locator,
        val page: Int,
        val boxes: List<PdfBox>,
    )

    // region Navigation

    fun go(
        locator: Locator,
        @Suppress("UNUSED_PARAMETER") animated: Boolean,
    ): Boolean {
        val page =
            pdfPageNumber(locator)
                ?: locator.locations.progression?.let { progression ->
                    if (pageCount > 0) (progression.coerceIn(0.0, 1.0) * pageCount).toInt() + 1 else null
                }
                ?: return false
        return goToPage(page)
    }

    fun goForward(@Suppress("UNUSED_PARAMETER") animated: Boolean): Boolean = goToPage(max(currentPage, 1) + 1, onlyIfInRange = true)

    fun goBackward(@Suppress("UNUSED_PARAMETER") animated: Boolean): Boolean = goToPage(max(currentPage, 1) - 1, onlyIfInRange = true)

    private fun goToPage(
        page: Int,
        onlyIfInRange: Boolean = false,
    ): Boolean {
        val view = pdfView
        if (view == null || document == null || pageCount == 0) {
            // Before the document is open the target is kept and used once it is.
            pendingPage = page
            return true
        }
        if (onlyIfInRange && page !in 1..pageCount) return false
        view.scrollToPage(page.coerceIn(1, pageCount) - 1)
        return true
    }

    // endregion

    // region Lifecycle

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = PdfView(requireContext())
        // The page counter that follows the scroll thumb gets in the way of the text.
        view.fastScrollVisibility = PdfView.FastScrollVisibility.ALWAYS_HIDE
        view.layoutParams =
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        catchRenderFailures(view)
        pdfView = view
        // The neighbours show the pages beside the current one while a swipe drags it.
        val left = neighbourView(requireContext())
        val right = neighbourView(requireContext())
        val handles = HandlesView(requireContext(), view)
        leftNeighbour = left
        rightNeighbour = right
        handlesView = handles
        val host = FrameLayout(requireContext())
        host.addView(view)
        host.addView(left)
        host.addView(right)
        host.addView(handles, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return host
    }

    private fun neighbourView(context: Context): ImageView =
        ImageView(context).apply {
            visibility = View.INVISIBLE
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.CENTER)
        }

    /**
     * androidx.pdf 1.0.0-beta01 renders pages in a scope with no exception handler, so one failed
     * bitmap request kills the app. Reopening a PDF can leave a stale request asking for a
     * 0-pixel bitmap ("width and height must be > 0") while the old one is being cancelled.
     *
     * The scope is `internal`, so it is swapped by its JVM name for the same scope plus a handler
     * that logs the failure. The page is requested again on the next viewport change.
     * consumer-rules.pro keeps both accessors through R8. Must run before the document is set,
     * since the page manager captures the scope then.
     */
    private fun catchRenderFailures(view: PdfView) {
        try {
            val type = PdfView::class.java
            val scope = type.getMethod("getBackgroundScope\$pdf_viewer").invoke(view) as CoroutineScope
            val handler =
                CoroutineExceptionHandler { _, error ->
                    PluginLog.w(TAG, "::render - $instance - a page bitmap failed: $error")
                }
            type
                .getMethod("setBackgroundScope\$pdf_viewer", CoroutineScope::class.java)
                .invoke(view, CoroutineScope(scope.coroutineContext + handler))
        } catch (error: Exception) {
            PluginLog.w(TAG, "::catchRenderFailures - $instance - not installed: $error")
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val pdf = pdfView ?: return
        PluginLog.d(TAG, "::onViewCreated - $instance")

        pdf.addOnViewportChangedListener(viewportListener)
        pdf.addOnSelectionChangedListener(selectionListener)
        pdf.addSelectionMenuItemPreparer(menuPreparer)
        pdf.setLinkClickListener(linkListener)
        installTapListener(pdf)
        pdf.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyPreferences()
        }

        pendingPage = vm?.locator?.let { initial -> pdfPageNumber(initial) }
        preferences = vm?.preferences
        applyPreferences()

        viewLifecycleOwner.lifecycleScope.launch { openDocument(pdf) }
    }

    override fun onDestroyView() {
        PluginLog.d(TAG, "::onDestroyView - $instance")
        handler.removeCallbacksAndMessages(null)
        paintJob?.cancel()
        pdfView?.let { view ->
            view.removeOnViewportChangedListener(viewportListener)
            view.removeOnSelectionChangedListener(selectionListener)
            view.removeSelectionMenuItemPreparer(menuPreparer)
            view.setLinkClickListener(null)
            view.setOnTouchListener(null)
            view.pdfDocument = null
        }
        try {
            document?.close()
        } catch (error: java.io.IOException) {
            PluginLog.w(TAG, "::onDestroyView - closing the document failed: $error")
        }
        document = null
        pdfView = null
        handlesView = null
        leftNeighbour = null
        rightNeighbour = null
        neighbourBitmaps.clear()
        turningPage = false
        started.value = false
        super.onDestroyView()
    }

    private suspend fun openDocument(view: PdfView) {
        val publication =
            ReadiumReader.currentPublication ?: run {
                PluginLog.w(TAG, "::openDocument - $instance - the publication is missing.")
                return
            }
        val link = publication.readingOrder.firstOrNull() ?: return
        resourceLink = link

        var descriptor: ParcelFileDescriptor? = null
        try {
            val file =
                PdfDocumentFile.materialize(requireContext(), publication)
                    ?: throw java.io.IOException("The PDF could not be copied for reading.")
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val opened =
                SandboxedPdfLoader(requireContext().applicationContext)
                    .openDocument(Uri.fromFile(file), descriptor)
            // The document owns the descriptor from here on.
            descriptor = null
            if (pdfView !== view) {
                opened.close()
                return
            }
            document = opened
            pageCount = opened.pageCount
            view.pdfDocument = opened
            PluginLog.i(TAG, "::openDocument - $instance - opened $pageCount pages")
            val firstPage = opened.getPageInfo(0)
            pageWidthPt = firstPage.width.toFloat()
            pageHeightPt = firstPage.height.toFloat()
            applyFit()
            pendingPage?.let { goToPage(it) }
            pendingPage = null
            repaintDecorations()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PluginLog.e(TAG, "::openDocument - $instance - failed: $error")
            ReadiumReader.emitReaderStatusUpdate(ReadiumReaderStatus.Error)
        } finally {
            descriptor?.close()
        }
    }

    // endregion

    // region Preferences

    fun updatePreferences(newPreferences: FlutterPdfPreferences) {
        // A call only sets what it names, as on iOS.
        preferences = preferences?.merge(newPreferences) ?: newPreferences
        applyPreferences()
    }

    private fun applyPreferences() {
        val view = pdfView ?: return
        val prefs = preferences ?: return
        if (defaultVerticalSpacing < 0) {
            defaultVerticalSpacing = view.verticalPageSpacing
            defaultHorizontalSpacing = view.horizontalPageSpacing
        }
        val paged = prefs.isPaginated
        val modeChanged = appliedPaged != null && appliedPaged != paged
        appliedPaged = paged
        // Changing the gap moves every page, and PdfView reports pages from the old scroll position
        // meanwhile, so the page the reader was on is kept aside until the layout settles.
        val anchor = if (modeChanged && currentPage > 0) currentPage else 0
        if (anchor > 0) modeAnchor = anchor
        if (paged) {
            // Pages are stacked vertically, so a gap as tall as the view keeps the neighbours off screen.
            if (view.height > 0) view.verticalPageSpacing = view.height
        } else {
            // Leaving book mode must give the gap back, or the next pages are scrolled far out of view.
            val pixels = prefs.pageSpacing?.let { (it.coerceAtLeast(0.0) * resources.displayMetrics.density).toInt() }
            view.verticalPageSpacing = pixels ?: defaultVerticalSpacing
            view.horizontalPageSpacing = pixels ?: defaultHorizontalSpacing
        }
        prefs.spread?.let { view.pagesPerRow = if (it == "always") 2 else 1 }
        neighbourBitmaps.clear()
        applyFit()
        prefetchNeighbours()
        if (anchor > 0) {
            view.scrollToPage(anchor - 1)
            // The new gap is laid out on the next pass, so the page is put back once more after it.
            view.post {
                pdfView?.scrollToPage(anchor - 1)
                pdfView?.invalidate()
                handler.postDelayed({ modeAnchor = 0 }, PAGE_SETTLE_MS)
            }
        }
    }

    /** Fits whole pages to the view for `fit: page` and book mode, and to the width otherwise. */
    private fun applyFit() {
        val view = pdfView ?: return
        val prefs = preferences
        if (pageWidthPt <= 0f || pageHeightPt <= 0f || view.width <= 0 || view.height <= 0) return
        if (defaultMinZoom < 0f) defaultMinZoom = view.minZoom
        if (prefs == null || !prefs.fitsPage) {
            view.setPadding(0, 0, 0, 0)
            view.minZoom = defaultMinZoom
            fitPageZoom = 0f
            if (fitPageApplied) {
                fitPageApplied = false
                view.zoom = (view.width / pageWidthPt).coerceIn(defaultMinZoom, view.maxZoom)
                if (currentPage > 0) view.scrollToPage(currentPage - 1)
            }
            return
        }
        val zoom = min(view.width / pageWidthPt, view.height / pageHeightPt)
        fitPageZoom = zoom.coerceIn(defaultMinZoom, view.maxZoom)
        // Zooming out past the fitted page would only shrink it and show the background around it.
        view.minZoom = fitPageZoom
        fitPageApplied = true
        // In book mode the page is centred by padding, so PdfView sees a viewport as tall as the page.
        applyBand(view, fitPageZoom)
        if (abs(view.zoom - fitPageZoom) > ZOOM_EPSILON) {
            view.zoom = fitPageZoom
            // Zooming moves the content, so the current page is brought back to the top.
            if (currentPage > 0) view.scrollToPage(currentPage - 1)
        }
    }

    /**
     * In book mode the page is centred with padding that shrinks as the page grows, so it is zero
     * once the page fills the view and zooming in never shows an edge or makes the page jump.
     */
    private fun applyBand(
        view: PdfView,
        zoom: Float,
    ) {
        val fits = preferences?.let { it.isPaginated && it.fitsPage } == true
        val band = if (fits && fitPageZoom > 0f) ((view.height - pageHeightPt * zoom) / 2f).toInt().coerceAtLeast(0) else 0
        if (abs(view.paddingTop - band) < BAND_STEP_PX) return
        view.setPadding(0, band, 0, band)
    }

    /**
     * Pages are a view's height apart in book mode, so a zoomed page could be scrolled into the gap.
     * The page is held inside the view while zoomed, and snapped back as soon as the zoom returns to the fit.
     */
    private fun keepPageInView(
        view: PdfView,
        pageLocations: SparseArray<RectF>,
        zoom: Float,
    ) {
        if (keepingPageInView || modeAnchor > 0 || currentPage < 1 || preferences?.let { it.isPaginated && it.fitsPage } != true) return
        val page = pageLocations.get(currentPage - 1) ?: return
        if (fitPageZoom <= 0f) return
        if (zoom <= fitPageZoom * ZOOMED_IN_FACTOR) {
            if (!fingerDown && abs(page.top - view.paddingTop) > BAND_STEP_PX) {
                keepingPageInView = true
                try {
                    view.scrollToPage(currentPage - 1)
                } finally {
                    keepingPageInView = false
                }
            }
            return
        }
        if (page.height() <= view.height) return
        val shift =
            when {
                page.top > 0f -> page.top
                page.bottom < view.height -> page.bottom - view.height
                else -> return
            }
        keepingPageInView = true
        try {
            view.scrollBy(0, shift.roundToInt())
        } finally {
            keepingPageInView = false
        }
    }

    private fun isZoomedIn(view: PdfView): Boolean = fitPageZoom > 0f && view.zoom > fitPageZoom * ZOOMED_IN_FACTOR

    // endregion

    // region Position

    private val viewportListener =
        object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(
                firstVisiblePage: Int,
                visiblePagesCount: Int,
                pageLocations: SparseArray<RectF>,
                zoomLevel: Float,
            ) {
                val view = pdfView ?: return
                publishPage(mostVisiblePage(view, firstVisiblePage, pageLocations) + 1)
                applyBand(view, zoomLevel)
                keepPageInView(view, pageLocations, zoomLevel)
                handlesView?.invalidate()
            }
        }

    private fun mostVisiblePage(
        view: PdfView,
        fallback: Int,
        pageLocations: SparseArray<RectF>,
    ): Int {
        var best = fallback
        var bestArea = -1f
        for (index in 0 until pageLocations.size()) {
            val rect = pageLocations.valueAt(index)
            val width = min(rect.right, view.width.toFloat()) - max(rect.left, 0f)
            val height = min(rect.bottom, view.height.toFloat()) - max(rect.top, 0f)
            val area = if (width > 0f && height > 0f) width * height else 0f
            if (area > bestArea) {
                bestArea = area
                best = pageLocations.keyAt(index)
            }
        }
        return best
    }

    private fun publishPage(page: Int) {
        if (page < 1 || page == currentPage || modeAnchor > 0) return
        currentPage = page
        prefetchNeighbours()
        val locator = locatorForPage(page) ?: return
        locatorFlow.value = locator
        started.value = true
        listener?.onPageChanged(page, pageCount, locator)
    }

    private fun locatorForPage(page: Int): Locator? {
        val link = resourceLink ?: return null
        val progression = (page - 1).toDouble() / max(pageCount, 1)
        return Locator(
            href = link.url(),
            mediaType = MediaType.PDF,
            locations =
                Locator.Locations(
                    fragments = listOf("page=$page"),
                    progression = progression,
                    totalProgression = progression,
                    position = page,
                ),
        )
    }

    // endregion

    // region Selection

    private val selectionListener =
        object : PdfView.OnSelectionChangedListener {
            override fun onSelectionChanged(newSelection: androidx.pdf.selection.Selection?) {
                handlesView?.invalidate()
                handler.removeCallbacks(reportSelection)
                if (newSelection is TextSelection) {
                    if (fingerDown && !hadSelectionAtDown && !bridgedThisGesture) selectionStartedUnderFinger = true
                    handler.postDelayed(reportSelection, SELECTION_SETTLE_MS)
                }
            }
        }

    // A handle drag changes the selection many times; only the settled one is reported.
    private val reportSelection =
        Runnable {
            val selection = pdfView?.currentSelection as? TextSelection ?: return@Runnable
            val locator = selectionLocator(selection) ?: return@Runnable
            channel?.onTextSelected(locator, locator.text.highlight)
        }

    private fun selectionLocator(selection: TextSelection): Locator? {
        val text = normalizeSelectionText(selection.text)
        if (text.isEmpty()) return null
        val pageIndex = selection.bounds.firstOrNull()?.pageNum ?: return null
        // A selection that crosses pages is located on its first page.
        val area =
            selection.bounds
                .filter { it.pageNum == pageIndex }
                .map { PdfBox(it.left, it.top, it.right, it.bottom) }
                .union() ?: return null
        val base = locatorForPage(pageIndex + 1) ?: return null
        return base.copy(
            locations = base.locations.copy(fragments = listOf("page=${pageIndex + 1}", viewRectFragment(area))),
            text = Locator.Text(highlight = text),
        )
    }

    private val menuPreparer =
        object : PdfView.SelectionMenuItemPreparer {
            override fun onPrepareSelectionMenuItems(components: MutableList<ContextMenuComponent>) {
                val allowed = ReadiumReader.allowedDefaultActions
                fun permits(name: String) = allowed == null || name in allowed

                components.removeAll { component ->
                    when (component.key) {
                        PdfSelectionMenuKeys.CopyKey -> !permits("copy")
                        PdfSelectionMenuKeys.SelectAllKey -> !permits("selectAll")
                        PdfSelectionMenuKeys.SmartActionKey -> allowed != null
                        else -> false
                    }
                }
                if (permits("share")) {
                    components.add(
                        SelectionMenuComponent(SHARE_KEY, getString(R.string.selection_share), null) {
                            close()
                            shareSelection()
                        },
                    )
                }
                ReadiumReader.selectionActions.forEach { action ->
                    components.add(
                        SelectionMenuComponent(action.id, action.title, null) {
                            close()
                            runSelectionAction(action.id)
                        },
                    )
                }
            }
        }

    private fun shareSelection() {
        val text = (pdfView?.currentSelection as? TextSelection)?.text?.toString() ?: return
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        startActivity(Intent.createChooser(send, null))
    }

    private fun runSelectionAction(actionId: String) {
        val view = pdfView ?: return
        val selection = view.currentSelection as? TextSelection ?: return
        val locator = selectionLocator(selection) ?: return
        channel?.onSelectionAction(actionId, locator, locator.text.highlight)
        if (actionId == "save") view.clearCurrentSelection()
    }

    private val linkListener =
        object : PdfView.LinkClickListener {
            override fun onLinkClicked(externalLink: ExternalLink): Boolean {
                val uri = externalLink.uri
                if (uri.scheme?.lowercase() !in listOf("http", "https")) return true
                AbsoluteUrl(uri.toString())?.let { listener?.onExternalLinkActivated(it) }
                return true
            }
        }

    // endregion

    // region Decorations

    fun applyDecorations(
        decorations: List<Decoration>,
        group: String,
    ) {
        if (decorations.isEmpty()) decorationsByGroup.remove(group) else decorationsByGroup[group] = decorations
        repaintDecorations()
    }

    private fun repaintDecorations() {
        val view = pdfView ?: return
        val doc = document ?: return
        val snapshot = decorationsByGroup.map { (group, decorations) -> group to decorations }
        paintJob?.cancel()
        paintJob =
            viewLifecycleOwner.lifecycleScope.launch {
                val highlights = mutableListOf<Highlight>()
                val areas = mutableListOf<HitArea>()
                for ((group, decorations) in snapshot) {
                    for (decoration in decorations) {
                        val (page, lines) = locateLines(doc, decoration.locator) ?: continue
                        val (tint, underline) = decorationPaint(decoration) ?: continue
                        val painted = if (underline) lines.map(::underlineBox) else lines
                        painted.forEach { box ->
                            highlights.add(Highlight(PdfRect(page, box.left, box.top, box.right, box.bottom), tint))
                        }
                        areas.add(HitArea(decoration.id, group, decoration.locator, page, lines))
                    }
                }
                try {
                    view.setHighlights(highlights)
                    hitAreas = areas
                } catch (error: IllegalStateException) {
                    PluginLog.w(TAG, "::repaintDecorations - $instance - the document is not ready: $error")
                }
            }
    }

    /**
     * Finds the lines a saved locator refers to: the page's text first, choosing the
     * occurrence that overlaps the saved `viewrect`, and the `viewrect` itself otherwise.
     */
    private suspend fun locateLines(
        doc: PdfDocument,
        locator: Locator,
    ): Pair<Int, List<PdfBox>>? {
        val page = (pdfPageNumber(locator) ?: return null) - 1
        if (page !in 0 until doc.pageCount) return null
        val area = parseViewRect(locator.locations.fragments)
        val text = locator.text.highlight?.let(::normalizeSelectionText).orEmpty()
        if (text.isNotEmpty()) {
            val matches =
                doc
                    .searchDocument(text, page..page)[page]
                    .orEmpty()
                    .map { match -> match.bounds.map { PdfBox(it.left, it.top, it.right, it.bottom) } }
                    .filter { it.isNotEmpty() }
            val index = bestMatchIndex(matches, area)
            if (index >= 0) return page to matches[index]
        }
        PluginLog.d(TAG, "::locateLines - text not found on page ${page + 1}; using the saved area")
        return area?.let { page to listOf(it) }
    }

    private fun decorationPaint(decoration: Decoration): Pair<Int, Boolean>? {
        val (tint, underline) =
            when (val style = decoration.style) {
                is Decoration.Style.Highlight -> style.tint to false
                is Decoration.Style.Underline -> style.tint to true
                else -> return null
            }
        val color =
            when {
                Color.alpha(tint) == 0 -> DEFAULT_HIGHLIGHT_COLOR
                // The highlight is drawn over the page, so an opaque colour would hide the text.
                Color.alpha(tint) == 0xFF && !underline -> (tint and 0x00FFFFFF) or (OPAQUE_HIGHLIGHT_ALPHA shl 24)
                else -> tint
            }
        return color to underline
    }

    // endregion

    // region Taps

    /** A tap is reported when the finger lifts, so the chrome answers as fast as in a book. */
    private fun installTapListener(view: PdfView) {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        val maximumHold = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var downRawX = 0f
        var downAt = 0L
        var moved = false
        var hadSelection = false
        var swipeDecided = false
        var verticalLocked = false
        var horizontalSwipe = false
        var secondTap = false
        val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        view.setOnTouchListener { touched, event ->
            if (injectingTouch) return@setOnTouchListener false
            val pdf = touched as PdfView
            val paged = preferences?.isPaginated == true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downRawX = event.rawX
                    downAt = event.eventTime
                    moved = false
                    fingerDown = true
                    selectionStartedUnderFinger = false
                    bridgedThisGesture = false
                    swipeDecided = false
                    verticalLocked = false
                    horizontalSwipe = false
                    // The second touch of a double tap zooms: it must neither turn the page nor be a tap.
                    secondTap = event.eventTime - lastTapAt <= doubleTapTimeout && hypot(event.x - lastTapX, event.y - lastTapY) <= slop * 6
                    if (secondTap) {
                        swipeDecided = true
                        pendingTap[0]?.let { handler.removeCallbacks(it) }
                        pendingTap[0] = null
                    }
                    // A tap that dismisses a selection is not a tap for the chrome.
                    hadSelection = pdf.currentSelection != null
                    hadSelectionAtDown = hadSelection
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    moved = true
                    swipeDecided = true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.x - downX, event.y - downY) > slop) moved = true
                    if (moved && selectionStartedUnderFinger) {
                        selectionStartedUnderFinger = false
                        bridgedThisGesture = true
                        extendSelectionFromFinger(pdf)
                    }
                    // In book mode a vertical drag does not scroll: the gesture is cancelled for PdfView.
                    if (paged && moved && !swipeDecided && !hadSelection && !bridgedThisGesture && !isZoomedIn(pdf)) {
                        swipeDecided = true
                        horizontalSwipe = abs(event.x - downX) >= abs(event.y - downY)
                        // The whole gesture is ours: PdfView would scroll the page along the swipe's vertical drift.
                        verticalLocked = true
                        cancelForPdfView(pdf, event)
                    }
                    if (verticalLocked) {
                        if (horizontalSwipe) dragPage(pdf, event.rawX - downRawX)
                        return@setOnTouchListener true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    fingerDown = false
                    selectionStartedUnderFinger = false
                    if (bridgedThisGesture) endBridgedGesture(pdf)
                    scheduleSelectionMenu()
                    if (verticalLocked) {
                        if (horizontalSwipe) finishPageDrag(pdf, event.rawX - downRawX) else if (currentPage > 0) pdf.scrollToPage(currentPage - 1)
                        return@setOnTouchListener true
                    }
                    if (secondTap) {
                        lastTapAt = 0L
                    } else if (!moved && !hadSelection && event.eventTime - downAt <= maximumHold) {
                        // Held back for a double tap, which zooms instead of reaching the chrome.
                        lastTapAt = event.eventTime
                        lastTapX = event.x
                        lastTapY = event.y
                        val x = event.x
                        val y = event.y
                        val tap = Runnable { pdfView?.let { onTap(it, x, y) } }
                        pendingTap[0] = tap
                        handler.postDelayed(tap, doubleTapTimeout)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    fingerDown = false
                    selectionStartedUnderFinger = false
                    if (bridgedThisGesture) endBridgedGesture(pdf)
                    scheduleSelectionMenu()
                    if (verticalLocked) {
                        if (horizontalSwipe) finishPageDrag(pdf, 0f)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    /**
     * PdfView closes a gesture only when the last touch event reaches its gesture tracker, but a
     * handle drag consumes that event. The injected drag therefore leaves the long press that
     * started it open, PdfView keeps hiding its menu, and the next scroll is what finally ends it.
     * A cancel, sent once the real event has been handled, closes the gesture.
     */
    private fun endBridgedGesture(view: PdfView) {
        handler.post {
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            injectingTouch = true
            try {
                view.dispatchTouchEvent(cancel)
            } finally {
                injectingTouch = false
                cancel.recycle()
            }
        }
    }

    /**
     * PdfView hides its menu while a handle moves and shows it again only after the next scroll,
     * zoom or layout, so a drag that ends while the selection is still being computed leaves no menu.
     * A layout pass once the finger is up makes it decide again.
     */
    private fun scheduleSelectionMenu() {
        handler.removeCallbacks(showSelectionMenu)
        handler.postDelayed(showSelectionMenu, SELECTION_MENU_DELAY_MS)
    }

    private val showSelectionMenu =
        Runnable {
            val view = pdfView ?: return@Runnable
            if (!fingerDown && view.currentSelection != null) view.requestLayout()
        }

    private fun cancelForPdfView(
        view: PdfView,
        event: MotionEvent,
    ) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        injectingTouch = true
        try {
            view.dispatchTouchEvent(cancel)
        } finally {
            injectingTouch = false
            cancel.recycle()
        }
    }

    /** Whether the page that lies to the left of the current one is the previous page. */
    private fun leftIsPrevious(): Boolean = preferences?.isRtl != true

    private fun neighbourPage(onLeft: Boolean): Int = max(currentPage, 1) + if (onLeft == leftIsPrevious()) -1 else 1

    /** The page moves with the finger, with its neighbours beside it, as in an EPUB. */
    private fun dragPage(
        view: PdfView,
        dx: Float,
    ) {
        if (turningPage || view.width <= 0) return
        val onLeft = dx > 0
        val exists = neighbourPage(onLeft) in 1..max(pageCount, 1)
        // Past the first or last page the content resists the finger.
        val shift = if (exists) dx else dx * 0.25f
        view.translationX = shift
        showNeighbour(if (onLeft) leftNeighbour else rightNeighbour, neighbourPage(onLeft), view)
        leftNeighbour?.translationX = shift - view.width
        rightNeighbour?.translationX = shift + view.width
    }

    private fun showNeighbour(
        image: ImageView?,
        page: Int,
        view: PdfView,
    ) {
        image ?: return
        val bitmap = neighbourBitmaps[page]
        if (bitmap == null || page !in 1..pageCount) {
            image.visibility = View.INVISIBLE
            return
        }
        if (image.tag != page) {
            image.tag = page
            image.setImageBitmap(bitmap)
            image.layoutParams = FrameLayout.LayoutParams(bitmap.width, bitmap.height, Gravity.CENTER)
        }
        image.visibility = View.VISIBLE
    }

    /** A drag past a fraction of the width, or far enough, turns the page: left for the next one, or right in a right-to-left book. */
    private fun finishPageDrag(
        view: PdfView,
        dx: Float,
    ) {
        val minimum = max(PAGE_SWIPE_MIN_DP * resources.displayMetrics.density, view.width * PAGE_SWIPE_MIN_FRACTION)
        val onLeft = dx > 0
        val target = neighbourPage(onLeft)
        val turn = abs(dx) >= minimum && target in 1..pageCount && neighbourBitmaps[target] != null
        if (abs(dx) >= minimum && target in 1..pageCount && !turn) {
            // The neighbour is not drawn yet: the page turns without the slide.
            resetPageDrag()
            goToPage(target)
            return
        }
        turningPage = true
        val width = view.width.toFloat()
        val end = if (turn) (if (onLeft) width else -width) else 0f
        view.animate().translationX(end).setDuration(PAGE_TURN_MS).start()
        val shownLeft = leftNeighbour
        val shownRight = rightNeighbour
        shownLeft?.animate()?.translationX(end - width)?.setDuration(PAGE_TURN_MS)?.start()
        shownRight
            ?.animate()
            ?.translationX(end + width)
            ?.setDuration(PAGE_TURN_MS)
            ?.setListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        shownRight.animate().setListener(null)
                        if (!turn) {
                            resetPageDrag()
                            return
                        }
                        // The slide ends with the neighbour in place, so the real page can load under it.
                        goToPage(target)
                        handler.postDelayed({ resetPageDrag() }, PAGE_SETTLE_MS)
                    }
                },
            )?.start()
    }

    private fun resetPageDrag() {
        pdfView?.apply {
            animate().cancel()
            translationX = 0f
        }
        for (neighbour in listOfNotNull(leftNeighbour, rightNeighbour)) {
            neighbour.animate().cancel()
            neighbour.visibility = View.INVISIBLE
            neighbour.tag = null
        }
        turningPage = false
    }

    /** Draws the pages beside the current one in advance, so a swipe can show them at once. */
    private fun prefetchNeighbours() {
        if (preferences?.isPaginated != true || document == null || fitPageZoom <= 0f) return
        val keep = setOf(currentPage - 1, currentPage + 1)
        neighbourBitmaps.keys.retainAll(keep)
        for (page in keep) {
            if (page !in 1..pageCount || neighbourBitmaps.containsKey(page)) continue
            val source = document ?: return
            val width = (pageWidthPt * fitPageZoom).roundToInt()
            val height = (pageHeightPt * fitPageZoom).roundToInt()
            if (width <= 0 || height <= 0) return
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val pageSource = source.getPageBitmapSource(page - 1)
                    try {
                        val bitmap = pageSource.getBitmap(Size(width, height))
                        if (pdfView != null && page in setOf(currentPage - 1, currentPage + 1)) neighbourBitmaps[page] = bitmap
                    } finally {
                        pageSource.close()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    PluginLog.w(TAG, "::prefetchNeighbours - $instance - page $page failed: $error")
                }
            }
        }
    }

    /** Selection handles drawn over the page, since the ones PdfView draws are not visible. */
    private class HandlesView(
        context: Context,
        private val pdf: PdfView,
    ) : View(context) {
        private val radius = HANDLE_RADIUS_DP * resources.displayMetrics.density
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = HANDLE_COLOR
                style = Paint.Style.FILL
            }
        private val shape = Path()

        override fun onDraw(canvas: Canvas) {
            val selection = pdf.currentSelection as? TextSelection ?: return
            val first = selection.bounds.firstOrNull() ?: return
            val last = selection.bounds.lastOrNull() ?: return
            pdf.pdfToViewPoint(PdfPoint(first.pageNum, first.left, first.bottom))?.let { drawHandle(canvas, it.x, it.y, -1f) }
            pdf.pdfToViewPoint(PdfPoint(last.pageNum, last.right, last.bottom))?.let { drawHandle(canvas, it.x, it.y, 1f) }
        }

        // The point of the drop sits on the selection edge and the circle hangs below it.
        private fun drawHandle(
            canvas: Canvas,
            x: Float,
            y: Float,
            side: Float,
        ) {
            shape.rewind()
            shape.addCircle(x + side * radius, y + radius, radius, Path.Direction.CW)
            shape.addRect(
                minOf(x, x + side * radius),
                y,
                maxOf(x, x + side * radius),
                y + radius,
                Path.Direction.CW,
            )
            canvas.drawPath(shape, paint)
        }
    }

    /**
     * PdfView extends a selection only when a touch starts on its end handle, so a finger that
     * keeps moving after the long press would leave one word selected. A touch on the end
     * handle is injected, and the moves that follow drag the selection as in a book.
     */
    private fun extendSelectionFromFinger(view: PdfView) {
        val selection = view.currentSelection as? TextSelection ?: return
        val end = selection.bounds.lastOrNull() ?: return
        val handle = view.pdfToViewPoint(PdfPoint(end.pageNum, end.right, end.bottom)) ?: return
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, handle.x + HANDLE_TOUCH_INSET_PX, handle.y + HANDLE_TOUCH_INSET_PX, 0)
        injectingTouch = true
        try {
            view.dispatchTouchEvent(down)
        } finally {
            injectingTouch = false
            down.recycle()
        }
    }

    private fun onTap(
        view: PdfView,
        x: Float,
        y: Float,
    ) {
        if (view.width <= 0 || view.height <= 0) return
        val point = view.viewToPdfPoint(x, y)
        if (point != null) {
            val hit =
                hitAreas.firstOrNull { area ->
                    area.page == point.pageNum && area.boxes.any { it.contains(point.x, point.y, DECORATION_HIT_PADDING) }
                }
            if (hit != null) {
                channel?.onDecorationInteraction(hit.id, hit.group, "tap", hit.locator)
                return
            }
        }
        channel?.onTap(x = (x / view.width).toDouble(), y = (y / view.height).toDouble())
    }

    // endregion
}
