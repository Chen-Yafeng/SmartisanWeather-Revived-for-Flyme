package com.smartisan.weather.ui.startup

import com.smartisan.weather.ui.saveVerificationScreenshot
import android.view.KeyEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val NOTICE_TAG = "notice"

@RunWith(AndroidJUnit4::class)
class WeatherNoticeDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun noticeDispatchesPositiveActionOnlyOnce() {
        var accepted = 0
        var rejected = 0
        compose.setContent {
            WeatherNoticeDialog(
                title = "使用说明",
                message = AnnotatedString("请阅读本应用的数据来源与定位说明。"),
                positiveText = "继续使用",
                negativeText = "退出",
                onPositive = { accepted++ },
                onNegative = { rejected++ },
                tagPrefix = NOTICE_TAG,
            )
        }
        compose.onNodeWithTag("${NOTICE_TAG}_positive").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, accepted)
            assertEquals(0, rejected)
        }
        compose.onNodeWithTag("${NOTICE_TAG}_positive").performClick()
        compose.onNodeWithTag("${NOTICE_TAG}_positive").assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, accepted)
            assertEquals(0, rejected)
        }
    }

    @Test
    fun noticeDispatchesNegativeAction() {
        var accepted = 0
        var rejected = 0
        compose.setContent {
            WeatherNoticeDialog(
                title = "使用说明",
                message = AnnotatedString("请阅读本应用的数据来源与定位说明。"),
                positiveText = "继续使用",
                negativeText = "退出",
                onPositive = { accepted++ },
                onNegative = { rejected++ },
                tagPrefix = NOTICE_TAG,
            )
        }
        compose.onNodeWithTag("${NOTICE_TAG}_negative").performClick()
        compose.runOnIdle {
            assertEquals(0, accepted)
            assertEquals(1, rejected)
        }
    }

    @Test
    fun systemBackDispatchesNegativeAction() {
        var accepted = 0
        var rejected = 0
        compose.setContent {
            WeatherNoticeDialog(
                title = "使用说明",
                message = AnnotatedString("请阅读本应用的数据来源与定位说明。"),
                positiveText = "继续使用",
                negativeText = "退出",
                onPositive = { accepted++ },
                onNegative = { rejected++ },
                tagPrefix = NOTICE_TAG,
            )
        }
        compose.onNodeWithTag(NOTICE_TAG).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.runOnIdle {
            assertEquals(0, accepted)
            assertEquals(1, rejected)
        }
    }

    @Test
    fun longNoticeKeepsBothActionsVisible() {
        var accepted = 0
        compose.setContent {
            WeatherNoticeDialog(
                title = "使用说明",
                message = AnnotatedString("请阅读本应用的数据来源与定位说明。\n".repeat(100)),
                positiveText = "继续使用",
                negativeText = "退出",
                onPositive = { accepted++ },
                onNegative = {},
                tagPrefix = "long_notice",
            )
        }
        compose.onNodeWithTag("long_notice_negative").assertIsDisplayed()
        compose.onNodeWithTag("long_notice_positive").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, accepted) }
    }

    @Test
    fun locationSettingsRequiresItsOwnConfirmation() {
        var settingsOpened = 0
        var cancelled = 0
        compose.setContent {
            WeatherLocationDialog(
                onCancel = { cancelled++ },
                onSettings = { settingsOpened++ },
            )
        }
        compose.onNodeWithTag("location_notice_positive").performClick()
        compose.runOnIdle {
            assertEquals(1, settingsOpened)
            assertEquals(0, cancelled)
        }
    }
    @Test
    fun approximateLocationRemainsAnExplicitUsableChoice() {
        var approximate = 0
        var settings = 0
        compose.setContent {
            WeatherLocationDialog(
                notice = LocationNotice.PRECISION,
                onCancel = { approximate++ },
                onSettings = { settings++ },
            )
        }
        compose.onNodeWithTag("location_notice_positive").assertIsDisplayed()
        compose.onNodeWithTag("location_notice_negative").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, approximate)
            assertEquals(0, settings)
        }
        saveVerificationScreenshot("location-precision")
    }

    @Test
    fun deniedPermissionCanBeCancelledWithoutOpeningSettings() {
        var cancelled = 0
        var settings = 0
        compose.setContent {
            WeatherLocationDialog(
                notice = LocationNotice.PERMISSION,
                onCancel = { cancelled++ },
                onSettings = { settings++ },
            )
        }
        compose.onNodeWithTag("location_notice_negative").performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertEquals(0, settings)
        }
        saveVerificationScreenshot("location-permission")
    }

}
