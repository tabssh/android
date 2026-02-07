package io.github.tabssh.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import io.github.tabssh.R
import io.github.tabssh.ui.activities.KeyManagementActivity

/**
 * Fragment for managing users and SSH keys (identities)
 */
class IdentitiesFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_identities, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        view.findViewById<Button>(R.id.btn_manage_keys)?.setOnClickListener {
            startActivity(Intent(requireContext(), KeyManagementActivity::class.java))
        }
    }

    companion object {
        fun newInstance() = IdentitiesFragment()
    }
}
