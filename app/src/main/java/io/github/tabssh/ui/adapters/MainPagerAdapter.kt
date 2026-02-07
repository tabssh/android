package io.github.tabssh.ui.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import io.github.tabssh.ui.fragments.*

/**
 * ViewPager2 adapter for MainActivity's 5 tabs
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private val fragments = listOf(
        FrequentConnectionsFragment.newInstance(),
        ConnectionsFragment.newInstance(),
        IdentitiesFragment.newInstance(),
        PerformanceFragment.newInstance(),
        HypervisorsFragment.newInstance()
    )

    private val tabTitles = listOf(
        "Frequent",
        "Connections",
        "Identities",
        "Performance",
        "Hypervisors"
    )

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]

    fun getTabTitle(position: Int): String = tabTitles.getOrElse(position) { "" }
}
