package com.xndev.littlejournal.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.xndev.littlejournal.storage.inMemoryRepository
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Layout on a canvas far wider than a phone.
 *
 * The app declares iPad support, and for a long time nobody had run it on one.
 * It did not crash — it stretched: a compose box thirteen inches wide, and
 * calendar cells inflated into empty squares. Nothing about the state was
 * wrong, which is exactly why no other kind of test could see it.
 *
 * Driven at 1400x1000, comfortably wider than the cap, so a regression shows
 * up as content spanning the whole canvas again.
 */
@OptIn(ExperimentalTestApi::class)
class TabletLayoutTest {

    @Test
    fun `the compose box stops well short of a wide canvas`() =
        runDesktopComposeUiTest(WIDE, TALL) {
            setContent { App(inMemoryRepository(), FakeTranscriber()) }

            val box = onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
            val width = box.right - box.left

            assertTrue(
                width <= READABLE_MAX,
                "the entry box grew to $width — a canvas rather than a measure",
            )
        }

    @Test
    fun `the month grid stops well short of a wide canvas`() =
        runDesktopComposeUiTest(WIDE, TALL) {
            setContent { App(inMemoryRepository(), FakeTranscriber()) }
            onNodeWithText("Calendar").performClick()

            // Monday's heading marks the grid's left edge. Centered inside a
            // 1400-wide canvas it should sit around (1400 - 640) / 2, near
            // 380dp; stretched full width it sits at the screen's own padding,
            // around 20dp. Asserting merely "> 0" was the first version of
            // this test, and it passed against the broken layout -- the
            // padding alone satisfied it.
            val monday = onNodeWithText("M").getUnclippedBoundsInRoot()

            assertTrue(
                monday.left > CENTRED_MARGIN_FLOOR,
                "the grid starts at ${monday.left}, so it is spanning the full width",
            )
        }
}

private const val WIDE = 1400
private const val TALL = 1000

/** The cap in App.kt, plus the padding the screens add inside it. */
private val READABLE_MAX = 700.dp

/** Far above a screen's own padding, far below a true centered margin. */
private val CENTRED_MARGIN_FLOOR = 200.dp
