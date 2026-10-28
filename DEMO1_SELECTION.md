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

An automatic fallback does not overwrite the last explicit choice. In particular,
visiting a hotspot which does not advertise the saved endpoint must not forget it.
Automatic selection only selects and probes a route; it never marks a unit clean
or infected and does not bypass gateway verification.

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
