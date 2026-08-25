package org.tarasec.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ResearchPanel() {
    var enabled by remember { mutableStateOf(false) }

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

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { enabled = !enabled }
        ) {
            Text(if (enabled) "Disable Research protection" else "Enable Research protection")
        }

        Text(
            if (enabled)
                "Research protection selected. Android VPN packet capture will be activated here once the packet service is connected."
            else
                "Research protection is off. Normal TaraSec management continues unchanged.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
