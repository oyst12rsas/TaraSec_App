# My units and Google account linking

This is unit-owner access, separate from gateway-manager authorization.

1. Sign in using Google in the app's **My access** screen.
2. On each laptop/unit, while on its TaraSec-managed LAN, open
   `https://GATEWAY/script/unitLink.php` and continue with the same Google account.
3. Open **My units** in the app menu, enter that gateway's HTTPS origin and tap
   **Sync linked units**. Repeat for other gateways.
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
