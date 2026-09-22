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

The host iOS target must register the `tarasec` URL scheme for the `tarasec://identity` callback. Production should move to an HTTPS Universal Link. The source is intentionally API-compatible with Android. The repository includes an XcodeGen project definition and an unsigned GitHub Actions simulator build. It does not yet include signing configuration, App Store metadata, hotspot auto-authentication, or a payment-provider SDK. Those should be added without changing the subscriber account contract.

## Build

The project is generated from `ios/project.yml` so contributors do not have to hand-edit or commit Xcode project internals.

On macOS:

```bash
brew install xcodegen
cd ios
xcodegen generate
open TaraSec.xcodeproj
```

For a command-line simulator build:

```bash
cd ios
xcodegen generate
xcodebuild \
  -project TaraSec.xcodeproj \
  -scheme TaraSec \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

GitHub Actions runs the same unsigned build for every pull request that changes the iOS source. Device installation, TestFlight, and App Store distribution will require an Apple developer team and signing configuration.

The shared endpoints are:

- `identity-start.php`
- `identity-exchange.php`
- `subscriber-login.php`
- `subscriber-account.php`

Subscriber endpoints use `https://tarasec.org/api/v1/subscriber`; Google/Facebook identity endpoints use `https://tarasec.org/api/v1/identity`. The `/hotspot` path remains reserved for node-local administration and captive portal pages.
