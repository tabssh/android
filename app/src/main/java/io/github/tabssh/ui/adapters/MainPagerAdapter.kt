package io.github.tabssh.ui.adapters

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.github.tabssh.R
import io.github.tabssh.ui.fragments.*

/**
 * ViewPager2 adapter for MainActivity's 5 tabs.
 *
 * Issue #158 — fragments are constructed lazily inside `createFragment`.
 * The previous `private val fragments = listOf(...)` field built all
 * five fragment instances eagerly at adapter construction time, which
 * defeated FragmentStateAdapter's lazy-creation contract and forced
 * every fragment's init onto the main thread during MainActivity.onCreate
 * — major contributor to the multi-second cold-start main-thread freeze.
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    // Mobile-first labels — long words don't fit 5 tabs on a phone.
    // "Connections" → "Hosts", "Performance" → "Stats". "Infra" combines
    // Hypervisors and Cloud Accounts in sub-tabs; "Auth" (formerly
    // "Identities") combines SSH/VMs/VNC/Keys credentials in sub-tabs.
    // Auth is last since it's the least-visited main tab.
    private val tabTitles = listOf(
        R.string.main_tab_frequent,
        R.string.main_tab_hosts,
        R.string.main_tab_stats,
        R.string.main_tab_infra,
        R.string.main_tab_auth
    )

    private val context: Context = activity

    override fun getItemCount(): Int = tabTitles.size

    override fun createFragment(position: Int): Fragment = when (position) {
        MainTab.FREQUENT -> FrequentConnectionsFragment.newInstance()
        MainTab.HOSTS -> ConnectionsFragment.newInstance()
        MainTab.STATS -> PerformanceFragment.newInstance()
        MainTab.INFRA -> InfraFragment.newInstance()
        MainTab.AUTH -> AuthFragment.newInstance()
        else -> error("Invalid tab position $position")
    }

    fun getTabTitle(position: Int): String =
        tabTitles.getOrNull(position)?.let(context::getString) ?: ""
}
