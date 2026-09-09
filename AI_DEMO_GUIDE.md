# TaraSec App Demo Guide for AI Assistants

This document is written so an AI assistant can quickly guide a tester through the TaraSec Android demo without rediscovering the network architecture from scratch.

## Purpose

The app demo should present one simple user concept: **This phone**. The app hides the underlying network complexity and resolves which TaraSec gateway currently owns the phone's active identity.

The main demonstration is that a phone can be marked **Clean** or **Infected** at the active TaraSec gateway, and a remote TaraSec node can independently observe the resulting traffic state shortly afterwards.

## Reference demo topology

With the demo VPN/WireGuard path active, the intended topology is:

```text
Phone
  | Wi-Fi
  v
Cigar
  | WireGuard / TaraSec routed path
  v
Squash
  | TaraSec-tagged traffic
  v
Tomato
```

Roles:

- **Phone**: the device whose Clean/Infected state the user controls.
- **Cigar**: the local Wi-Fi hotspot / first hop.
- **Squash**: the selected TaraSec security gateway when the VPN/WireGuard path is active.
- **Tomato**: a receiving TaraSec node that independently reports the traffic state it observes.

The phone can have multiple network identities at the same time. In the current reference setup:

```text
Wi-Fi identity seen by Cigar:     192.168.50.161
WireGuard identity seen by Squash: 10.100.0.150
```

Do not ask the tester to reason about these addresses unless troubleshooting. The app should present them as one logical device: **This phone**.

## Expected app behavior

Open the TaraSec app and enter **Security Demo**.

The page should show:

- Local Wi-Fi hotspot / first hop.
- Selected TaraSec gateway (normally Squash in the reference demo).
- VPN/gateway-path status.
- Receiving node selection (normally Tomato).
- **This phone** with Clean / Infected controls.
- Tomato's independently observed state.

If the selected TaraSec gateway is reachable through its service/data-plane address, the app should treat it as the active security gateway. Clean/Infected should then read and change the phone's state there.

If the selected gateway is unreachable, the app should indicate that the VPN probably needs to be turned on. If no remote security gateway is active, the app can fall back to the local hotspot gateway.

## Successful demo sequence

A known-good demo sequence is:

1. Connect the phone to the Cigar TaraSec hotspot.
2. Turn on the WireGuard/TaraSec VPN path used by the reference demo.
3. Open **Security Demo**.
4. Confirm Squash is shown as the active/reachable TaraSec gateway.
5. Confirm Tomato is reachable.
6. Observe the current state under **This phone**.
7. Tap **Clean** or **Infected**.
8. The app should update the phone's state on Squash using the identity Squash actually sees for the phone.
9. Tomato should independently change to the corresponding observed state, normally within about a second or a few polling cycles.

A validated example was:

- Squash had an active `internalInfections` row for the phone's WireGuard address `10.100.0.150`, severity 7.
- The app initially showed the phone as Infected.
- The tester selected Clean.
- Tomato reported Clean about one second later.

That is the desired end-to-end demonstration.

## Important implementation distinction

There are two different kinds of status readback and they must not be confused.

### Local/active-gateway phone state

For the phone itself, the app should read the explicit `internalInfections` state from the active gateway using:

```text
/script/appLocalInfection.php
```

This avoids a fresh traffic record overriding the user's explicit Clean/Infected setting.

The state-control endpoint is:

```text
/script/appInfectionControl.php
```

When called through the active VPN path, Squash naturally sees the phone's WireGuard source identity and updates the matching `internalInfections` record.

### Remote receiver observation

For Tomato and other receiving nodes, the app should use:

```text
/script/appInfection.php
```

That endpoint uses TaraSec's broader `getTagData()` logic and represents what the receiving node observes from traffic, not merely the source gateway's local database state.

Therefore it is valid for the source and receiver to briefly disagree during propagation, but a persistent mismatch is a troubleshooting signal.

## Cigar captive-portal/API port detail

On a TaraSec hotspot using openNDS, port 80 from Wi-Fi clients may be intercepted and redirected to openNDS on port 2050.

For TaraSec local application/API calls, the reference hotspot exposes Apache on port **8080**.

Example:

```text
openNDS captive portal: 192.168.50.1:2050
TaraSec local API:       192.168.50.1:8080
```

The Android `LocalGateway` helper should therefore use:

```text
http://<wifi-gateway>:8080
```

not plain port 80.

A validated phone request was:

```text
GET http://192.168.50.1:8080/script/appLocalInfection.php
```

and Cigar correctly identified the caller as `192.168.50.161`.

## VPN status semantics

Do not infer VPN state from the management/NetBird address. NetBird may remain reachable even when WireGuard is off.

The demo should test the selected installation's **service/data-plane IP** for the VPN/gateway-path indication.

Expected messages:

- Gateway service address reachable: `VPN / gateway path active`
- Selected gateway normally used but unreachable: `VPN appears to be off — turn on your VPN`
- No service IP configured: state that the VPN service IP is not configured rather than hiding the gateway.

Squash should remain visible as the selected/reference TaraSec gateway even if its VPN service address is temporarily unavailable.

## NetBird is separate from the demo data path

The reference environment also uses NetBird for management and testing. A phone may be allowed through Cigar to NetBird for authorized TaraSec owner/admin access.

This can make Tomato or other management hosts reachable even when WireGuard is off. That does **not** prove the Squash VPN path is active.

Do not interpret `Tomato reachable` as `Squash VPN active`.

## Troubleshooting checklist

If **This phone** is stuck on Clean or Infected:

- Confirm the app is using the correct active gateway.
- If WireGuard is on and Squash is active, inspect Squash's `internalInfections` table for the phone's WireGuard identity.
- If no remote gateway is active, inspect Cigar's `internalInfections` table for the phone's Wi-Fi identity.
- Confirm `appLocalInfection.php` and `appInfectionControl.php` are installed on the gateway being used.

Useful SQL on a gateway:

```sql
SELECT
    infectionId,
    INET_NTOA(ip) AS ip,
    severity,
    CAST(active AS UNSIGNED) AS active,
    CAST(handled AS UNSIGNED) AS handled,
    why,
    lastSeen
FROM internalInfections
ORDER BY infectionId DESC
LIMIT 20;
```

If Cigar's local API appears unavailable from the phone, remember openNDS intercepts port 80. Test port 8080.

If Tomato always reports Infected or Clean regardless of the source state:

- Verify the source gateway really changed the relevant `internalInfections` record.
- Verify the request is actually routed through the intended security gateway.
- Inspect Tomato's `appInfection.php` output and `traffic` table if needed.
- Remember that Tomato reports observed TaraSec traffic state, not merely the source gateway's database row.

## Guidance for AI assistants

When a tester says they want to test the TaraSec app, start with the user-visible workflow and only expose network details if something fails.

Prefer language such as:

```text
Open TaraSec > Security Demo.
Confirm the selected TaraSec gateway is reachable.
Toggle This phone between Clean and Infected.
Watch whether the receiving node changes shortly afterwards.
```

Avoid immediately asking the user to manipulate `iptables`, SQL tables, WireGuard routes, or IP addresses unless the normal demo fails.

If troubleshooting is required, first establish which machine a command belongs on. In the reference setup:

- Android/ADB commands run on the computer physically connected to the phone (Acer in the development setup).
- Cigar commands run on the Wi-Fi hotspot.
- Squash commands run on the selected TaraSec/WireGuard gateway.
- Tomato commands run on the receiving node.

Never give a placeholder path such as `/path/to/the/script` as if it were a literal command. If the exact path is not known, identify it first.

## Repository context

Android app repository:

```text
oyst12rsas/TaraSec_App
```

Core TaraSec repository containing PHP endpoints and gateway logic:

```text
oyst12rsas/taransvar
```

Useful app files include:

```text
app/src/main/java/org/tarasec/app/DemoPanel.kt
app/src/main/java/org/tarasec/app/DemoClient.kt
app/src/main/java/org/tarasec/app/LocalGateway.kt
app/src/main/java/org/tarasec/app/InstallationStore.kt
```

Useful TaraSec web endpoints include:

```text
html/script/appLocalInfection.php
html/script/appInfectionControl.php
html/script/appInfection.php
```

## Demo goal in one sentence

The demo should let a user change **This phone** between Clean and Infected while the app automatically resolves the correct TaraSec identity and gateway, and then show a remote TaraSec node independently observing the resulting security state.

## Demo 2: SSH attribution and correction

This is a separate demo from the Clean/Infected toggle. The Android app supervises
the demonstration and tells the DB server which registered installations and units
will act in each role:

- **SSH client**: the app itself, or a laptop used to make the two SSH attempts.
- **Node A**: rejects the first SSH attempt and reports it as infected demo traffic.
- **Gateway**: receives Node A's report, is deliberately not told that this is a
  demo, and treats the source unit as suspicious through the normal TaraSec path.
- **Node B**: receives the next tagged SSH attempt, permits authentication with the
  DB-managed demo credential, and reports the successfully authenticated connection
  as legitimate.
- **DB server**: correlates all reports, reveals the demo only after the evidence is
  complete, and instructs the gateway to clear the unit.

The app-side protocol is implemented in `DemoSshClient.kt`. The expected DB-server
endpoints are:

```text
POST /script/appDemoSshStart.php
GET  /script/appDemoSshStatus.php?session_id=...
POST /script/appDemoSshFinish.php
```

The DB server owns the session and credential lifecycle. It returns the same
`demoSshNodeB` username and password to every active session using that Node B.
It must not rotate or retire the credential until the last linked session completes,
expires, or is cancelled. Node B receives only the password hash. The app holds the
plaintext password in memory and must not persist it.

The status response drives these app-visible stages:

```text
waiting_for_participants
ready
first_ssh_expected
node_a_rejected
gateway_marked_unit
second_ssh_expected
node_b_accepted_tagged_ssh
legitimate_reported
gateway_cleared
complete
```

Port 22 is reserved for the SSH honeypot/demo service. Genuine administrative SSH is
moved to the configured alternate port (for example 5822). `tarakernel` must not block
the honeypot port: Node B needs to observe the TaraSec tag and allow the DB-issued demo
credential to reach SSH authentication. This is a permanent service/port distinction,
not a temporary per-flow exception. Normal protection of the real administrative SSH
port remains independent.

The app should display the SSH host, port, username, password, a copyable SSH command,
and the live event sequence. If no embedded SSH implementation is present, it should
let the user copy the command and use a laptop or installed SSH client.

