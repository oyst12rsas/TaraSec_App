# Temporary SSH in the app

The selected gateway provides its enrolled server reference through the authenticated
managerSsh API. If that identity is absent, there is no generic dashboard fallback.

Sign in with Google once using the browser handoff and explicitly connect the app
to the displayed node. The handoff uses a short-lived S256 verifier exchange;
the URL contains no verifier or reusable app token. Native tokens last at most
30 minutes, remain in memory and authorize only this node's list/opening/approval.

Choose 5, 10 or 15 minutes, enter the Authenticator code in the app and confirm.
The existing server-side TOTP checks, replay protection, rate limits, local node
policy and bounded execution still apply. Approval is not proof of opening:
the app waits for the matching applied job and node-reported deadline.

Deployment requires the matching Core and approval-service changes. Upgrade the
node approval installer to publish metadata from its protected enrollment receipt.
A node with no receipt requires explicit Google enrollment. Ports and private node
IDs never enter the app. No live firewall migration is performed automatically.

Verify two nodes with different identities and duplicate/changed display names.
Test unknown/unauthorized references, wrong codes, replay, expired sign-in, service
failure, actual opening on only the selected node, countdown and expiry.

The SSH screen first reuses the signed-in account through the approval service's
`account_login` action. The account token is sent only when the selected account
service and approval service have the same HTTPS origin, with redirects disabled.
The approval service asks its configured central backend for a verified Google
identity, checks pinned subjects and current per-node grants, then issues the same
30-minute maximum, node-bound SSH token as browser pairing. Management cookies
alone never authorize SSH. Authenticator approval is still required per opening.

Tokens, pending browser pairing and the matching opening job survive tab/activity
navigation in process memory, keyed by installation, account credential, approval
origin and exact node reference. Logout/installation removal clears them. Expired
sessions are verified again using the current account; process restart can also
reuse that account without another browser visit. Different account services or
accounts without a verified Google identity retain explicit browser connection.

Deploy the account-service `identity/ssh-identity.php` first (the hosted-link
installer includes it), then website `_account_identity.php` and `mobile.php`, then
the app. No node reinstall or new enrollment is needed for this change. Check live
Google-signed-in My units → Manage → SSH, tab away/back, expiry, logout/account
switch, and two distinct node identities. The new local tests cover cache scope,
expiry, pairing retention and preventing credentials reaching a foreign origin.
