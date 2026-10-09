
package com.rfidsoftwares.ui.screens

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.rfidsoftwares.R
import com.rfidsoftwares.common.auth.AuthUiStateStore
import com.rfidsoftwares.common.prefs.LoginPrefs
import com.rfidsoftwares.ui.base.BaseScreenFragment

class LoginPageFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Sign In"
    override fun screenSubtitle(): String? = "Enter credentials to access terminal"
    override fun isTopLevelScreen(): Boolean = true
    override fun allowOfflinePanel(): Boolean = false
    override fun showHeader(): Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        val body = inflater.inflate(R.layout.body_login, container, false)

        val usernameInput: TextInputEditText = body.findViewById(R.id.usernameInput)
        val passwordInput: TextInputEditText = body.findViewById(R.id.passwordInput)
        val savePasswordCheckBox: MaterialCheckBox = body.findViewById(R.id.savePasswordCheckBox)
        val loginErrorText: TextView = body.findViewById(R.id.loginErrorText)
        val loginSubmitButton: MaterialButton = body.findViewById(R.id.loginSubmitButton)
        val loadingContainer: FrameLayout = body.findViewById(R.id.loginLoadingContainer)

        // Load saved credentials & remember password preference
        val ctx = requireContext()
        val isRemember = LoginPrefs.isRememberPasswordEnabled(ctx)
        savePasswordCheckBox.isChecked = isRemember
        usernameInput.setText(LoginPrefs.getSavedUsername(ctx))
        if (isRemember) {
            passwordInput.setText(LoginPrefs.getSavedPassword(ctx))
        }

        loginSubmitButton.setOnClickListener {
            val username = usernameInput.text?.toString()?.trim().orEmpty()
            val password = passwordInput.text?.toString()?.trim().orEmpty()
            val remember = savePasswordCheckBox.isChecked

            loginErrorText.visibility = View.GONE

            if (username.isBlank() || password.isBlank()) {
                loginErrorText.text = "Please enter both username and password."
                loginErrorText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (!LoginPrefs.validate(username, password)) {
                loginErrorText.text = "Invalid credentials. Use username: admin, password: admin"
                loginErrorText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Valid credentials
            LoginPrefs.saveCredentials(ctx, username, password, remember)
            AuthUiStateStore.username = username
            AuthUiStateStore.password = password

            loadingContainer.removeAllViews()
            val loading = inflater.inflate(R.layout.view_state_loading, loadingContainer, false)
            val msg: TextView = loading.findViewById(R.id.loadingMessage)
            msg.text = "Signing in..."
            loadingContainer.addView(loading)
            loadingContainer.visibility = View.VISIBLE
            loginSubmitButton.isEnabled = false

            handler.postDelayed(
                {
                    loadingContainer.visibility = View.GONE
                    findNavController().navigate(R.id.action_loginPage_to_modeSelectorFragment)
                },
                400
            )
        }

        return body
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }
}
