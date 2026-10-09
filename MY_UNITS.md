# My units and Google account linking

This is unit-owner access, separate from gateway-manager authorization.

1. Open **My units**, select **Link a node**, enter the gateway IP, and continue with Google when prompted.
2. Use **Copy link** to copy the gateway's linking URL. Open it on the device you
   want to link, while on its TaraSec-managed LAN, and continue with the same
   Google account. Opening the link on the phone links the phone; this URL does
   not carry another node's identity or a pairing proof.
3. Return to **My units** and tap **Add linked nodes**. Repeat for other gateways.
4. Name units and use **Check status** for each. Offline units stay saved.

The gateway must serve HTTPS with a trusted certificate, identify each unit
unambiguously from a recent canonical local address, and have its Google origin
and login URI registered. A reverse proxy that hides all local client addresses
does not meet the attribution requirement. Do not enable caller-controlled
forwarded IP headers to work around this.

The app's subscriber token goes only to the central identity service. A
one-use, 60-second ticket bound to the chosen gateway carries the account proof.
The gateway stores a keyed hash of the Google subject alongside local unit links,
not a Google email/name. The central ticket service receives no unit IDs or
threat reports. Gateway unit tokens and the saved list use Android Keystore-backed
encrypted storage, split by subscriber account. Screens disable screenshots;
credentials are not included in status/debug exports.

Sync replaces the Google-linked list for that gateway and rotates this app's
read-only unit tokens. **Remove from phone** removes only the local entry;
**Unlink account** revokes all Google-linked app grants for that account/unit.
Other accounts, manual tokens and manager access are independent.

For a headless device, run the existing unit pairing tool on that device and
paste its JSON under **Pair a device without Google/browser**, with its reachable
HTTPS gateway address. Never publish pairing JSON. Live QR scanning is not part
of this change.

Threat status distinguishes a reported local infection, a warning, and no current
warning. Unavailable/expired/revoked/unsupported endpoints never imply clean.
AI summaries and confidence are shown if available. The guidance link explains
AI and recovery; this change does not implement an interactive remediation chat.

## Coordinated deployment

- TaraSec Core: unit link pages/API, setup script, schema and status confidence.
- tarasec_payment: central unit-identity API and schema (normal deploy applies it).
- tarasec.org: fixed public `/api/v1/identity/unit-identity.php` proxy route.
- TaraSec_App: build/install the APK containing My units.

No source change alone configures Google, HTTPS, the database or a running APK.
See Core `docs/unit-app-pairing.md` for gateway setup and deployment validation.

## Gateway-first service selection

In My units, **Link a node → Continue** checks the gateway before using tarasec.org.
A configured owner-hosted identity/subscriber service takes precedence; a gateway
with no local service configured (or no discovery endpoint) uses tarasec.org.
The app also checks the DB-recognized NetBird gateway before first sign-in.
A failing or invalid configured service is an error, not automatic central fallback.
The service hostname is shown before sign-in. Tokens, account IDs and saved units
are isolated by provider. OAuth exchanges stay with the provider that started them.
My units checks that its gateway uses the same account service before sending proof.
See Core `docs/service-discovery.md` for server configuration and deployment.

This does not configure gateway HTTPS or create the missing unit-link configuration.
Those prerequisites above remain required for the existing Google unit-link flow.

## One ownership and management entry

**My units** is the only hamburger destination for nodes. The overlapping
**Status / Units** and **AI / Assistance** menu entries are removed. Select a
node and tap **Manage** to open its status, then use **Status**, **AI**,
**Assistance** and **SSH** inside that node's management screen. All four retain the same
selected node. Older status/AI intents still resolve to the corresponding view.
Linked nodes with saved management mappings appear once, rather than repeating
in the managed-installations section. Saved management access opens Status and
is checked on entry; unapproved and pending requests open the management approval flow.
Status contains only the selected gateway and its clients; other partner servers
are not listed there. AI assessments and network assistance requests have separate
views. Approval/reconnection is shown when needed, rather than an empty Access tab.

The SSH view requires the gateway's optional manager SSH worker. It offers 5, 10
and 15 minute openings of the gateway's configured SSH port, with
queued/applied state and a countdown. Kernel ipset timeouts enforce expiry even
if the app or worker stops. The app triggers the opening; SSH may then be used from a computer or another
client. Ending the temporary opening removes only that
allowance; existing owner/recovery SSH policy is retained. The displayed status
is the temporary firewall allowance, not a verified SSH login. See Core
`docs/manager-ssh.md` for installation and live acceptance checks.

The hamburger menu has one **My units** entry. It shows saved installations with
Manage actions and linked nodes with a Request management access action, plus
account sign-in, gateway service discovery and linked-node status. The separate
Setup / My hotspots menu entry is removed. Google sign-in started from My units
returns to My units. Hotspot Internet sign-in remains in My access without the
node ownership discovery controls. Unit read-only credentials and installation
manager authorization remain distinct; the existing manager API verifies access.

The default My units screen shows saved units and one **Link a node** action.
Gateway discovery, Google sign-in and adding linked nodes are presented in sequence.
Manual pairing is hidden under Advanced. Once linked, each node offers
**Request management access**, using the existing administrator approval flow.
For a linked gateway, tapping **Request management access** submits one request
using that saved gateway address and the signed-in account email. Reopening resumes
the pending request and shows **Check management approval**, with no repeated
registration form or second request. **Open node approval page** opens Gatekeeper
Home, where a local administrator reviews the request after email verification.
Foreground polling checks every five seconds and activates access after both
confirmations. Pending request credentials survive activity recreation; successful
activation saves a node/account-specific installation mapping and changes the action
to **Manage**. Read-only linking does not grant management permission.

For a single endpoint paired through a gateway, its gateway address is not assumed
to be that endpoint’s management address; the explicit management form remains.
