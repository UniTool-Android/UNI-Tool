package ahk.unitool.gsm.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import ahk.unitool.gsm.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivitySmokeTest {

    @Test
    fun launchesAndShowsRefresh() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnRefresh)).check(matches(isDisplayed()))
            onView(withId(R.id.btnShell)).check(matches(isDisplayed()))
            onView(withId(R.id.logView)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun refreshButtonHasLabel() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.btnRefresh)).check(matches(withText("Refresh")))
        }
    }
}
