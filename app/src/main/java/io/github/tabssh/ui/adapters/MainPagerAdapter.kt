package io.github.tabssh.ui.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
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
    // "Connections" → "Hosts", "Performance" → "Stats", "Hypervisors" →
    // "VMs". Tab content is unchanged; the underlying fragments still
    // refer to themselves with the longer names internally.
    private val tabTitles = listOf(
        "Frequent",
        "Hosts",
        "Identities",
        "Stats",
        "VMs"
    )

    override fun getItemCount(): Int = tabTitles.size

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> FrequentConnectionsFragment.newInstance()
        1 -> ConnectionsFragment.newInstance()
        2 -> IdentitiesFragment.newInstance()
        3 -> PerformanceFragment.newInstance()
        4 -> HypervisorsFragment.newInstance()
        else -> error("Invalid tab position $position")
    }

    fun getTabTitle(position: Int): String = tabTitles.getOrElse(position) { "" }
}
