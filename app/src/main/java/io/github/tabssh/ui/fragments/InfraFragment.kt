package io.github.tabssh.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import io.github.tabssh.R

/**
 * Combined "Infra" tab that hosts Docker Hosts, Hypervisors, and Cloud
 * Accounts as sub-tabs within a single main-tab slot. This replaces the
 * standalone "VMs" tab (HypervisorsFragment) and the Cloud Accounts drawer
 * entry; Docker was added in Phase 4 and leads the order as the most-used
 * section.
 */
class InfraFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_infra, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val viewPager = view.findViewById<ViewPager2>(R.id.view_pager_infra)
        val tabLayout = view.findViewById<TabLayout>(R.id.tab_layout_infra)

        viewPager.adapter = InfraPagerAdapter(this)

        // Disable swipe-to-switch on the inner pager — the outer main-tab
        // ViewPager2 handles horizontal swipes, so letting the inner pager
        // compete for touch events causes scrolling to get stuck. Tab taps
        // on the TabLayout are the intended navigation mechanism here.
        viewPager.isUserInputEnabled = false

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.container_manager_tab_containers)
                1 -> getString(R.string.infra_tab_hypervisors)
                2 -> getString(R.string.infra_tab_cloud)
                else -> ""
            }
        }.attach()

        // Remember which sub-tab the user last had open, so returning to
        // Infra from another main tab (or relaunching the app) restores it
        // instead of always resetting to sub-tab 0.
        //
        // Note: this deliberately does NOT persist drill-down state inside
        // detail Activities (ContainerHostManagerActivity, ProxmoxManagerActivity,
        // CloudAccountManagerActivity, etc.) — those are separate Activities on
        // the normal back stack, not reachable while this tab UI is in the
        // foreground, so system Back and task-resume already restore them
        // correctly once sub-tab position is preserved here. No app code path
        // clears the back stack into a detail Activity, so no extra
        // persistence is needed for that case.
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val savedSubTab = prefs.getInt(PREF_LAST_SUB_TAB, 0)
        viewPager.setCurrentItem(savedSubTab, false)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                prefs.edit().putInt(PREF_LAST_SUB_TAB, position).apply()
            }
        })
    }

    private inner class InfraPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> ContainerHostsFragment.newInstance()
            1 -> HypervisorsFragment.newInstance()
            2 -> CloudAccountsFragment.newInstance()
            else -> error("Invalid Infra sub-tab position $position")
        }
    }

    companion object {
        private const val PREF_LAST_SUB_TAB = "ui_last_infra_sub_tab_index"
        fun newInstance() = InfraFragment()
    }
}
