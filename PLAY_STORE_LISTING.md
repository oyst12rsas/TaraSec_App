# TaraSec Google Play listing draft

## Listing

**App name:** TaraSec

**Category:** Tools

**Short description (80 characters maximum):**

Connect to TaraSec hotspots and manage TaraSec security services.

**Full description:**

TaraSec connects subscribers, hotspot operators and network owners to participating TaraSec services.

Subscribers can sign in to a global TaraSec account, see their credit balance and recent hotspot usage, locate nearby participating hotspots, and activate their account on the TaraSec hotspot currently connected to the phone.

Gateway owners and approved managers can view the state of their TaraSec gateway, request and manage approved access, review AI-assisted security assessments, and use available protection, recovery and assistance functions.

Nearby-hotspot discovery uses Android's location permission because Android protects Wi-Fi scan information with that permission. TaraSec uses it to display nearby TaraSec Wi-Fi networks and their signal information. The app does not request background location access.

Some functions require connection to a participating TaraSec hotspot or an approved TaraSec gateway. Availability of payment, credit, roaming and management functions depends on the participating service and account.

TaraSec is developed by Taransvar, a Norwegian nonprofit organization.

## Reviewer access notes

The app contains both subscriber and gateway-management functions. Some hotspot operations require a physical TaraSec hotspot and cannot be reproduced on an arbitrary Wi-Fi network.

Provide Google Play review with:

- an active subscriber test account;
- any gateway-manager test account and approval steps needed;
- a short screen recording of connection to a physical TaraSec hotspot;
- instructions for Google sign-in and the `tarasec://identity` return link;
- confirmation that Privacy and Delete account are available from the subscriber account screen;
- an explanation of which screens legitimately show “not connected to a TaraSec hotspot.”

Do not provide production administrator credentials.

## Preliminary Data safety inventory — verify before submission

Potential data handled by the current service includes:

- account identifiers such as email address or phone number;
- identity-provider identifier and profile fields returned by Google or Facebook;
- TaraSec authentication/session tokens;
- hotspot association and technical device/network identifiers;
- balance, credit and hotspot-usage/accounting records;
- gateway ownership or approved-manager relationships;
- diagnostics and security events submitted through TaraSec services.

Location permission is used for foreground nearby Wi-Fi discovery. The app does not declare background-location permission.

Before completing Play Console's Data safety form, verify the production APIs, server logs, retention rules, deletion behavior, transport encryption and every third-party service. The form must describe actual production behavior, not only the Android source.
