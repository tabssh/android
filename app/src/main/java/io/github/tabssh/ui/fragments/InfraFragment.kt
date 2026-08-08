package io.github.tabssh.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
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
                0 -> getString(R.string.docker_tab_title)
                1 -> getString(R.string.infra_tab_hypervisors)
                2 -> getString(R.string.infra_tab_cloud)
                else -> ""
            }
        }.attach()
    }

    private inner class InfraPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> DockerHostsFragment.newInstance()
            1 -> HypervisorsFragment.newInstance()
            2 -> CloudAccountsFragment.newInstance()
            else -> error("Invalid Infra sub-tab position $position")
        }
    }

    companion object {
        fun newInstance() = InfraFragment()
    }
}
