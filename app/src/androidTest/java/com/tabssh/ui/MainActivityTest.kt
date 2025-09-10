package com.tabssh.ui

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import com.tabssh.R
import com.tabssh.ui.activities.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for MainActivity UI
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)
    
    @Test
    fun testMainActivityLaunches() {
        // Verify main components are displayed
        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.text_welcome))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.fab_add_connection))
            .check(matches(isDisplayed()))
    }
    
    @Test
    fun testQuickConnectForm() {
        // Test quick connect form visibility and interaction
        onView(withId(R.id.edit_quick_host))
            .check(matches(isDisplayed()))
            .perform(typeText("test.example.com"))
        
        onView(withId(R.id.edit_quick_port))
            .check(matches(withText("22")))
            .perform(clearText(), typeText("2222"))
        
        onView(withId(R.id.edit_quick_username))
            .check(matches(isDisplayed()))
            .perform(typeText("testuser"))
        
        onView(withId(R.id.btn_quick_connect))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))
    }
    
    @Test
    fun testFABClickable() {
        onView(withId(R.id.fab_add_connection))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
            .perform(click())
        
        // Would test navigation to connection edit activity
        // Requires proper navigation setup
    }
    
    @Test
    fun testToolbarDisplayed() {
        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))
            .check(matches(hasDescendant(withText("TabSSH"))))
    }
    
    @Test
    fun testEmptyConnectionsMessage() {
        // When no connections are saved, empty message should be shown
        onView(withId(R.id.text_empty_connections))
            .check(matches(isDisplayed()))
            .check(matches(withText(containsString("No saved connections"))))
    }
    
    @Test
    fun testAccessibilityProperties() {
        // Test accessibility content descriptions
        onView(withId(R.id.fab_add_connection))
            .check(matches(hasContentDescription()))
        
        // Test that interactive elements are focusable
        onView(withId(R.id.btn_quick_connect))
            .check(matches(isFocusable()))
        
        onView(withId(R.id.fab_add_connection))
            .check(matches(isFocusable()))
    }
    
    @Test 
    fun testQuickConnectValidation() {
        // Test empty host validation
        onView(withId(R.id.edit_quick_host))
            .perform(clearText())
        
        onView(withId(R.id.btn_quick_connect))
            .perform(click())
        
        // Should show validation error (would need error message checking)
        
        // Test invalid port validation
        onView(withId(R.id.edit_quick_port))
            .perform(clearText(), typeText("99999"))
        
        onView(withId(R.id.btn_quick_connect))
            .perform(click())
        
        // Should show port validation error
    }
}