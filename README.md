# TaraSec App

TaraSec App is the primary mobile implementation and source of truth for TaraSec client behaviour. It provides owner, subscriber, gateway-management, research, and end-to-end demonstration interfaces for the wider TaraSec system.

## Development order

New features, protocol changes, security improvements, and behavioural fixes are implemented and validated in this repository first. Platform-specific versions, including [TaraSec for iOS](https://github.com/oyst12rsas/tarasec_iOS), are then updated to preserve the same API contracts and behaviour.

## Build

See [AI_DEMO_GUIDE.md](AI_DEMO_GUIDE.md) for the current Android build, deployment, demonstration, and troubleshooting workflow.

Release signing credentials, local configuration, API credentials, keystores, APKs, and app bundles must not be committed. See [PLAY_STORE_RELEASE.md](PLAY_STORE_RELEASE.md) for release-signing guidance.
