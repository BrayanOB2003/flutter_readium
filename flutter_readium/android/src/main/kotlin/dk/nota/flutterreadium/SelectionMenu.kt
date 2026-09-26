package dk.nota.flutterreadium

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import android.widget.PopupMenu
import kotlinx.coroutines.ExperimentalCoroutinesApi

private const val SELECTION_MENU_STILL_MS = 50L
private const val SELECTION_MENU_HOLD_MS = 3_000L

/**
 * Decides when the Android selection menu is already the one we want.
 *
 * [onPrepareActionMode] returning that the menu changed makes the system rebuild
 * it, and that rebuild scrolls the column so the menu fits. Once Copy and Share
 * are present and nothing else is left, the menu stays as it is.
 */
internal data class SelectionMenuTarget(
    /** Each set is one action. The menu is settled when every set has one of its ids. */
    val requiredGroups: List<Set<Int>>,
    val optionalSystemIds: Set<Int>,
    val customIds: Set<Int>,
) {
    val permittedIds: Set<Int> =
        requiredGroups.flatten().toSet() + optionalSystemIds + customIds
}

/**
 * The floating toolbar is a separate window. Creating it while the handles are
 * still moving paints that window as an empty black bar, then jumps it into
 * place. [onContentRect] withholds it until the rect stays still.
 */
internal class SelectionToolbarGate {
    private var last: SelectionContentRect? = null
    var withhold: Boolean = true
        private set

    /** Returns true when the rect moved and the quiet timer should restart. */
    fun onContentRect(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        val rect = SelectionContentRect(left, top, right, bottom)
        val moved = last != rect
        last = rect
        if (moved) withhold = true
        return moved
    }

    fun onQuiet() {
        withhold = false
    }
}

internal data class SelectionContentRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun selectionMenuIsSettled(
    itemIds: Set<Int>,
    target: SelectionMenuTarget,
): Boolean {
    if (itemIds.any { it !in target.permittedIds }) return false
    if (target.customIds.any { it !in itemIds }) return false
    if (target.requiredGroups.any { group -> group.none { it in itemIds } }) return false
    return true
}

internal fun selectionMenuTarget(
    allowedNames: Set<String>?,
    copyIds: Set<Int>,
    shareIds: Set<Int>,
    selectAllIds: Set<Int>,
    customIds: Set<Int>,
): SelectionMenuTarget {
    fun allowed(name: String): Boolean = allowedNames == null || name in allowedNames

    fun group(
        enabled: Boolean,
        ids: Set<Int>,
    ): Set<Int>? = ids.takeIf { enabled && it.isNotEmpty() }

    return SelectionMenuTarget(
        requiredGroups =
            listOfNotNull(
                group(allowed("copy"), copyIds),
                group(allowed("share"), shareIds),
            ),
        optionalSystemIds = if (allowed("selectAll")) selectAllIds else emptySet(),
        customIds = customIds,
    )
}

internal data class WebViewSelectionIds(
    val copy: Set<Int>,
    val share: Set<Int>,
    val selectAll: Set<Int>,
    val canFilter: Boolean,
)

internal fun webViewSelectionIds(context: Context): WebViewSelectionIds {
    val copy = webViewResourceId(context, "select_action_menu_copy")
    val share = webViewResourceId(context, "select_action_menu_share")
    val selectAll = webViewResourceId(context, "select_action_menu_select_all")
    return WebViewSelectionIds(
        copy = setOfNotNull(copy.takeIf { it != 0 }, android.R.id.copy),
        share = setOfNotNull(share.takeIf { it != 0 }, android.R.id.shareText),
        selectAll = setOfNotNull(selectAll.takeIf { it != 0 }, android.R.id.selectAll),
        canFilter = copy != 0 || share != 0 || selectAll != 0,
    )
}

private fun webViewResourceId(
    context: Context,
    name: String,
): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0
    val packageName = WebView.getCurrentWebViewPackage()?.packageName ?: return 0
    return try {
        context.packageManager
            .getResourcesForApplication(packageName)
            .getIdentifier(name, "id", packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        0
    }
}

internal const val SELECTION_CUSTOM_ID_OFFSET = 0x00f10000

internal fun selectionCustomItemId(index: Int): Int = SELECTION_CUSTOM_ID_OFFSET + index

private fun Menu.itemIds(): Set<Int> =
    buildSet {
        for (index in 0 until size()) {
            add(getItem(index).itemId)
        }
    }

/**
 * Pins a WebView's scroll while a selection menu is open in book mode.
 * Showing the menu asks the WebView to scroll the selection into view, which
 * shifts a fixed column.
 */
internal class ColumnScrollLock(
    private val view: View,
) {
    private val x = view.scrollX
    private val y = view.scrollY
    private var restoring = false
    private val listener =
        View.OnScrollChangeListener { scrolled, scrollX, scrollY, _, _ ->
            if (restoring || (scrollX == x && scrollY == y)) return@OnScrollChangeListener
            restoring = true
            scrolled.scrollTo(x, y)
            restoring = false
        }

    fun install() {
        if (view.getTag(R.id.selection_column_scroll_lock) === this) return
        view.setTag(R.id.selection_column_scroll_lock, this)
        view.setOnScrollChangeListener(listener)
    }

    fun release() {
        if (view.getTag(R.id.selection_column_scroll_lock) !== this) return
        view.setOnScrollChangeListener(null)
        view.setTag(R.id.selection_column_scroll_lock, null)
    }
}

/**
 * Wraps the WebView's own selection callback.
 *
 * The system callback still performs Copy, Share, and Select all. This wrapper
 * drops every other system item, appends the app's custom actions, and reports
 * the selection. [onPrepareActionMode] returns false once that menu is in place
 * so Android does not rebuild it.
 */
internal class SystemSelectionActionModeCallback(
    private val delegate: ActionMode.Callback,
    private val source: View,
    private val columnLock: ColumnScrollLock?,
    private val canFilter: Boolean,
    private val target: SelectionMenuTarget,
    private val customActions: List<SelectionActionConfig>,
    private val onTextSelected: () -> Unit,
    private val onCustomAction: (SelectionActionConfig) -> Unit,
) : ActionMode.Callback2() {
    private var preparing = false
    private var mode: ActionMode? = null

    fun releaseLock() {
        columnLock?.release()
    }

    override fun onCreateActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        this.mode = mode
        columnLock?.install()
        val created = delegate.onCreateActionMode(mode, menu)
        if (canFilter) enforce(menu)
        onTextSelected()
        return created
    }

    override fun onPrepareActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        if (preparing) return false
        if (menuIsQuiet(menu)) return false
        preparing = true
        try {
            delegate.onPrepareActionMode(mode, menu)
            if (canFilter) enforce(menu)
        } finally {
            preparing = false
        }
        return false
    }

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem,
    ): Boolean {
        val index = item.itemId - SELECTION_CUSTOM_ID_OFFSET
        val action = customActions.getOrNull(index)
        if (action != null) {
            onCustomAction(action)
            mode.finish()
            return true
        }
        return delegate.onActionItemClicked(mode, item)
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        this.mode = null
        columnLock?.release()
        delegate.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(
        mode: ActionMode,
        view: View,
        outRect: Rect,
    ) {
        val callback2 = delegate as? ActionMode.Callback2
        if (callback2 != null) {
            callback2.onGetContentRect(mode, view, outRect)
        } else {
            super.onGetContentRect(mode, view, outRect)
        }
    }

    private fun menuIsQuiet(menu: Menu): Boolean {
        if (!canFilter) return menu.size() > 0
        return selectionMenuIsSettled(menu.itemIds(), target)
    }

    private fun enforce(menu: Menu) {
        val stale = menu.itemIds().filter { it !in target.permittedIds }
        stale.forEach { menu.removeItem(it) }
        customActions.forEachIndexed { index, action ->
            val id = selectionCustomItemId(index)
            if (menu.findItem(id) == null) {
                menu.add(Menu.NONE, id, Menu.NONE, action.title)
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun decorateSystemSelectionCallback(
    delegate: ActionMode.Callback,
    source: View,
    bookMode: Boolean,
    context: Context,
    onTextSelected: () -> Unit,
    onCustomAction: (SelectionActionConfig) -> Unit,
): SystemSelectionActionModeCallback {
    val ids = webViewSelectionIds(context)
    val actions = ReadiumReader.selectionActions
    val target =
        selectionMenuTarget(
            allowedNames = ReadiumReader.allowedDefaultActions,
            copyIds = ids.copy,
            shareIds = ids.share,
            selectAllIds = ids.selectAll,
            customIds = actions.indices.map { selectionCustomItemId(it) }.toSet(),
        )
    val lock = if (bookMode && source is WebView) ColumnScrollLock(source) else null
    lock?.install()
    return SystemSelectionActionModeCallback(
        delegate = delegate,
        source = source,
        columnLock = lock,
        canFilter = ids.canFilter,
        target = target,
        customActions = actions,
        onTextSelected = onTextSelected,
        onCustomAction = onCustomAction,
    )
}

/**
 * Stands in for the system floating toolbar until the selection rect stays still.
 *
 * The real toolbar is a popup window. Opening it while the handles move draws
 * the window's empty background, then slides it into place when the drag ends.
 */
internal class DeferredSelectionActionMode(
    private val view: View,
    private val callback: ActionMode.Callback,
    private val startReal: () -> ActionMode?,
) : ActionMode() {
    private var real: ActionMode? = null
    private var finished = false
    private var readingRect = false
    private val gate = SelectionToolbarGate()
    private val stubMenu by lazy { PopupMenu(view.context, view).menu }
    private val promoteRunnable = Runnable { promote() }
    private val keepHidden =
        object : Runnable {
            override fun run() {
                val active = real ?: return
                if (!gate.withhold) return
                active.hide(SELECTION_MENU_HOLD_MS)
                view.postDelayed(this, SELECTION_MENU_HOLD_MS - 500)
            }
        }

    init {
        setType(TYPE_FLOATING)
        view.post {
            if (real == null && !finished) invalidateContentRect()
        }
    }

    override fun setTitle(title: CharSequence?) {
        real?.setTitle(title)
    }

    override fun setTitle(resId: Int) {
        real?.setTitle(resId)
    }

    override fun setSubtitle(subtitle: CharSequence?) {
        real?.setSubtitle(subtitle)
    }

    override fun setSubtitle(resId: Int) {
        real?.setSubtitle(resId)
    }

    override fun setCustomView(view: View?) {
        real?.setCustomView(view)
    }

    override fun invalidate() {
        if (readingRect) return
        val active = real
        if (active != null && !gate.withhold) {
            active.invalidate()
        } else {
            invalidateContentRect()
        }
    }

    override fun invalidateContentRect() {
        if (finished || readingRect) return
        val rect = Rect()
        val callback2 = callback as? Callback2
        if (callback2 != null) {
            readingRect = true
            try {
                callback2.onGetContentRect(this, view, rect)
            } finally {
                readingRect = false
            }
        }
        if (rect.isEmpty) return
        val moved = gate.onContentRect(rect.left, rect.top, rect.right, rect.bottom)
        if (moved) {
            view.removeCallbacks(promoteRunnable)
            view.postDelayed(promoteRunnable, SELECTION_MENU_STILL_MS)
            val active = real ?: return
            view.removeCallbacks(keepHidden)
            active.hide(SELECTION_MENU_HOLD_MS)
            view.postDelayed(keepHidden, SELECTION_MENU_HOLD_MS - 500)
            return
        }
        if (real != null && !gate.withhold) {
            real?.invalidateContentRect()
        }
    }

    override fun hide(duration: Long) {
        if (gate.withhold) return
        real?.hide(duration)
    }

    override fun finish() {
        if (finished) return
        finished = true
        view.removeCallbacks(promoteRunnable)
        view.removeCallbacks(keepHidden)
        val active = real
        real = null
        if (active != null) {
            active.finish()
        } else {
            callback.onDestroyActionMode(this)
        }
    }

    override fun getMenu(): Menu = real?.menu ?: stubMenu

    override fun getTitle(): CharSequence? = real?.title

    override fun getSubtitle(): CharSequence? = real?.subtitle

    override fun getCustomView(): View? = real?.customView

    override fun getMenuInflater(): MenuInflater = real?.menuInflater ?: MenuInflater(view.context)

    private fun promote() {
        if (finished) return
        gate.onQuiet()
        view.removeCallbacks(keepHidden)
        val active = real
        if (active == null) {
            real = startReal()
        } else {
            active.invalidateContentRect()
            active.hide(0)
        }
    }
}
