# TaraSec iPhone subscriber client

This directory starts the iOS subscriber client against the same platform-neutral TaraSec API used by Android.

Current scaffold:

- SwiftUI subscriber account view
- Google/Facebook global identity login plus legacy email/phone password login
- token storage in the iOS Keychain
- global credit balance
- recent usage grouped by serving hotspot
- per-hotspot credits/MiB display
- payment entry point that remains disabled until the backend advertises a configured payment provider

The host iOS target must register the `tarasec` URL scheme for the `tarasec://identity` callback. Production should move to an HTTPS Universal Link. The source is intentionally API-compatible with Android. It does not yet include an Xcode project, signing configuration, App Store metadata, hotspot auto-authentication, or payment-provider SDK. Those should be added without changing the subscriber account contract.

The shared endpoints are:

- `identity-start.php`
- `identity-exchange.php`
- `subscriber-login.php`
- `subscriber-account.php`

Subscriber endpoints use `https://tarasec.org/api/v1/subscriber`; Google/Facebook identity endpoints use `https://tarasec.org/api/v1/identity`. The `/hotspot` path remains reserved for node-local administration and captive portal pages.
