package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ui.adapters.MainPagerAdapter
import io.github.tabssh.utils.logging.Logger

/**
 * Main activity with 5-tab JuiceSSH-inspired layout
 * Tabs: Frequent | Connections | Identities | Performance | Hypervisors
 */
class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var app: TabSSHApplication
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var fab: FloatingActionButton
    private lateinit var pagerAdapter: MainPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Logger.d("MainActivity", "onCreate - New 5-tab layout")

        app = application as TabSSHApplication

        // Setup toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "TabSSH"

        // Setup drawer
        drawerLayout = findViewById(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        navView.setNavigationItemSelectedListener(this)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // Setup ViewPager2 + TabLayout
        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.tab_layout)
        fab = findViewById(R.id.fab_add)

        pagerAdapter = MainPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = pagerAdapter.getTabTitle(position)
        }.attach()

        // FAB action
        fab.setOnClickListener {
            val currentTab = viewPager.currentItem
            when (currentTab) {
                0 -> {
                    // Frequent tab - no add action (read-only)
                }
                1 -> {
                    // Connections tab - add new connection
                    startActivity(Intent(this, ConnectionEditActivity::class.java))
                }
                2 -> {
                    // Identities tab - navigate to key management
                    startActivity(Intent(this, KeyManagementActivity::class.java))
                }
                3 -> {
                    // Performance tab - placeholder
                }
                4 -> {
                    // Hypervisors tab - placeholder
                }
            }
        }

        // Update FAB visibility based on current tab
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Show FAB only on Connections and Identities tabs
                fab.visibility = if (position == 1 || position == 2) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            }
        })

        // Set initial FAB visibility
        fab.visibility = android.view.View.VISIBLE

        // Handle back press for drawer
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        Logger.i("MainActivity", "MainActivity created successfully")
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_search -> {
                // Search is handled within ConnectionsFragment
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            R.id.nav_port_forwarding -> {
                // TODO: Navigate to Port Forwarding settings
            }
            R.id.nav_snippets -> {
                // TODO: Navigate to Snippets Library
            }
            R.id.nav_import_export_connections -> {
                // TODO: Show import/export dialog
            }
            R.id.nav_import_ssh_config -> {
                // TODO: Show SSH config import dialog
            }
            R.id.nav_about -> {
                // TODO: Show about dialog
            }
            R.id.nav_help -> {
                // TODO: Show help
            }
        }
        
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onResume() {
        super.onResume()
        // Fragments will handle their own data refreshing
    }
}
