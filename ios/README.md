# TaraSec iPhone subscriber client

This directory starts the iOS subscriber client against the same platform-neutral TaraSec API used by Android.

Current scaffold:

- SwiftUI subscriber account view
- email/phone global TaraSec login
- token storage in the iOS Keychain
- global credit balance
- recent usage grouped by serving hotspot
- per-hotspot credits/MiB display
- payment entry point that remains disabled until the backend advertises a configured payment provider

The source is intentionally API-compatible with Android. It does not yet include an Xcode project, signing configuration, App Store metadata, hotspot auto-authentication, or payment-provider SDK. Those should be added without changing the subscriber account contract.

The shared endpoints are:

- `subscriber-login.php`
- `subscriber-account.php`

Both default to `https://tarasec.org/hotspot/opennds`.
