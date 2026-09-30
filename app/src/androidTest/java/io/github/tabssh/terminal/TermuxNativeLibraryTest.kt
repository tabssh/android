package io.github.tabssh.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermuxNativeLibraryTest {

    @Test
    fun bundledNativeLibraryLoadsInTheAppProcess() {
        System.loadLibrary("termux")
    }
}
