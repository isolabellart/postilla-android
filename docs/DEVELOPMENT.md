# Development guide

## Requirements

- Android Studio with Android SDK 35
- JDK 17
- Git
- Network access for Gradle dependency resolution

The Gradle wrapper pins the project Gradle version. Use `./gradlew`; do not install a different Gradle release globally.

## Get the source

```bash
git clone https://github.com/isolabellart/postilla-android.git
cd postilla-android
git switch main
```

For new work, create a dedicated branch from the updated `main`:

```bash
git switch -c feature/short-description
```

## Build and test

```bash
# Unit tests for the app's staging variant
./gradlew testStagingUnitTest

# Local debug build
./gradlew assembleDebug

# Local staging build
./gradlew assembleStaging
```

Production application ID: `com.isolabellart.darkwispenhanced`.

Staging application ID: `com.isolabellart.darkwispenhanced.staging`.

The Android namespace remains `com.darkwisp.app` for source compatibility. Debug builds add the configured debug suffix.

## Signing

Release signing properties are read from local `local.properties` or environment variables. Never commit the keystore, passwords, tokens, Bunker URLs or private keys. A local staging build without the permanent release key uses the debug key; the CI staging workflow requires the permanent key and verifies its certificate.

See [RELEASE_SIGNING.md](../RELEASE_SIGNING.md) and [RELEASE_PROCESS.md](RELEASE_PROCESS.md) before preparing a public release.

## Pull requests

Keep changes focused, include tests for protocol and data-handling changes, describe manual device checks, and identify any effect on package identity, Nostr signing, relays, wallet functions or release workflows.
