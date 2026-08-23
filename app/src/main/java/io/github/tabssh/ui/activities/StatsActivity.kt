package io.github.tabssh.ui.activities

import android.os.Bundle
import io.github.tabssh.R
import io.github.tabssh.databinding.ActivityStatsBinding
import io.github.tabssh.ui.fragments.PerformanceFragment

/**
 * Host screen for the Stats tab (server performance monitoring), relocated
 * from the main tab strip into the nav drawer's Insights group. Hosts the
 * unmodified [PerformanceFragment] — this class supplies chrome only, no
 * UI/UX changes to the fragment itself.
 */
class StatsActivity : TabSSHActivity() {

    private lateinit var binding: ActivityStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBar.toolbar)
        supportActionBar?.setTitle(R.string.main_tab_stats)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.stats_fragment_container, PerformanceFragment.newInstance())
                .commit()
        }
    }
}
