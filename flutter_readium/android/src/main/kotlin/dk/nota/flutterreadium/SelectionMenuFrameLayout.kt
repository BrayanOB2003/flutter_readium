package dk.nota.flutterreadium

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * Ancestor of the EPUB WebView. The selection menu starts here, so this is
 * where the system callback can be wrapped without replacing Copy and Share.
 */
class SelectionMenuFrameLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
        var decorateActionMode: ((View, ActionMode.Callback) -> ActionMode.Callback)? = null

        private var pointerDown = false
        private var selectionCallback: SystemSelectionActionModeCallback? = null

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerDown = true
                    selectionCallback?.holdHidden()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pointerDown) selectionCallback?.holdHidden()
                }
            }
            val handled = super.dispatchTouchEvent(event)
            if (
                (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) &&
                event.pointerCount <= 1
            ) {
                pointerDown = false
                post {
                    if (!pointerDown) selectionCallback?.reveal()
                }
            }
            return handled
        }

        override fun startActionModeForChild(
            originalView: View,
            callback: ActionMode.Callback,
            type: Int,
        ): ActionMode? {
            val decorate = decorateActionMode
            if (decorate == null || type != ActionMode.TYPE_FLOATING || originalView !is WebView) {
                return super.startActionModeForChild(originalView, callback, type)
            }
            val wrapped = decorate(originalView, callback)
            (wrapped as? SystemSelectionActionModeCallback)?.let { callback ->
                callback.isPointerDown = { pointerDown }
                selectionCallback = callback
            }
            val mode = super.startActionModeForChild(originalView, wrapped, type)
            if (mode == null) {
                (wrapped as? SystemSelectionActionModeCallback)?.releaseLock()
            }
            return mode
        }
    }
