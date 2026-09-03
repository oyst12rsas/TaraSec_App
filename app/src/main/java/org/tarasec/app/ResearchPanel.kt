package org.tarasec.app

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun ResearchPanel(paymentBaseUrl: String? = null) {
    val activity = LocalContext.current as Activity
    var enabled by remember { mutableStateOf(ResearchPreferences.participationEnabled(activity)) }
    var discountOptIn by remember { mutableStateOf(ResearchPreferences.discountOptIn(activity)) }
    var paymentMethod by remember { mutableStateOf(PaymentPreferences.preferredMethod(activity)) }
    var paymentStatus by remember { mutableStateOf<PaymentBackendStatus?>(null) }

    LaunchedEffect(paymentBaseUrl) {
        val base = paymentBaseUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        Thread {
            val status = PaymentClient.status(base)
            activity.runOnUiThread { paymentStatus = status }
        }.start()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Research", style = MaterialTheme.typography.titleLarge)
        Text(
            "Help test TaraSec on real networks while getting an extra warning layer for suspicious traffic.",
            style = MaterialTheme.typography.bodyMedium
        )

        TaraSectionCard(
            title = "What TaraSec observes",
            subtitle = "Packet metadata, not your private content"
        ) {
            Text(
                "When Research protection is enabled, TaraSec uses Android's local VPN interface to inspect network packet headers and TaraSec tags. " +
                    "This can include source and destination addresses, ports, protocol, TCP flags and experimental TaraSec markers."
            )
            Text(
                "TaraSec does not need to read message text, passwords, web-page contents or other application payloads for this research. " +
                    "The purpose is to recognise TaraSec traffic, study how tags survive NAT and other networks, and detect warning signals in traffic metadata.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        TaraSectionCard(
            title = "Why this can help you",
            subtitle = "Research also acts as an early-warning sensor"
        ) {
            Text(
                "If TaraSec sees traffic carrying an infection/threat tag, or metadata that the TaraSec network has identified as suspicious, " +
                    "the app can warn you. A warning means suspicious or infected traffic was observed; it does not by itself prove that your phone is infected."
            )
        }

        TaraSectionCard(
            title = "Research contribution",
            subtitle = "Compare what leaves your phone with what another TaraSec endpoint receives"
        ) {
            Text(
                "Tests can measure whether TCP flags and experimental fields such as URG/urgent pointer survive Wi-Fi, mobile networks, CGNAT, " +
                    "multiple NAT layers and other middleboxes. Remote TaraSec apps, hotspots and VPS test endpoints can report what they actually received."
            )
        }

        TaraSectionCard(
            title = "Discounted internet access",
            subtitle = "Optional reward for voluntary research participation"
        ) {
            Text(
                "Hotspots and internet providers may later offer discounted access to users who voluntarily contribute approved research measurements. " +
                    "The provider controls the actual discount and availability. Research participation remains optional and can be turned off."
            )
            Text(
                if (enabled && discountOptIn)
                    "Discount participation: eligible to be considered when a hotspot/provider offers a research discount."
                else if (enabled)
                    "Research is enabled, but discount participation is not selected."
                else
                    "Enable Research protection before opting in for research-linked discounts.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    discountOptIn = !discountOptIn
                    ResearchPreferences.setDiscountOptIn(activity, discountOptIn)
                }
            ) {
                Text(if (discountOptIn) "Leave discount program" else "Join discount program")
            }
        }

        TaraSectionCard(
            title = "Payment options",
            subtitle = "Preferred method for hotspot access and research discounts"
        ) {
            Text(
                "TaraSec can present payment methods supplied by the hotspot's payment platform. No card or wallet credentials are stored in the TaraSec app.",
                style = MaterialTheme.typography.bodySmall
            )
            TaraPaymentMethod.entries.forEach { method ->
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        paymentMethod = method
                        PaymentPreferences.setPreferredMethod(activity, method)
                    }
                ) {
                    Text((if (paymentMethod == method) "✓ " else "") + method.title)
                }
                if (paymentMethod == method) {
                    Text(method.note, style = MaterialTheme.typography.bodySmall)
                }
            }

            val status = paymentStatus
            Text(
                when {
                    paymentBaseUrl.isNullOrBlank() -> "Select a registered installation to check payment availability."
                    status == null -> "Checking payment backend…"
                    !status.reachable -> "Payment backend unavailable: ${status.message}"
                    status.configured -> "${status.provider.ifBlank { "Payment" }} ${status.environment} backend ready."
                    else -> "Payment backend found but not configured yet: ${status.message}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (status?.methods?.isNotEmpty() == true) {
                Text("Backend methods: ${status.methods.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Google Pay can be used from Android; PayPal can use provider checkout; Apple Pay is offered through hotspot web checkout on supported Apple devices.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                enabled = !enabled
                ResearchPreferences.setParticipationEnabled(activity, enabled)
                if (!enabled) discountOptIn = false
            }
        ) {
            Text(if (enabled) "Disable Research protection" else "Enable Research protection")
        }

        Text(
            if (enabled)
                "Research protection is enabled. Android VPN packet capture will be activated here once the packet service is connected."
            else
                "Research protection is off. Normal TaraSec management continues unchanged.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
