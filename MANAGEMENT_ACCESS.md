# App management access actions

The app shows only the next applicable action:
- No valid saved grant: start a new request. Do not open the approval page yet.
- Request created: confirm the email; resend is available after status is known.
- Email confirmed, administrator approval missing: open the node approval page.
- Both approvals complete: wait for credential generation and activate automatically.
- Expired, rejected or revoked request: offer a new request, not more approval of the old one.
- Network/status failure: show the error and retry; do not assume rejection.

Core now returns a structured state for a known request token or saved credential.
Older nodes remain compatible: generic rejected login offers a new request;
request_not_found stops polling the obsolete request.

Expired and rejected records leave the node's default approval worklist. Read-only
history retains them for audit; expiry is never displayed as ACTIVE and obsolete
requests cannot be approved or re-enabled by posting an old ID.

SSH opening uses the existing Google sign-in and TOTP approval page, rather than
the old manager-only opening API. The app does not display a port or call that API.
Confirm the selected machine before approving in the browser. Update Core as well
to reject old direct-open requests and remove ports from SSH API responses.

No live firewall migration is performed by these source changes. See Core
docs/google-ssh.md and docs/manager-ssh.md for node enrollment, independent console
recovery and the restriction against combining legacy and Google timed gates.

Validation:
- Unit tests cover next-action visibility including stale flags and terminal states.
- On a phone, test new request, email confirmation, approval, expiry, rejection,
  missing request, network failure and successful reconnect.
- Verify SSH browser sign-in and Authenticator approval, the intended node,
  an actual new SSH connection and expiry. No port should appear in app/API data.
