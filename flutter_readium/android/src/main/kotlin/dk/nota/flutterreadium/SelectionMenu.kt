package dk.nota.flutterreadium

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView
import kotlinx.coroutines.ExperimentalCoroutinesApi

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

private const val STANDALONE_PACKAGE_ID = 0x7f
private const val RESOURCE_ENTRY_MASK = 0x00ffffff

/**
 * WebView ids come from the WebView APK opened on its own, where its package
 * byte is 0x7f. Inside the app the WebView is loaded as a shared library and
 * gets another byte (0x02 on current devices), so only type and entry count.
 */
internal fun menuIdMatches(
    itemId: Int,
    knownId: Int,
): Boolean {
    if (itemId == knownId) return true
    if (knownId ushr 24 != STANDALONE_PACKAGE_ID) return false
    return itemId and RESOURCE_ENTRY_MASK == knownId and RESOURCE_ENTRY_MASK
}

internal fun Set<Int>.containsMenuId(itemId: Int): Boolean = any { menuIdMatches(itemId, it) }

internal fun selectionMenuIsSettled(
    itemIds: Set<Int>,
    target: SelectionMenuTarget,
): Boolean {
    if (itemIds.any { !target.permittedIds.containsMenuId(it) }) return false
    if (target.customIds.any { it !in itemIds }) return false
    if (target.requiredGroups.any { group -> itemIds.none { group.containsMenuId(it) } }) return false
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
 * Wraps the WebView's own selection callback.
 *
 * The system callback still performs Copy, Share, and Select all. This wrapper
 * drops every other system item, appends the app's custom actions, and reports
 * the selection. [onPrepareActionMode] returns false once that menu is in place
 * so Android does not rebuild it.
 *
 * The column stays in place through [SELECTION_GESTURES_JS], which pins it
 * for as long as text is selected, with or without this menu.
 */
internal class SystemSelectionActionModeCallback(
    private val delegate: ActionMode.Callback,
    private val canFilter: Boolean,
    private val target: SelectionMenuTarget,
    private val customActions: List<SelectionActionConfig>,
    private val onTextSelected: () -> Unit,
    /** Must call `done` once it has read the selection; finishing the mode clears it. */
    private val onCustomAction: (action: SelectionActionConfig, done: () -> Unit) -> Unit,
) : ActionMode.Callback2() {
    private var preparing = false
    private var actionPending = false

    override fun onCreateActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        val created = delegate.onCreateActionMode(mode, menu)
        Log.d(
            "SelDiag",
            "create created=$created canFilter=$canFilter before=[${menu.describe()}] " +
                "permitted=${target.permittedIds.map { "0x" + Integer.toHexString(it) }}",
        )
        if (canFilter) enforce(menu)
        Log.d("SelDiag", "create after=[${menu.describe()}]")
        onTextSelected()
        return created
    }

    override fun onPrepareActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        Log.d("SelDiag", "prepare preparing=$preparing quiet=${menuIsQuiet(menu)} menu=[${menu.describe()}]")
        if (preparing) return false
        if (menuIsQuiet(menu)) return false
        preparing = true
        try {
            delegate.onPrepareActionMode(mode, menu)
            Log.d("SelDiag", "prepare delegate=[${menu.describe()}]")
            if (canFilter) enforce(menu)
        } finally {
            preparing = false
        }
        Log.d("SelDiag", "prepare after=[${menu.describe()}]")
        return false
    }

    private fun Menu.describe(): String =
        (0 until size()).joinToString { i ->
            getItem(i).let { "${it.title}=0x${Integer.toHexString(it.itemId)}" }
        }

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem,
    ): Boolean {
        val index = item.itemId - SELECTION_CUSTOM_ID_OFFSET
        val action = customActions.getOrNull(index)
        Log.d("SelDiag", "clicked ${item.title}=0x${Integer.toHexString(item.itemId)} custom=${action?.id}")
        if (action != null) {
            if (actionPending) return true
            actionPending = true
            onCustomAction(action) { mode.finish() }
            return true
        }
        return delegate.onActionItemClicked(mode, item)
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        Log.d("SelDiag", "destroy")
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
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        Log.d(
            "SelDiag",
            "contentRect=$outRect view=${view.width}x${view.height} scroll=${view.scrollX},${view.scrollY} " +
                "screen=${location[0]},${location[1]} shown=${view.isShown}",
        )
    }

    private fun menuIsQuiet(menu: Menu): Boolean {
        if (!canFilter) return menu.size() > 0
        return selectionMenuIsSettled(menu.itemIds(), target)
    }

    private fun enforce(menu: Menu) {
        val stale = menu.itemIds().filter { !target.permittedIds.containsMenuId(it) }
        stale.forEach { menu.removeItem(it) }
        customActions.forEachIndexed { index, action ->
            val id = selectionCustomItemId(index)
            if (menu.findItem(id) == null) {
                menu.add(Menu.NONE, id, Menu.NONE, action.title)
            }
        }
    }
}

/**
 * Selection menu for a selection this page created itself.
 *
 * Chromium only shows its toolbar for a selection its own gesture started.
 * Book mode selects from JavaScript, so Copy and Share are added here and the
 * [SelectionMenuFrameLayout] wrapper still appends the app's custom actions.
 */
internal class BookSelectionActionModeCallback(
    private val contentRect: Rect,
    private val copyLabel: String,
    private val shareLabel: String,
    private val onCopy: (ActionMode) -> Unit,
    private val onShare: (ActionMode) -> Unit,
) : ActionMode.Callback2() {
    override fun onCreateActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean {
        menu.add(Menu.NONE, android.R.id.copy, 0, copyLabel)
        menu.add(Menu.NONE, android.R.id.shareText, 1, shareLabel)
        return true
    }

    override fun onPrepareActionMode(
        mode: ActionMode,
        menu: Menu,
    ): Boolean = false

    override fun onActionItemClicked(
        mode: ActionMode,
        item: MenuItem,
    ): Boolean {
        when (item.itemId) {
            android.R.id.copy -> onCopy(mode)
            android.R.id.shareText -> onShare(mode)
            else -> return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    override fun onGetContentRect(
        mode: ActionMode,
        view: View,
        outRect: Rect,
    ) {
        outRect.set(contentRect)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun decorateSystemSelectionCallback(
    delegate: ActionMode.Callback,
    context: Context,
    onTextSelected: () -> Unit,
    onCustomAction: (action: SelectionActionConfig, done: () -> Unit) -> Unit,
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
    return SystemSelectionActionModeCallback(
        delegate = delegate,
        canFilter = ids.canFilter,
        target = target,
        customActions = actions,
        onTextSelected = onTextSelected,
        onCustomAction = onCustomAction,
    )
}
