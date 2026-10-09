
package com.rfidsoftwares.ui.screens

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.rfidsoftwares.R
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.ui.base.BaseScreenFragment

class ConnectApiPageFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Connect API"
    override fun screenSubtitle(): String? = "Choose the service this device should use"

    private var selectedProvider: AppConfig.ProviderDefinition? = null

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        val body = inflater.inflate(R.layout.body_connect_api, container, false)

        val providerRecycler: RecyclerView = body.findViewById(R.id.providerRecyclerView)
        val selectedSummary: TextView = body.findViewById(R.id.providerSelectedSummary)
        val continueBtn: MaterialButton = body.findViewById(R.id.connectContinueButton)

        val providers = AppConfig.ProviderRegistry.providers
        selectedSummary.text = "Choose one provider to continue."
        continueBtn.isEnabled = false
        continueBtn.visibility = View.GONE

        providerRecycler.adapter = ProviderAdapter(
            inflater = inflater,
            providers = providers,
            selectedProviderId = { selectedProvider?.providerId },
            onProviderPicked = { provider ->
                selectedProvider = provider
                selectedSummary.text = "Selected provider: ${provider.displayName}"
                continueBtn.isEnabled = true
                continueBtn.visibility = View.VISIBLE
            }
        )

        continueBtn.setOnClickListener {
            val providerId = selectedProvider?.providerId
            if (providerId.isNullOrBlank()) return@setOnClickListener

            findNavController().navigate(
                R.id.action_connectApiPage_to_providerAuthFlowFragment,
                bundleOf("providerId" to providerId)
            )
        }

        return body
    }

    private class ProviderAdapter(
        val inflater: LayoutInflater,
        val providers: List<AppConfig.ProviderDefinition>,
        val selectedProviderId: () -> String?,
        val onProviderPicked: (AppConfig.ProviderDefinition) -> Unit,
    ) : RecyclerView.Adapter<ProviderAdapter.ProviderViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProviderViewHolder {
            val v = inflater.inflate(R.layout.view_provider_row, parent, false)
            return ProviderViewHolder(v)
        }

        override fun onBindViewHolder(holder: ProviderViewHolder, position: Int) {
            val provider = providers[position]
            holder.bind(
                provider = provider,
                isSelected = provider.providerId == selectedProviderId(),
                onProviderPicked = {
                    onProviderPicked(it)
                    notifyDataSetChanged()
                }
            )
        }

        override fun getItemCount(): Int = providers.size

        class ProviderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(
                provider: AppConfig.ProviderDefinition,
                isSelected: Boolean,
                onProviderPicked: (AppConfig.ProviderDefinition) -> Unit,
            ) {
                val card: MaterialCardView = itemView.findViewById(R.id.providerRowRoot)
                val name: TextView = itemView.findViewById(R.id.providerRowName)
                val subtitle: TextView = itemView.findViewById(R.id.providerRowSubtitle)
                name.text = provider.displayName

                if (provider.isImplementedInPhase1) {
                    subtitle.text = if (isSelected) "Selected and ready to use" else "Available now"
                    itemView.alpha = 1.0f
                    itemView.isEnabled = true
                    itemView.setOnClickListener { onProviderPicked(provider) }
                    val selectedColor = ContextCompat.getColor(itemView.context, R.color.rfid_chip_background)
                    val defaultColor = ContextCompat.getColor(itemView.context, R.color.rfid_card_surface)
                    card.setCardBackgroundColor(if (isSelected) selectedColor else defaultColor)
                } else {
                    subtitle.text = "Available later"
                    itemView.alpha = 0.45f
                    itemView.isEnabled = false
                    itemView.setOnClickListener(null)
                    card.setCardBackgroundColor(
                        ContextCompat.getColor(itemView.context, R.color.rfid_card_surface)
                    )
                }
            }
        }
    }
}

