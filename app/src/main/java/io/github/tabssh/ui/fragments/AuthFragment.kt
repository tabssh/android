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
 * Combined "Auth" tab that hosts SSH Identities, VM Credentials, VNC
 * Identities, and SSH Keys as sub-tabs within a single main-tab slot.
 * Replaces the standalone "Identities" tab (IdentitiesFragment), which
 * stacked all four sections into one long scrolling screen.
 */
class AuthFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_auth, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val viewPager = view.findViewById<ViewPager2>(R.id.view_pager_auth)
        val tabLayout = view.findViewById<TabLayout>(R.id.tab_layout_auth)

        viewPager.adapter = AuthPagerAdapter(this)

        // Disable swipe-to-switch on the inner pager — the outer main-tab
        // ViewPager2 handles horizontal swipes, so letting the inner pager
        // compete for touch events causes scrolling to get stuck. Tab taps
        // on the TabLayout are the intended navigation mechanism here.
        viewPager.isUserInputEnabled = false

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.auth_tab_ssh)
                1 -> getString(R.string.auth_tab_vms)
                2 -> getString(R.string.auth_tab_vnc)
                3 -> getString(R.string.auth_tab_keys)
                else -> ""
            }
        }.attach()

        // Remember which sub-tab the user last had open, so returning to
        // Auth from another main tab (or relaunching the app) restores it
        // instead of always resetting to SSH (sub-tab 0). MainActivity also
        // writes into this same preference key when a "start_sub_tab" Intent
        // extra targets a specific sub-tab (e.g. Configure OCI -> VMs).
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

    private inner class AuthPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> AuthSshFragment.newInstance()
            1 -> AuthVmsFragment.newInstance()
            2 -> AuthVncFragment.newInstance()
            3 -> AuthKeysFragment.newInstance()
            else -> error("Invalid Auth sub-tab position $position")
        }
    }

    companion object {
        const val PREF_LAST_SUB_TAB = "ui_last_auth_sub_tab_index"
        fun newInstance() = AuthFragment()
    }
}
