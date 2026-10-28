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
