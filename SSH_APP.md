# Fresh server management and SSH

Opening Manage starts a new gateway-session challenge using the saved approved
management credential. That credential alone never authorizes server data.
The app offers a fresh Google verification on management.php, bound to the exact
enrolled server reference and app verifier. Existing subscriber credentials and
old operator cookies cannot satisfy that request. Google may use its own SSO;
TaraSec requires a newly verified nonce-bound assertion, not a forced password prompt.

The selected node verifies a one-use proof using its existing Gatekeeper handoff
credential. It checks the current local manager credential, matching Google email,
node identity, session nonce and expiry before setting a management cookie.
The same session lasts at most 15 minutes across Status, AI, Assistance and SSH.
Sensitive endpoints enforce freshness and exact node identity server-side. The app
hides management views on expiry, and background polling cannot log in with a saved
credential. Opening SSH still requires a fresh Authenticator code and node-reported
confirmation; switching tabs does not cause another Google sign-in.

Native grants stay in process memory, scoped to the management entry, installation
and exact approval target. No long-lived subscriber-token exchange is used.
Existing legacy management cookies and native sessions are rejected after rollout.

Deploy website first, Core management endpoints/helper second, app last. The node
needs its existing Google enrollment and Gatekeeper handoff configuration. Use
Core install_agent_approvals.sh --update-only for an already enrolled node; it
deploys the complete management bundle. No SSH firewall policy or enrollment is
automatically changed. No tarasec_payment deployment is required for this fix.

Acceptance: open Manage with only saved credentials and confirm no server status
is exposed; complete fresh Google verification; visit every tab, including SSH;
verify only Authenticator approval is required for an opening; reject a different
account/node, replayed proof, old cookie, expired session and long-lived account
token. Live phone/Google/node acceptance remains required after deployment.
