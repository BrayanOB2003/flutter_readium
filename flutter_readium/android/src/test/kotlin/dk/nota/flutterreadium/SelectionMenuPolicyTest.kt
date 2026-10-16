package dk.nota.flutterreadium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SelectionMenuPolicyTest {
    private val copy = 1
    private val share = 2
    private val selectAll = 3
    private val webSearch = 4
    private val highlight = selectionCustomItemId(0)

    @Test
    fun `menu is quiet once copy and share are the only system items`() {
        val target =
            selectionMenuTarget(
                allowedNames = setOf("copy", "share"),
                copyIds = setOf(copy),
                shareIds = setOf(share),
                selectAllIds = setOf(selectAll),
                customIds = emptySet(),
            )

        assertTrue(selectionMenuIsSettled(setOf(copy, share), target))
        assertFalse(selectionMenuIsSettled(setOf(copy, share, selectAll), target))
        assertFalse(selectionMenuIsSettled(setOf(copy, share, webSearch), target))
        assertFalse(selectionMenuIsSettled(emptySet(), target))
    }

    @Test
    fun `null allowance keeps select all and still drops anything else`() {
        val target =
            selectionMenuTarget(
                allowedNames = null,
                copyIds = setOf(copy),
                shareIds = setOf(share),
                selectAllIds = setOf(selectAll),
                customIds = emptySet(),
            )

        assertTrue(selectionMenuIsSettled(setOf(copy, share, selectAll), target))
        assertTrue(selectionMenuIsSettled(setOf(copy, share), target))
        assertFalse(selectionMenuIsSettled(setOf(copy, share, webSearch), target))
    }

    @Test
    fun `empty allowance leaves only custom actions`() {
        val target =
            selectionMenuTarget(
                allowedNames = emptySet(),
                copyIds = setOf(copy),
                shareIds = setOf(share),
                selectAllIds = setOf(selectAll),
                customIds = setOf(highlight),
            )

        assertEquals(emptyList<Set<Int>>(), target.requiredGroups)
        assertTrue(selectionMenuIsSettled(setOf(highlight), target))
        assertFalse(selectionMenuIsSettled(setOf(copy, highlight), target))
        assertFalse(selectionMenuIsSettled(emptySet(), target))
    }

    @Test
    fun `either resource id for copy counts as copy`() {
        val chromiumCopy = 11
        val frameworkCopy = 12
        val target =
            selectionMenuTarget(
                allowedNames = setOf("copy", "share"),
                copyIds = setOf(chromiumCopy, frameworkCopy),
                shareIds = setOf(share),
                selectAllIds = setOf(selectAll),
                customIds = emptySet(),
            )

        assertTrue(selectionMenuIsSettled(setOf(chromiumCopy, share), target))
        assertFalse(selectionMenuIsSettled(setOf(share), target))
    }

    @Test
    fun `webview ids match whatever package byte the app assigns`() {
        val lookedUpCopy = 0x7f01010a
        val lookedUpShare = 0x7f010111
        val runtimeCopy = 0x0201010a
        val runtimeShare = 0x02010111
        val runtimeTranslate = 0x02010113
        val target =
            selectionMenuTarget(
                allowedNames = setOf("copy", "share"),
                copyIds = setOf(lookedUpCopy, android.R.id.copy),
                shareIds = setOf(lookedUpShare, android.R.id.shareText),
                selectAllIds = emptySet(),
                customIds = setOf(highlight),
            )

        assertTrue(selectionMenuIsSettled(setOf(runtimeCopy, runtimeShare, highlight), target))
        assertFalse(selectionMenuIsSettled(setOf(runtimeCopy, runtimeShare, runtimeTranslate, highlight), target))
        assertFalse(selectionMenuIsSettled(setOf(runtimeShare, highlight), target))
    }

    @Test
    fun `only standalone webview ids ignore the package byte`() {
        assertTrue(menuIdMatches(0x0201010a, 0x7f01010a))
        assertFalse(menuIdMatches(0x0201010a, 0x0301010a))
        assertFalse(menuIdMatches(0x02f10000, selectionCustomItemId(0)))
        assertTrue(menuIdMatches(android.R.id.copy, android.R.id.copy))
    }

    @Test
    fun `custom action must be present before the menu is quiet`() {
        val target =
            selectionMenuTarget(
                allowedNames = setOf("copy", "share"),
                copyIds = setOf(copy),
                shareIds = setOf(share),
                selectAllIds = setOf(selectAll),
                customIds = setOf(highlight),
            )

        assertFalse(selectionMenuIsSettled(setOf(copy, share), target))
        assertTrue(selectionMenuIsSettled(setOf(copy, share, highlight), target))
    }
}
