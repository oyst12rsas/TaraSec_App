package org.tarasec.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Public contribution entry point.
 *
 * This first version deliberately links to the public governance/AI surfaces while
 * the shared governance API, verified merit feed and TaraSec AI chat endpoint are
 * being implemented. Those services must be shared by the app and website rather
 * than creating a second app-only governance system.
 */
class ContributeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { ContributeScreen() }
            }
        }
    }
}

@Composable
private fun ContributeScreen() {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Contribute", style = MaterialTheme.typography.headlineLarge)
            TaraHamburgerMenu { destination ->
                when (destination) {
                    TaraMenuDestination.CONTRIBUTE -> Unit
                    TaraMenuDestination.MY_ACCESS,
                    TaraMenuDestination.FIND_INTERNET -> context.startActivity(
                        Intent(context, SubscriberHomeActivity::class.java)
                            .putExtra(CONSOLE_DESTINATION_EXTRA, destination.name)
                    )
                    else -> context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .putExtra(CONSOLE_DESTINATION_EXTRA, destination.name)
                    )
                }
            }
        }

        Text(
            "Use TaraSec, improve TaraSec, and build a verifiable contribution history.",
            style = MaterialTheme.typography.titleMedium
        )

        TaraSectionCard(
            title = "Your contribution",
            subtitle = "Merit is earned from useful, verifiable participation — not titles or donations."
        ) {
            Text("Deploying and responsibly operating TaraSec hotspots, nodes, gateways and participating servers counts as contribution.")
            Text("Code, proposals, testing, documentation, useful reviews and security findings can also build domain-specific merit.")
            Text("GitHub discussion activity is not credited automatically yet. The planned account link will attach a verified GitHub identity to the contributor's Google-linked TaraSec account and retain evidence for each awarded merit event.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { open("https://tarasec.org/governance/#github-merit") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("How GitHub contributions earn merit")
            }
            Text("Healthy operation and usefulness matter more than raw device or message count.", style = MaterialTheme.typography.bodySmall)
        }

        TaraSectionCard(
            title = "Contributor community",
            subtitle = "Discuss changes with other contributors before they become formal proposals."
        ) {
            Text("Use the public contributor forum to raise ideas, challenge assumptions, review proposals and help define risks, tests and implementation criteria.")
            Text("TaraSec AI may help summarize a discussion and prepare an editable proposal, but discussion is not a vote and cannot authorize code changes or deployment.")
            Button(
                onClick = { open("https://github.com/oyst12rsas/taransvar/discussions") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open contributor discussions")
            }
            Button(
                onClick = { open("https://github.com/oyst12rsas/taransvar/issues/132") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Discuss contribution design")
            }
            Text("GitHub Discussions is the current public community space. It will be replaced or integrated with the shared Google-linked TaraSec contributor identity and merit backend without creating a separate app-only history.", style = MaterialTheme.typography.bodySmall)
        }

        TaraSectionCard(
            title = "Chat with TaraSec AI",
            subtitle = "Turn questions and observations into useful contributions."
        ) {
            Text("TaraSec AI is intended to help inspect the public architecture and source, explain behaviour, challenge assumptions, trace affected components, suggest tests and prepare candidate governance proposals.")
            Text("A useful chat should be convertible into a proposal that the contributor can edit before submission. AI analysis is not a vote and does not authorize deployment.")
            Button(onClick = { open("https://tarasec.org/ai/") }, modifier = Modifier.fillMaxWidth()) {
                Text("Open TaraSec AI")
            }
        }

        TaraSectionCard(
            title = "Governance",
            subtitle = "Good ideas should matter because they are good ideas."
        ) {
            Text("Propose changes, challenge assumptions, follow implementation and build trust through demonstrated work.")
            Button(onClick = { open("https://tarasec.org/governance/") }, modifier = Modifier.fillMaxWidth()) {
                Text("Develop TaraSec with us")
            }
        }

        TaraSectionCard(
            title = "Implementation status",
            subtitle = "This screen establishes the app workflow before the shared backend is complete."
        ) {
            Text("Next backend milestone: Google-linked contributor identity, shared community discussions and proposal threads, verified infrastructure ownership/operation, contribution events, AI chat sessions, proposal conversion, moderation and notifications.")
            Text("The app and TaraSec.org must use the same contribution and governance records.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
