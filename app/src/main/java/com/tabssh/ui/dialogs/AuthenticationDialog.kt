package com.tabssh.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tabssh.R
import com.tabssh.TabSSHApplication
import com.tabssh.storage.database.entities.ConnectionProfile
import com.tabssh.utils.logging.Logger
import kotlinx.coroutines.launch

/**
 * Authentication dialog for SSH connections
 * Handles password input and biometric authentication
 */
class AuthenticationDialog : DialogFragment() {
    
    companion object {
        private const val ARG_CONNECTION_ID = "connection_id"
        private const val ARG_CONNECTION_NAME = "connection_name"
        private const val ARG_AUTH_TYPE = "auth_type"
        
        fun show(
            activity: FragmentActivity,
            connectionProfile: ConnectionProfile,
            onAuthenticated: (String?) -> Unit
        ) {
            val dialog = AuthenticationDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONNECTION_ID, connectionProfile.id)
                    putString(ARG_CONNECTION_NAME, connectionProfile.getDisplayName())
                    putString(ARG_AUTH_TYPE, connectionProfile.authType)
                }
                this.onAuthenticated = onAuthenticated
            }
            
            dialog.show(activity.supportFragmentManager, "AuthenticationDialog")
        }
    }
    
    private var onAuthenticated: ((String?) -> Unit)? = null
    private lateinit var app: TabSSHApplication
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        app = requireActivity().application as TabSSHApplication
        
        val connectionName = arguments?.getString(ARG_CONNECTION_NAME) ?: "Unknown"
        val authType = arguments?.getString(ARG_AUTH_TYPE) ?: "PASSWORD"
        
        val view = requireActivity().layoutInflater.inflate(R.layout.dialog_authentication, null)
        val passwordLayout = view.findViewById<TextInputLayout>(R.id.layout_password)
        val passwordEdit = view.findViewById<TextInputEditText>(R.id.edit_password)
        
        return AlertDialog.Builder(requireContext())
            .setTitle("Connect to $connectionName")
            .setMessage("Enter your ${getAuthTypeDisplayName(authType)} to connect:")
            .setView(view)
            .setPositiveButton("Connect") { _, _ ->
                val password = passwordEdit.text?.toString()
                handleAuthentication(password)
            }
            .setNegativeButton("Cancel") { _, _ ->
                onAuthenticated?.invoke(null)
            }
            .setNeutralButton("Use Biometric") { _, _ ->
                if (authType == "BIOMETRIC" || app.securePasswordManager.isBiometricAvailable()) {
                    handleBiometricAuthentication()
                } else {
                    Logger.w("AuthenticationDialog", "Biometric not available")
                    onAuthenticated?.invoke(null)
                }
            }
            .create()
    }
    
    private fun getAuthTypeDisplayName(authType: String): String {
        return when (authType) {
            "PASSWORD" -> "password"
            "PUBLIC_KEY" -> "SSH key passphrase"
            "KEYBOARD_INTERACTIVE" -> "credentials"
            else -> "password"
        }
    }
    
    private fun handleAuthentication(password: String?) {
        if (password.isNullOrBlank()) {
            onAuthenticated?.invoke(null)
            return
        }
        
        val connectionId = arguments?.getString(ARG_CONNECTION_ID)
        if (connectionId != null) {
            // Store password if user wants to save it
            lifecycleScope.launch {
                try {
                    val storageLevel = com.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.SESSION_ONLY
                    app.securePasswordManager.storePassword(connectionId, password, storageLevel)
                    
                    onAuthenticated?.invoke(password)
                    
                } catch (e: Exception) {
                    Logger.e("AuthenticationDialog", "Failed to store password", e)
                    onAuthenticated?.invoke(password) // Still try to authenticate
                }
            }
        } else {
            onAuthenticated?.invoke(password)
        }
    }
    
    private fun handleBiometricAuthentication() {
        val connectionId = arguments?.getString(ARG_CONNECTION_ID) ?: return
        
        if (activity is FragmentActivity) {
            lifecycleScope.launch {
                try {
                    val password = app.securePasswordManager.retrievePasswordWithBiometrics(
                        connectionId,
                        activity as FragmentActivity
                    )
                    
                    onAuthenticated?.invoke(password)
                    
                } catch (e: Exception) {
                    Logger.e("AuthenticationDialog", "Biometric authentication failed", e)
                    onAuthenticated?.invoke(null)
                }
            }
        }
    }
}