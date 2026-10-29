# Demo 1 endpoint selection

Demo 1 remembers the last explicitly selected endpoint across screen changes and
app restarts. A manually entered endpoint is retained as well.

Selection order:

1. The last chosen endpoint, when available on the current demo path.
2. The most recently registered local installation, when available as an IPv4
   demo endpoint (registration time, not alphabetical order).
3. The first endpoint in the displayed list.

Without local additions, the reversed preset list is Gouda, Camembert, Roquefort,
Porsche, Tomato: the three Standard-gateway endpoints are together at the top.
Registered IPv4 installations are included newest first. The hotspot's advertised
endpoint list is also reversed; it remains authoritative on a direct hotspot.

Locally registered and manually entered endpoints have a **Remove … from Demo 1**
button. Removal is remembered across restarts and does not delete the saved
installation, credentials, or gateway connection. This also lets users remove a
gateway which was included through its saved installation. Removed addresses are
excluded from the local demo list and its polling; entering the address again
with **Use this endpoint** restores it. A direct hotspot's advertised list remains
controlled by the hotspot.

An automatic fallback does not overwrite the last explicit choice. In particular,
visiting a hotspot which does not advertise the saved endpoint must not forget it.
Selecting an endpoint probes its route and never bypasses gateway verification.
When Demo 1 is open on its own, starting a verified gateway for the first time
in this visit automatically requests a clean demo state using `demo=1`. The
backend clears only DEMO records; genuine infections remain active. Confirmation
still requires gateway and receiver evidence. Refreshing or selecting another
receiver behind that gateway does not reset a subsequent INFECTED demonstration.
A failed startup reset is not repeatedly retried; the visible Set CLEAN control
can retry it. Other expanded demos suppress automatic preparation.

Manual checks:

- With no saved choice or installations, open Demo 1: Gouda is selected and its
  route is probed automatically.
- Register two installations with different registration times; clear only the
  demo preference: the newer registration is selected, regardless of its name.
- Choose another preset, leave Demo 1 and reopen/restart: the choice is restored.
- Enter a custom IPv4 endpoint, restart: it is listed and selected again.
- Enter an unavailable endpoint: retain the selection and show probe failures;
  do not silently choose a reachable endpoint instead.
- Switch between a direct hotspot with a different list and the VPN list: use the
  available fallback and restore the explicit choice when it becomes available.
- A hotspot advertising no endpoints must show no selected endpoint or enabled
  state-changing controls.
- Remove a registered gateway endpoint and restart: it stays absent from Demo 1,
  while its saved installation remains available for management/VPN use.
- Remove the selected custom endpoint: its draft and saved custom address clear,
  another available endpoint is selected, and old receiver evidence is cleared.
- Remove an unselected local endpoint: the current selection remains unchanged.
- Re-enter a removed address: it becomes visible and selected again.

Clean-start checks:

- Start with a stale Demo 1 infection, open the screen and wait for verification:
  one demo-only reset is requested and CLEAN must be observed before confirmation.
- Tap Set INFECTED, refresh and select a shared-gateway receiver: remain infected
  until the user requests CLEAN; no repeated automatic reset.
- Without a verified gateway or while another demo is expanded, send no startup
  reset. With a genuine infection present, do not clear it or claim clean.
- Simulate reset failure: show the failure and permit manual retry.
