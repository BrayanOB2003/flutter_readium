package dk.nota.flutterreadium

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.JavascriptInterface
import android.widget.PopupWindow
import org.json.JSONObject

private const val TAG = "SelDiag"
private const val HANDLE_CLASS_SUFFIX = "PopupTouchHandleDrawable"
private val main = Handler(Looper.getMainLooper())

/**
 * Hides Chromium's selection handles after this page installs the DOM selection.
 *
 * The handles are what scroll the column into the previous page. The highlight
 * and the system menu stay. Touches fall through to the handles drawn by
 * [SELECTION_GESTURES_JS].
 */
class SelectionHandleGuard(
    private val onShowMenu: (Rect) -> Unit = {},
    private val onDismissMenu: () -> Unit = {},
) {
    @JavascriptInterface
    fun settled() {
        val delays = longArrayOf(0, 40, 120, 300, 700)
        delays.forEach { delay ->
            main.postDelayed({ disarmNativeSelectionHandles() }, delay)
        }
    }

    @JavascriptInterface
    fun showMenu(payload: String) {
        try {
            val json = JSONObject(payload)
            val scale = json.optDouble("scale", 1.0).toFloat().coerceAtLeast(1f)
            val left = json.optDouble("left") * scale
            val top = json.optDouble("top") * scale
            val right = json.optDouble("right") * scale
            val bottom = json.optDouble("bottom") * scale
            val rect =
                Rect(
                    minOf(left, right).toInt().coerceAtLeast(0),
                    minOf(top, bottom).toInt().coerceAtLeast(0),
                    maxOf(left, right).toInt().coerceAtLeast(1),
                    maxOf(top, bottom).toInt().coerceAtLeast(1),
                )
            Log.d(TAG, "menu request $rect")
            main.post { onShowMenu(rect) }
        } catch (error: Exception) {
            Log.d(TAG, "menu payload $error")
        }
    }

    @JavascriptInterface
    fun dismissMenu() {
        main.post { onDismissMenu() }
    }

    companion object {
        const val JS_NAME = "FlutterPageSelection"
    }
}

private var loggedMissingPopup = false

private fun disarmNativeSelectionHandles() {
    windowRoots().flatMap { it.findHandleViews() }.forEach { view ->
        view.alpha = 0f
        val popup = view.findPopupWindow()
        if (popup == null) {
            if (!loggedMissingPopup) {
                loggedMissingPopup = true
                Log.d(TAG, "handle popup missing on ${view.javaClass.name}")
            }
            return@forEach
        }
        popup.isTouchable = false
        popup.isFocusable = false
        popup.update()
        Log.d(TAG, "handle disarmed")
    }
}

private fun windowRoots(): List<View> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        WindowInspector.getGlobalWindowViews()
    } else {
        legacyWindowRoots()
    }

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
private fun legacyWindowRoots(): List<View> =
    try {
        val clazz = Class.forName("android.view.WindowManagerGlobal")
        val instance = clazz.getMethod("getInstance").invoke(null)
        val field = clazz.getDeclaredField("mViews")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(instance) as? List<View>) ?: emptyList()
    } catch (error: Exception) {
        Log.d(TAG, "handle windows unavailable $error")
        emptyList()
    }

private fun View.findHandleViews(): List<View> {
    val found = mutableListOf<View>()
    if (javaClass.name.endsWith(HANDLE_CLASS_SUFFIX)) found.add(this)
    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            found.addAll(getChildAt(index).findHandleViews())
        }
    }
    return found
}

private fun View.findPopupWindow(): PopupWindow? {
    var type: Class<*>? = javaClass
    while (type != null) {
        val fields = type.declaredFields
        for (field in fields) {
            if (!PopupWindow::class.java.isAssignableFrom(field.type)) continue
            field.isAccessible = true
            return field.get(this) as? PopupWindow
        }
        type = type.superclass
    }
    return null
}
