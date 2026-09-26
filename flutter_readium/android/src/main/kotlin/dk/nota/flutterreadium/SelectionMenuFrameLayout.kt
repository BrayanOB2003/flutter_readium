package dk.nota.flutterreadium

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
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
            val mode = super.startActionModeForChild(originalView, wrapped, type)
            if (mode == null) {
                (wrapped as? SystemSelectionActionModeCallback)?.releaseLock()
            }
            return mode
        }
    }
