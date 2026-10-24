package com.mohamed.dev.ui.storageanalyzer

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the real application graph: Hilt bootstrap, Room seeding, the
 * first global scan, then every top-level tab and the guide.
 */
@RunWith(AndroidJUnit4::class)
class ShowcaseSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun everyTopLevelSurfaceIsReachableAfterBootstrap() {
        waitForText("Volume capacity", timeoutMillis = 120_000)

        openTab("Explorer", "Inventory")
        openTab("History", "Snapshot timeline")
        openTab("Database", "Room notes", substring = true)
        openTab("Lab", "Experiments")

        composeRule.onNodeWithContentDescription("Implementation guide").performClick()
        waitForText("Implementation guide")
    }

    private fun openTab(contentDescription: String, expectedHeading: String, substring: Boolean = false) {
        composeRule.onNodeWithContentDescription(contentDescription).performClick()
        waitForText(expectedHeading, substring = substring)
    }

    private fun waitForText(text: String, timeoutMillis: Long = 30_000, substring: Boolean = false) {
        composeRule.waitUntil(timeoutMillis) {
            hasText(text, substring) || hasText(FAILURE_HEADING, substring = false)
        }
        check(!hasText(FAILURE_HEADING, substring = false)) {
            "bootstrap failed; the app rendered the retry screen instead of '$text'"
        }
        check(hasText(text, substring)) { "expected '$text' to be on screen" }
    }

    private fun hasText(text: String, substring: Boolean): Boolean =
        composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val FAILURE_HEADING = "The demo could not start"
    }
}
