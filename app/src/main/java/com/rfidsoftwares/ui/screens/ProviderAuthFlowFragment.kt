
package com.rfidsoftwares.ui.screens
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.rfidsoftwares.R
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.common.auth.AuthUiStateStore
import com.rfidsoftwares.common.auth.ActiveProviderStore
import com.rfidsoftwares.integration.BackendAdapterProvider
import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.models.AuthRequest
import com.rfidsoftwares.ui.base.BaseScreenFragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class ProviderAuthFlowFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Provider Connection"
    override fun screenSubtitle(): String? = "Finish connecting before entering the app"

    private val handler = Handler(Looper.getMainLooper())
    private var authExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        val body = inflater.inflate(R.layout.body_provider_auth_flow, container, false)
        ensureExecutor()

        val authProviderName: TextView = body.findViewById(R.id.authProviderName)
        val directAuthButton: MaterialButton = body.findViewById(R.id.directAuthButton)
        val oauthAuthButton: MaterialButton = body.findViewById(R.id.oauthAuthButton)
        val authStatusText: TextView = body.findViewById(R.id.authStatusText)

        val providerId = requireArguments().getString(ARG_PROVIDER_ID)
        val provider = AppConfig.ProviderRegistry.getById(providerId)

        if (provider == null) {
            authProviderName.text = "Provider: (unknown)"
            directAuthButton.isEnabled = false
            oauthAuthButton.isEnabled = false
            authStatusText.text = "Provider configuration is missing. Go back and choose a valid provider."
            return body
        }

        authProviderName.text = "Provider: ${provider.displayName}"

        directAuthButton.isEnabled = provider.supportsDirectAuth
        oauthAuthButton.isEnabled = provider.supportsOauth
        if (!provider.supportsDirectAuth) directAuthButton.alpha = 0.45f
        if (!provider.supportsOauth) oauthAuthButton.alpha = 0.45f

        directAuthButton.setOnClickListener {
            if (!provider.supportsDirectAuth) return@setOnClickListener

            val username = AuthUiStateStore.username
            val password = AuthUiStateStore.password
            val captchaToken = AuthUiStateStore.captchaToken

            if (username.isNullOrBlank() || password.isNullOrBlank()) {
                authStatusText.text = "Your sign-in details are missing. Go back and sign in again."
                return@setOnClickListener
            }

            authStatusText.text = "Connecting to ${provider.displayName}..."
            directAuthButton.isEnabled = false
            oauthAuthButton.isEnabled = false

            authExecutor.execute {
                try {
                    val adapter = BackendAdapterProvider.getAdapter(provider.providerId)
                    adapter.startAuth(
                        request = AuthRequest.DirectCredentialAuth(username = username, password = password),
                        captchaToken = captchaToken,
                    )

                    handler.post {
                        if (!isAdded) return@post
                        ActiveProviderStore.activeProviderId = provider.providerId
                        authStatusText.text = "Connected. Opening your dashboard..."
                        val navOptions = NavOptions.Builder()
                            .setPopUpTo(R.id.splashFragment, true)
                            .build()
                        findNavController().navigate(
                            R.id.action_providerAuthFlow_to_roleAndFeatureGateFragment,
                            null,
                            navOptions
                        )
                    }
                } catch (e: AdapterError) {
                    handler.post {
                        if (!isAdded) return@post
                        authStatusText.text = "Connection failed: ${e.message.orEmpty()}"
                        directAuthButton.isEnabled = true
                        oauthAuthButton.isEnabled = provider.supportsOauth
                    }
                } catch (_: Exception) {
                    handler.post {
                        if (!isAdded) return@post
                        authStatusText.text = "Connection failed. Please try again."
                        directAuthButton.isEnabled = true
                        oauthAuthButton.isEnabled = provider.supportsOauth
                    }
                }
            }
        }

        oauthAuthButton.setOnClickListener {
            if (!provider.supportsOauth) return@setOnClickListener
            authStatusText.text = "Browser sign-in is not available yet for this provider."
        }

        return body
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        authExecutor.shutdownNow()
        super.onDestroyView()
    }

    companion object {
        const val ARG_PROVIDER_ID = "providerId"
    }

    private fun ensureExecutor() {
        if (authExecutor.isShutdown || authExecutor.isTerminated) {
            authExecutor = Executors.newSingleThreadExecutor()
        }
    }
}

