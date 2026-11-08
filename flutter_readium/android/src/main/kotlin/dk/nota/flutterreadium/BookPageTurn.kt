package dk.nota.flutterreadium

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Minimum movement, in dp, before Readium treats a drag as a flick.
 * Kept in step with `MIN_DISTANCE_FOR_FLING` in `R2WebView`.
 */
internal const val READIUM_FLING_DISTANCE_DP = 25

/**
 * Speed, in dp/s, above which Readium turns the page from the flick alone.
 * Kept in step with `MIN_FLING_VELOCITY` in `R2WebView`.
 */
internal const val READIUM_FLING_VELOCITY_DP = 400

private const val R2_WEB_VIEW = "org.readium.r2.navigator.R2WebView"
private const val TAG = "BookPageTurn"

/**
 * Where a book-mode drag should finish when Readium's own decision would
 * leave the reader on the wrong page.
 */
internal sealed interface PageTurnTarget {
    data class Page(
        val index: Int,
    ) : PageTurnTarget

    data object PreviousResource : PageTurnTarget

    data object NextResource : PageTurnTarget
}

/**
 * Returns a correction, or null when Readium already settles on the same page.
 *
 * [dragged] is the change in `scrollX` since the gesture began. Positive scroll
 * moves toward the next page. [deltaX] is the finger's travel, positive to the
 * right, which is toward the previous page. A velocity of zero means the finger
 * stopped. It does not cancel a drag that already crossed half the page.
 *
 * At the first or last page of a file the WebView cannot scroll, so the finger
 * travel stands in for [dragged]. A quick flick keeps Readium's own result.
 */
internal fun pageTurnCorrection(
    startPage: Int,
    velocity: Int,
    initialVelocity: Int,
    deltaX: Int,
    deltaY: Int,
    dragged: Int,
    pageWidth: Int,
    pageCount: Int,
    atStart: Boolean,
    atEnd: Boolean,
    flingDistance: Int,
    minFlingVelocity: Int,
    scrollMode: Boolean = false,
    selecting: Boolean = false,
): PageTurnTarget? {
    if (scrollMode || selecting || pageWidth <= 0 || pageCount <= 0) return null
    if (abs(deltaY) > abs(deltaX)) return null

    val ours =
        settledPage(
            startPage = startPage,
            velocity = velocity,
            deltaX = deltaX,
            dragged = dragged,
            pageWidth = pageWidth,
            atStart = atStart,
            atEnd = atEnd,
            flingDistance = flingDistance,
            minFlingVelocity = minFlingVelocity,
        )
    val readium =
        readiumSettledPage(
            startPage = startPage,
            velocity = velocity,
            initialVelocity = initialVelocity,
            deltaX = deltaX,
            flingDistance = flingDistance,
            minFlingVelocity = minFlingVelocity,
        )
    if (ours == readium) return null

    val lastPage = pageCount - 1
    return when {
        ours < 0 -> PageTurnTarget.PreviousResource
        ours > lastPage -> PageTurnTarget.NextResource
        else -> PageTurnTarget.Page(ours)
    }
}

/**
 * Readium turns a page only when the finger is still fast at release and was
 * already fast when the drag began. Any other drag snaps back to [startPage].
 */
internal fun readiumSettledPage(
    startPage: Int,
    velocity: Int,
    initialVelocity: Int,
    deltaX: Int,
    flingDistance: Int,
    minFlingVelocity: Int,
): Int {
    val cancelled = initialVelocity.toLong() * velocity.toLong() <= 0L
    if (!cancelled && abs(deltaX) > flingDistance && abs(velocity) > minFlingVelocity) {
        return if (velocity >= 0) startPage - 1 else startPage + 1
    }
    return startPage
}

private fun settledPage(
    startPage: Int,
    velocity: Int,
    deltaX: Int,
    dragged: Int,
    pageWidth: Int,
    atStart: Boolean,
    atEnd: Boolean,
    flingDistance: Int,
    minFlingVelocity: Int,
): Int {
    if (abs(deltaX) > flingDistance && abs(velocity) > minFlingVelocity) {
        return if (velocity >= 0) startPage - 1 else startPage + 1
    }

    // At the edge of a file the WebView is clamped. A rubber-band still moves
    // scrollX a little, so the finger distance is what records the drag.
    val travel =
        when {
            atEnd && deltaX < 0 -> max(dragged, -deltaX)
            atStart && deltaX > 0 -> min(dragged, -deltaX)
            else -> dragged
        }
    val half = pageWidth / 2
    return when {
        travel > half -> startPage + 1
        travel < -half -> startPage - 1
        else -> startPage
    }
}

/**
 * Watches book-mode drags on the Readium WebView and, when Readium would snap
 * back, settles the page before the next draw.
 *
 * `R2WebView` is final, so this runs from the reader's root view: its
 * `dispatchTouchEvent` wraps the WebView's own handling.
 */
internal class BookPageTurnController {
    private var tracker: VelocityTracker? = null
    private var gesture: Gesture? = null
    private var pending: (() -> Unit)? = null

    fun beforeDispatch(
        root: View,
        event: MotionEvent,
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(root, event)
            MotionEvent.ACTION_POINTER_DOWN -> gesture?.multiTouch = true
            MotionEvent.ACTION_MOVE -> noteMove(event)
            MotionEvent.ACTION_UP -> pending = correction(event)
            MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    fun afterDispatch() {
        val apply = pending
        pending = null
        apply?.invoke()
    }

    private fun begin(
        root: View,
        event: MotionEvent,
    ) {
        reset()
        val webView = root.findReadiumWebView(event.rawX, event.rawY) ?: return
        val pageWidth =
            try {
                webView.horizontalScrollExtent()
            } catch (_: Exception) {
                webView.width
            }
        if (pageWidth <= 0) return
        val configuration = ViewConfiguration.get(webView.context)
        gesture =
            Gesture(
                webView = webView,
                pointerId = event.getPointerId(0),
                startX = event.x,
                startY = event.y,
                startScrollX = webView.scrollX,
                pageWidth = pageWidth,
                atStart = !webView.canScrollHorizontally(-1),
                atEnd = !webView.canScrollHorizontally(1),
                slop = configuration.scaledPagingTouchSlop,
                maxFlingVelocity = configuration.scaledMaximumFlingVelocity.toFloat(),
                flingDistance = (READIUM_FLING_DISTANCE_DP * webView.resources.displayMetrics.density).toInt(),
                minFlingVelocity = (READIUM_FLING_VELOCITY_DP * webView.resources.displayMetrics.density).toInt(),
            )
        tracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun noteMove(event: MotionEvent) {
        val gesture = gesture ?: return
        val tracker = tracker ?: return
        tracker.addMovement(event)
        if (gesture.initialVelocity != null || gesture.multiTouch) return
        val index = event.findPointerIndex(gesture.pointerId)
        if (index < 0) return
        val travelled = abs(event.getX(index) - gesture.startX)
        if (travelled <= gesture.slop) return
        tracker.computeCurrentVelocity(1000, gesture.maxFlingVelocity)
        gesture.initialVelocity = tracker.getXVelocity(gesture.pointerId).toInt()
    }

    private fun correction(event: MotionEvent): (() -> Unit)? {
        val gesture = gesture ?: return null
        val tracker = tracker
        this.gesture = null
        this.tracker = null
        try {
            tracker?.addMovement(event)
            if (gesture.multiTouch || tracker == null) return null
            return correctionFor(gesture, tracker, event)
        } catch (error: ReflectiveOperationException) {
            PluginLog.w(TAG, "::correction. Could not read the Readium page. ${error.message}")
            return null
        } finally {
            tracker?.recycle()
        }
    }

    private fun correctionFor(
        gesture: Gesture,
        tracker: VelocityTracker,
        event: MotionEvent,
    ): (() -> Unit)? {
        val webView = gesture.webView
        if (!webView.isAttachedToWindow) return null
        val index = event.findPointerIndex(gesture.pointerId)
        if (index < 0) return null
        tracker.computeCurrentVelocity(1000, gesture.maxFlingVelocity)
        val pageCount = webView.horizontalPageCount(gesture.pageWidth)
        val startPage =
            (gesture.startScrollX.toDouble() / gesture.pageWidth)
                .roundToInt()
                .coerceIn(0, pageCount - 1)
        val target =
            pageTurnCorrection(
                startPage = startPage,
                velocity = tracker.getXVelocity(gesture.pointerId).toInt(),
                initialVelocity = gesture.initialVelocity ?: 0,
                deltaX = (event.getX(index) - gesture.startX).toInt(),
                deltaY = (event.getY(index) - gesture.startY).toInt(),
                dragged = webView.scrollX - gesture.startScrollX,
                pageWidth = gesture.pageWidth,
                pageCount = pageCount,
                atStart = gesture.atStart,
                atEnd = gesture.atEnd,
                flingDistance = gesture.flingDistance,
                minFlingVelocity = gesture.minFlingVelocity,
                scrollMode = webView.isReadiumScrollMode(),
                selecting = webView.isReadiumSelecting(),
            ) ?: return null
        return { webView.settle(target, pageCount) }
    }

    private fun reset() {
        pending = null
        gesture = null
        tracker?.recycle()
        tracker = null
    }

    private class Gesture(
        val webView: View,
        val pointerId: Int,
        val startX: Float,
        val startY: Float,
        val startScrollX: Int,
        val pageWidth: Int,
        val atStart: Boolean,
        val atEnd: Boolean,
        val slop: Int,
        val maxFlingVelocity: Float,
        val flingDistance: Int,
        val minFlingVelocity: Int,
        var initialVelocity: Int? = null,
        var multiTouch: Boolean = false,
    )
}

private fun View.findReadiumWebView(
    rawX: Float,
    rawY: Float,
): WebView? {
    if (this is WebView && javaClass.name == R2_WEB_VIEW) {
        val visible = android.graphics.Rect()
        if (isShown && getGlobalVisibleRect(visible) && visible.contains(rawX.toInt(), rawY.toInt())) {
            return this
        }
    }
    if (this is ViewGroup) {
        for (index in childCount - 1 downTo 0) {
            getChildAt(index).findReadiumWebView(rawX, rawY)?.let { return it }
        }
    }
    return null
}

private fun View.horizontalScrollExtent(): Int = invokeScroll("computeHorizontalScrollExtent")

private fun View.horizontalPageCount(pageWidth: Int): Int {
    if (pageWidth <= 0) return 1
    val range = invokeScroll("computeHorizontalScrollRange")
    return (range.toDouble() / pageWidth).roundToInt().coerceAtLeast(1)
}

private fun View.invokeScroll(name: String): Int {
    val method = View::class.java.getDeclaredMethod(name)
    method.isAccessible = true
    return method.invoke(this) as Int
}

private fun View.isReadiumScrollMode(): Boolean {
    val method = javaClass.methods.firstOrNull { it.name == "getScrollMode" && it.parameterTypes.isEmpty() }
    return method?.invoke(this) as? Boolean ?: false
}

private fun View.isReadiumSelecting(): Boolean {
    var type: Class<*>? = javaClass
    while (type != null) {
        val field = type.declaredFields.firstOrNull { it.name == "isSelecting" }
        if (field != null) {
            field.isAccessible = true
            return field.getBoolean(this)
        }
        type = type.superclass
    }
    return false
}

private fun View.settle(
    target: PageTurnTarget,
    pageCount: Int,
) {
    try {
        when (target) {
            is PageTurnTarget.Page -> {
                invokeScrollCommand("setCurrentItem", target.index, true)
            }
            PageTurnTarget.PreviousResource -> {
                if (canScrollHorizontally(-1)) {
                    invokeScrollCommand("setCurrentItem", 0, true)
                } else {
                    invokeScrollCommand("scrollLeft", true)
                }
            }
            PageTurnTarget.NextResource -> {
                if (canScrollHorizontally(1)) {
                    invokeScrollCommand("setCurrentItem", pageCount - 1, true)
                } else {
                    invokeScrollCommand("scrollRight", true)
                }
            }
        }
    } catch (error: ReflectiveOperationException) {
        PluginLog.w(TAG, "::settle. Readium page turn is not available. ${error.message}")
    } catch (error: IllegalArgumentException) {
        PluginLog.w(TAG, "::settle. Readium page turn is not available. ${error.message}")
    }
}

private fun View.invokeScrollCommand(
    name: String,
    vararg args: Any,
) {
    val types =
        Array(args.size) { index ->
            when (args[index]) {
                is Int -> Int::class.javaPrimitiveType
                is Boolean -> Boolean::class.javaPrimitiveType
                else -> args[index].javaClass
            }
        }
    javaClass.getMethod(name, *types).invoke(this, *args)
}
