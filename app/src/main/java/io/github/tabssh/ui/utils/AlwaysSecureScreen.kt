package io.github.tabssh.ui.utils

/**
 * Marks an activity that always blocks screenshots and screen recording
 * (FLAG_SECURE), whatever the "prevent screenshots" setting says. The
 * application-wide window flag pass never clears FLAG_SECURE on these.
 */
interface AlwaysSecureScreen
