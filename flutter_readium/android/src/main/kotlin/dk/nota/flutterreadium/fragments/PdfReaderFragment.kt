package dk.nota.flutterreadium.fragments

import android.os.Bundle
import java.util.WeakHashMap
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.commitNow
import com.github.barteksc.pdfviewer.PDFView
import kotlin.math.hypot
import androidx.lifecycle.lifecycleScope
import dk.nota.flutterreadium.R
import dk.nota.flutterreadium.ReadiumReader
import dk.nota.flutterreadium.models.PdfReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.readium.adapter.pdfium.navigator.PdfiumNavigatorFragment
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl

private const val TAG = "PdfReaderFragment"
private const val NAVIGATOR_FRAGMENT_TAG = "READIUM_PDF_READER_FRAGMENT"

private var instanceNo = 0

/**
 * Host fragment for the Readium PDF navigator. Mirrors [EpubReaderFragment] but
 * follows the upstream PDF test-app pattern: the navigator's
 * [androidx.fragment.app.FragmentFactory] must be installed on
 * `childFragmentManager` *before* `super.onCreate`, because
 * [PdfNavigatorFragment] has an `internal` constructor that only the
 * generated factory can invoke.
 */
@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class)
class PdfReaderFragment :
    VisualReaderFragment(),
    PdfNavigatorFragment.Listener,
    CoroutineScope by MainScope() {
    interface Listener {
        fun onPageChanged(
            pageIndex: Int,
            totalPages: Int,
            locator: Locator,
        )

        fun onExternalLinkActivated(url: AbsoluteUrl)
    }

    var listener: Listener? = null

    val started = MutableStateFlow(false)

    private val instance = ++instanceNo

    private var pdfNavigator
        get() = navigator as? PdfiumNavigatorFragment
        set(value) {
            navigator = value
        }

    private val pdfVm
        get() = vm as PdfReaderViewModel?

    /**
     * Synchronous wrappers around the navigator's `go*` methods so the
     * surrounding [dk.nota.flutterreadium.navigators.PdfNavigator] can await
     * fragment-startup before calling.
     */
    fun goBackward(animated: Boolean): Boolean {
        val nav = pdfNavigator
        if (nav == null) {
            Log.d(TAG, "::goBackward - $instance - navigator not ready")
            return false
        }
        return nav.goBackward(animated)
    }

    fun goForward(animated: Boolean): Boolean {
        val nav = pdfNavigator
        if (nav == null) {
            Log.d(TAG, "::goForward - $instance - navigator not ready")
            return false
        }
        return nav.goForward(animated)
    }

    @OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
    fun updatePreferences(preferences: PdfiumPreferences) {
        val nav = pdfNavigator
        if (nav == null) {
            Log.d(TAG, "::updatePreferences - $instance - navigator not ready")
            return
        }
        nav.submitPreferences(preferences)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "::onCreate - $instance")
        val model = pdfVm
        val navigatorFactory = model?.navigatorFactory
        if (model == null || navigatorFactory == null) {
            Log.e(TAG, "::onCreate - $instance - missing view model or navigator factory")
            // Without a factory installed before super.onCreate, the FragmentManager
            // would crash when it tries to instantiate the (internal-ctor)
            // PdfNavigatorFragment. Install the dummy factory so the host can
            // gracefully fail rather than throw at restore time.
            childFragmentManager.fragmentFactory =
                PdfNavigatorFragment.createDummyFactory(
                    pdfEngineProvider =
                        org.readium.adapter.pdfium.navigator
                            .PdfiumEngineProvider(),
                )
            super.onCreate(savedInstanceState)
            return
        }

        childFragmentManager.fragmentFactory =
            navigatorFactory.createFragmentFactory(
                initialLocator = model.locator,
                initialPreferences = model.preferences,
                listener = this,
            )
        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        try {
            super.onViewCreated(view, savedInstanceState)
            Log.d(TAG, "::onViewCreated - $instance")

            val model = pdfVm
            if (model == null || model.navigatorFactory == null) {
                Log.e(TAG, "::onViewCreated - $instance - missing model / factory")
                return
            }

            attachingNavigatorFragment = true

            lifecycleScope.launch {
                if (ReadiumReader.currentPublication != null) {
                    attachNavigator()
                } else {
                    Log.d(TAG, "::onViewCreated - $instance - publication is missing")
                }
                attachingNavigatorFragment = false
            }
        } finally {
            Log.d(TAG, "::onViewCreated - $instance - ended")
        }
    }

    override fun onResume() {
        try {
            Log.d(TAG, "::onResume - $instance - attaching=$attachingNavigatorFragment")
            if (pdfVm == null) {
                Log.d(TAG, "::onResume - $instance - missing view model")
                return
            }
            if (attachingNavigatorFragment) {
                Log.d(TAG, "::onResume - $instance - don't attach navigator")
                return
            }
            attachNavigator()
        } finally {
            super.onResume()
        }
    }

    override fun onPause() {
        try {
            Log.d(TAG, "::onPause - $instance")
            pdfVm?.locator = currentLocator?.value
            pdfNavigator?.let { fragment ->
                childFragmentManager.commitNow { remove(fragment) }
            }
            pdfNavigator = null
            started.value = false
            attachingNavigatorFragment = false
        } finally {
            super.onPause()
        }
    }

    private var attachingNavigatorFragment = false

    private val immediateTapViews = WeakHashMap<PDFView, Boolean>()

    /// The PDF engine reports a tap only after the double-tap zoom timeout.
    /// The book reports it when the finger lifts, so listen for that lift.
    private fun watchPdfTaps(root: View) {
        root.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (!root.isAttachedToWindow) {
                        root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        return
                    }
                    root.findPdfView()?.let(::installImmediateTap)
                }
            },
        )
    }

    private fun installImmediateTap(pdfView: PDFView) {
        if (immediateTapViews.containsKey(pdfView)) return
        val drag =
            try {
                val field = PDFView::class.java.getDeclaredField("dragPinchManager")
                field.isAccessible = true
                field.get(pdfView) as View.OnTouchListener
            } catch (error: ReflectiveOperationException) {
                Log.w(TAG, "::installImmediateTap - touch listener unavailable", error)
                return
            }
        immediateTapViews[pdfView] = true
        val slop = ViewConfiguration.get(pdfView.context).scaledTouchSlop.toFloat()
        val maximumHold = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var moved = false
        pdfView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = event.eventTime
                    moved = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> moved = true
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.x - downX, event.y - downY) > slop) moved = true
                }
                MotionEvent.ACTION_UP -> {
                    val held = event.eventTime - downAt
                    val tap = !moved && held in 0..maximumHold
                    val handled = drag.onTouch(view, event)
                    if (tap && view.width > 0 && view.height > 0) {
                        ReadiumReader.currentReaderWidget?.channel?.onTap(
                            x = (event.x / view.width).toDouble(),
                            y = (event.y / view.height).toDouble(),
                        )
                    }
                    return@setOnTouchListener handled
                }
            }
            drag.onTouch(view, event)
        }
    }

    private fun View.findPdfView(): PDFView? {
        if (this is PDFView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findPdfView()?.let { return it }
        }
        return null
    }

    private fun attachNavigator() {
        Log.d(TAG, "::attachNavigator() - $instance")
        if (navigator != null) {
            Log.d(TAG, "::attachNavigator() - $instance - already attached")
            return
        }

        if (ReadiumReader.currentPublication == null) {
            Log.e(TAG, "::attachNavigator() - $instance - missing publication")
            return
        }

        // The FragmentFactory was already installed in onCreate. We instantiate by
        // class name via `replace`, and the FragmentManager calls the factory.
        childFragmentManager.commitNow {
            replace(
                R.id.fragment_reader_container,
                PdfNavigatorFragment::class.java,
                Bundle(),
                NAVIGATOR_FRAGMENT_TAG,
            )
        }

        val pdfNav =
            childFragmentManager.findFragmentByTag(NAVIGATOR_FRAGMENT_TAG) as? PdfiumNavigatorFragment
        if (pdfNav == null) {
            Log.e(TAG, "::attachNavigator() - $instance - failed to retrieve PdfNavigatorFragment")
            return
        }
        pdfNavigator = pdfNav
        Log.d(TAG, "::attachNavigator() - $instance - got navigator = $pdfNav")
        (pdfNav as OverflowableNavigator).apply {
            // Arrow keys turn pages. A tap is reported from the PDF view on
            // finger-up. The engine's own tap waits out a possible double-tap
            // zoom, which would open the chrome later than in a book.
            addInputListener(DirectionalNavigationAdapter(this, tapEdges = emptySet()))
        }
        watchPdfTaps(pdfNav.requireView())

        // PDF has no PaginationListener equivalent — emit page-changed events by
        // observing the navigator's currentLocator. Locator.locations.position is
        // 1-based across both swift and kotlin Readium toolkits, so it maps 1:1
        // to a page number.
        lifecycleScope.launch {
            // PdfiumNavigatorFragment emits an initial locator with `position == null`
            // before layout settles. Skip those rather than fabricate a page number;
            // the next emission carries the real 1-based page position.
            pdfNav.currentLocator.collect { locator ->
                val pageIndex = locator.locations.position ?: return@collect
                val totalPages =
                    ReadiumReader.currentPublication?.metadata?.numberOfPages ?: 1
                listener?.onPageChanged(pageIndex, totalPages, locator)
            }
        }

        started.value = true
    }
}
