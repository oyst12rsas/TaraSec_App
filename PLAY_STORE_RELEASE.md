# Google Play release

TaraSec uses Google Play App Signing. Keep the upload key and all passwords outside this repository.

## One-time upload key setup

Run this on the release computer and answer the `keytool` prompts. Keep a second encrypted backup of the resulting JKS file.

```bash
clear

mkdir -p "$HOME/.config/tarasec"
keytool -genkeypair \
  -keystore "$HOME/.config/tarasec/tarasec-upload-key.jks" \
  -alias tarasec-upload \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Create `keystore.properties` in the repository root:

```properties
storeFile=/home/USERNAME/.config/tarasec/tarasec-upload-key.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=tarasec-upload
keyPassword=YOUR_KEY_PASSWORD
```

The file and common keystore extensions are ignored by Git. Never commit either file.

## Build and verify the bundle

```bash
clear

cd ~/TaraSec_App
./gradlew clean bundleRelease
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
```

Upload `app/build/outputs/bundle/release/app-release.aab` to **Test and release → Testing → Internal testing** in Play Console.

The release build is signed only when `keystore.properties` exists. If it is absent, Gradle may create an unsigned bundle that cannot be uploaded. Every Play release must use a higher `versionCode`.

## Required public pages

- Privacy policy: https://tarasec.org/app/privacy.html
- Account deletion: https://tarasec.org/app/delete-account.html

Before production, replace the account-deletion request process with a tested authenticated deletion endpoint if it is not yet deployed, and confirm that the public support address receives requests.
