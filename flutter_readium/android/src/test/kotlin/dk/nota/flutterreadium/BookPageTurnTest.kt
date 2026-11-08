package dk.nota.flutterreadium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class BookPageTurnTest {
    @Test
    fun `a quick flick readium already accepts is left alone`() {
        assertNull(correct(velocity = -2_000, initialVelocity = -800, deltaX = -100, dragged = -100))
        assertNull(correct(velocity = -2_000, initialVelocity = -800, deltaX = -700, dragged = 700))
        assertNull(correct(velocity = 2_000, initialVelocity = 800, deltaX = 100, dragged = -100))
    }

    @Test
    fun `a drag that stops past halfway stays on the new page`() {
        assertEquals(PageTurnTarget.Page(3), correct(dragged = 501, deltaX = -501))
        assertEquals(PageTurnTarget.Page(1), correct(dragged = -501, deltaX = 501))
    }

    @Test
    fun `a drag that stops short of halfway still returns`() {
        assertNull(correct(dragged = 500, deltaX = -500))
        assertNull(correct(dragged = 400, deltaX = -400))
        assertNull(correct(dragged = 80, deltaX = -80))
    }

    @Test
    fun `a zero starting velocity does not cancel a real flick`() {
        assertEquals(
            PageTurnTarget.Page(3),
            correct(velocity = -2_000, initialVelocity = 0, deltaX = -100, dragged = 100),
        )
    }

    @Test
    fun `a small reversal at the end keeps a drag that finished past halfway`() {
        assertEquals(
            PageTurnTarget.Page(3),
            correct(velocity = -100, initialVelocity = 500, dragged = 700, deltaX = -700),
        )
    }

    @Test
    fun `the last page of a file turns on a slow drag past halfway`() {
        assertEquals(
            PageTurnTarget.NextResource,
            correct(startPage = 9, dragged = 0, deltaX = -600, atEnd = true, pageCount = 10),
        )
        assertEquals(
            PageTurnTarget.NextResource,
            correct(startPage = 9, dragged = 80, deltaX = -600, atEnd = true, pageCount = 10),
        )
        assertNull(correct(startPage = 9, dragged = 0, deltaX = -200, atEnd = true, pageCount = 10))
    }

    @Test
    fun `the first page of a file turns backward on a slow drag past halfway`() {
        assertEquals(
            PageTurnTarget.PreviousResource,
            correct(startPage = 0, dragged = 0, deltaX = 600, atStart = true, pageCount = 10),
        )
    }

    @Test
    fun `a single page file turns to the neighbouring file`() {
        assertEquals(
            PageTurnTarget.NextResource,
            correct(
                startPage = 0,
                dragged = 0,
                deltaX = -700,
                atStart = true,
                atEnd = true,
                pageCount = 1,
            ),
        )
        assertEquals(
            PageTurnTarget.PreviousResource,
            correct(
                startPage = 0,
                dragged = 0,
                deltaX = 700,
                atStart = true,
                atEnd = true,
                pageCount = 1,
            ),
        )
    }

    @Test
    fun `a mostly vertical drag does not turn the page`() {
        assertNull(correct(dragged = 700, deltaX = -600, deltaY = 800))
    }

    @Test
    fun `scroll mode and an active selection are left alone`() {
        assertNull(correct(dragged = 700, deltaX = -700, scrollMode = true))
        assertNull(correct(dragged = 700, deltaX = -700, selecting = true))
    }

    private fun correct(
        startPage: Int = 2,
        velocity: Int = 0,
        initialVelocity: Int = 0,
        deltaX: Int = 0,
        deltaY: Int = 0,
        dragged: Int = 0,
        pageWidth: Int = 1_000,
        pageCount: Int = 10,
        atStart: Boolean = false,
        atEnd: Boolean = false,
        scrollMode: Boolean = false,
        selecting: Boolean = false,
    ) = pageTurnCorrection(
        startPage = startPage,
        velocity = velocity,
        initialVelocity = initialVelocity,
        deltaX = deltaX,
        deltaY = deltaY,
        dragged = dragged,
        pageWidth = pageWidth,
        pageCount = pageCount,
        atStart = atStart,
        atEnd = atEnd,
        flingDistance = 70,
        minFlingVelocity = 1_200,
        scrollMode = scrollMode,
        selecting = selecting,
    )
}
