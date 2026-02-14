package io.github.tabssh.sync.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages Google Drive authentication using OAuth 2.0
 */
class DriveAuthenticationManager(private val context: Context) {

    companion object {
        private const val TAG = "DriveAuthManager"
        const val REQUEST_CODE_SIGN_IN = 9001
    }

    private var googleSignInClient: GoogleSignInClient? = null
    private var driveService: Drive? = null
    private var currentAccount: GoogleSignInAccount? = null

    /**
     * Initialize Google Sign-In client
     */
    fun initialize() {
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()

        googleSignInClient = GoogleSignIn.getClient(context, signInOptions)

        checkExistingSignIn()
    }

    /**
     * Check if user is already signed in
     */
    private fun checkExistingSignIn() {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null) {
            currentAccount = account
            initializeDriveService(account)
            Logger.d(TAG, "Found existing sign-in: ${account.email}")
        }
    }

    /**
     * Start sign-in flow (deprecated - use getSignInIntent() with Activity Result API)
     */
    @Deprecated("Use getSignInIntent() with Activity Result API instead")
    fun signIn(activity: Activity) {
        val signInIntent = googleSignInClient?.signInIntent
        activity.startActivityForResult(signInIntent, REQUEST_CODE_SIGN_IN)
        Logger.d(TAG, "Starting Google Sign-In flow (legacy)")
    }

    /**
     * Get sign-in intent for use with Activity Result API
     */
    fun getSignInIntent(): Intent? {
        // Initialize if not already done
        if (googleSignInClient == null) {
            initialize()
        }
        return googleSignInClient?.signInIntent
    }

    /**
     * Handle sign-in result from activity
     */
    suspend fun handleSignInResult(data: Intent?): SignInResult = withContext(Dispatchers.IO) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            if (account != null) {
                currentAccount = account
                initializeDriveService(account)
                Logger.d(TAG, "Sign-in successful: ${account.email}")
                SignInResult.Success(account)
            } else {
                Logger.e(TAG, "Sign-in failed: account is null")
                SignInResult.Error("Sign-in failed: no account returned")
            }
        } catch (e: ApiException) {
            Logger.e(TAG, "Sign-in failed with API exception: ${e.statusCode}", e)
            SignInResult.Error("Sign-in failed: ${e.localizedMessage}")
        } catch (e: Exception) {
            Logger.e(TAG, "Sign-in failed with exception", e)
            SignInResult.Error("Sign-in failed: ${e.message}")
        }
    }

    /**
     * Sign out current user
     */
    suspend fun signOut(): Boolean = withContext(Dispatchers.IO) {
        try {
            googleSignInClient?.signOut()?.await()
            currentAccount = null
            driveService = null
            Logger.d(TAG, "Sign-out successful")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Sign-out failed", e)
            false
        }
    }

    /**
     * Revoke access (complete disconnection)
     */
    suspend fun revokeAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            googleSignInClient?.revokeAccess()?.await()
            currentAccount = null
            driveService = null
            Logger.d(TAG, "Access revoked successfully")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to revoke access", e)
            false
        }
    }

    /**
     * Initialize Drive service with authenticated account
     */
    private fun initializeDriveService(account: GoogleSignInAccount) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                listOf(DriveScopes.DRIVE_APPDATA)
            )
            credential.selectedAccount = account.account

            driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName("TabSSH")
                .build()

            Logger.d(TAG, "Drive service initialized")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize Drive service", e)
            driveService = null
        }
    }

    /**
     * Get authenticated Drive service
     */
    fun getDriveService(): Drive? {
        if (driveService == null && currentAccount != null) {
            initializeDriveService(currentAccount!!)
        }
        return driveService
    }

    /**
     * Check if user is authenticated
     */
    fun isAuthenticated(): Boolean {
        return currentAccount != null && driveService != null
    }

    /**
     * Get current account
     */
    fun getCurrentAccount(): GoogleSignInAccount? {
        return currentAccount
    }

    /**
     * Get account email
     */
    fun getAccountEmail(): String? {
        return currentAccount?.email
    }

    /**
     * Check if specific scope is granted
     */
    fun hasScope(scope: String): Boolean {
        val account = currentAccount ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(scope))
    }

    /**
     * Request additional scopes
     */
    fun requestScopes(activity: Activity, vararg scopes: String) {
        val account = currentAccount ?: return
        val scopeList = scopes.map { Scope(it) }.toTypedArray()

        GoogleSignIn.requestPermissions(
            activity,
            REQUEST_CODE_SIGN_IN,
            account,
            *scopeList
        )
    }

    /**
     * Refresh authentication token
     */
    suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            val account = currentAccount ?: return@withContext false

            // Re-initialize to refresh token
            initializeDriveService(account)

            Logger.d(TAG, "Token refreshed successfully")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to refresh token", e)
            false
        }
    }

    /**
     * Check if app has Drive access
     */
    fun hasDriveAccess(): Boolean {
        return isAuthenticated() && hasScope(DriveScopes.DRIVE_APPDATA)
    }

    /**
     * Get authentication status
     */
    fun getAuthStatus(): AuthStatus {
        return when {
            !isAuthenticated() -> AuthStatus.NOT_AUTHENTICATED
            !hasDriveAccess() -> AuthStatus.MISSING_PERMISSIONS
            else -> AuthStatus.AUTHENTICATED
        }
    }
}

/**
 * Sign-in result sealed class
 */
sealed class SignInResult {
    data class Success(val account: GoogleSignInAccount) : SignInResult()
    data class Error(val message: String) : SignInResult()
    object Cancelled : SignInResult()
}

/**
 * Authentication status
 */
enum class AuthStatus {
    NOT_AUTHENTICATED,
    MISSING_PERMISSIONS,
    AUTHENTICATED
}

/**
 * Extension to await Tasks
 */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    withContext(Dispatchers.IO) {
        while (!isComplete) {
            Thread.sleep(50)
        }
        if (isSuccessful) {
            result
        } else {
            throw exception ?: Exception("Task failed without exception")
        }
    }
