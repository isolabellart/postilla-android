# Postilla — release signing and publishing

This document records the stable Android installation identity and the protected release path.

## Current release identity

| Setting | Value |
|---|---|
| Current stable version | **1.0.39** |
| Current version code | **39** |
| Android application ID | com.isolabellart.darkwispenhanced |
| Staging application ID | com.isolabellart.darkwispenhanced.staging |
| Minimum Android version | Android 8.0 (API 26) |
| Packaged ABI | arm64-v8a |

Postilla continues the existing Dark Wisp Enhanced Android installation line. The package ID is intentionally unchanged so Android can accept signed updates to the same app.

## Permanent APK signing identity

Every public Postilla APK must use the same private keystore. Never generate a replacement key, change its alias, or change the expected fingerprint.

- Keystore file: dark-wisp-enhanced-release.jks (kept outside the repository)
- Keystore type: JKS
- Key alias: dark-wisp-enhanced-release
- Key algorithm: RSA 4096 / SHA256withRSA
- Certificate SHA-256: 86868a3659729a6531f3e8a31d5dcfe04405e2a86866f3e0eb0abb6965cdb841

Keep at least two secure backups of the keystore and passwords. Never commit the keystore, passwords, base64 keystore contents, Bunker URL, Bunker client key or personal access token.

## GitHub Actions secrets

The release and staging workflows use these repository Actions secrets:

1. DARK_WISP_ENHANCED_KEYSTORE_BASE64 — base64-encoded permanent JKS.
2. DARK_WISP_ENHANCED_STORE_PASSWORD — keystore password.
3. DARK_WISP_ENHANCED_KEY_ALIAS — dark-wisp-enhanced-release.
4. DARK_WISP_ENHANCED_KEY_PASSWORD — private-key password.
5. DARK_WISP_ENHANCED_RELEASE_TOKEN — fine-grained repository PAT used to create and publish GitHub Releases. It needs Contents: Read and write and Workflows: Read and write.
6. ZAPSTORE_SIGN_WITH — Postilla's private bunker:// connection URL.
7. ZAPSTORE_BUNKER_CLIENT_KEY — 32-byte hex client key for that Bunker connection.

Do not paste any secret into source files, issues, pull requests, logs or chat messages.

## Protected release path for v1.0.39

The signed and manually tested staging source is commit:

76abd0612b9e7752eb45eaf657c113a78ab5cdda

The official release branch is release/postilla-v1.0.39. Its workflow is:

.github/workflows/postilla-v1.0.39-release.yml

The workflow is push-triggered on that branch and performs these checks before publishing anything:

1. Confirms the release branch and that the application/build files are unchanged from the exact tested staging commit.
2. Refuses to overwrite an existing v1.0.39 tag or release.
3. Checks that all signing, GitHub and Zapstore secrets are present.
4. Performs a read-only NIP-46 handshake and confirms the expected Postilla Zapstore delegate identity.
5. Runs staging unit tests and separately builds the minified production APK.
6. Verifies package ID, version name, version code, APK signature and permanent certificate fingerprint.
7. Uploads a production APK workflow artifact.
8. Creates a GitHub Release in draft state, attaches Postilla-v1.0.39.apk, verifies the uploaded asset and its SHA-256, then publishes it as a stable release using the repository PAT.

The workflow never uses the staging APK as a public asset. The final production APK is freshly built with the non-staging package ID com.isolabellart.darkwispenhanced.

## Zapstore publication and verification

The independent Zapstore workflow is:

.github/workflows/zapstore-publish.yml

It runs on the published stable GitHub Release event. It checks the exact release tag and APK asset, uses the pinned Zapstore CLI zsp v2.0.1, verifies the application ID, version, APK hash and certificate, authenticates the Bunker identity through NIP-46, publishes the release, and checks that the exact version is indexed in the Zapstore catalog.

The GitHub release workflow uses DARK_WISP_ENHANCED_RELEASE_TOKEN rather than the automatic GITHUB_TOKEN when it publishes the release. This is intentional: the published event must trigger the separate Zapstore workflow.

The current repository default branch is feature/postilla-commerce-on-rebrand; the Zapstore workflow and zapstore.yaml metadata are read from that branch. Do not assume that a Zapstore metadata change on the release branch alone changes the workflow's metadata source.

## Preparing later releases

For each subsequent release:

1. Start from a staging commit that has passed CI and real-device testing.
2. Create a protected, version-specific release branch from that exact staging commit.
3. Keep application source and Gradle build inputs unchanged after staging approval.
4. Set a new, unused semantic tag and increment both versionName and versionCode in the new staging source before testing.
5. Update bilingual release notes and expected constants in the dedicated release workflow.
6. Confirm that the Zapstore Bunker check and required repository secrets are valid before publishing.
7. Verify both the GitHub Release asset and Zapstore catalog result.

Never reuse a published tag or version code. Never change the package ID, permanent keystore, key alias or certificate fingerprint.

## Wallet and account safety

Keep Nostr private keys, wallet recovery material and NWC connection strings private and offline. Do not include recovery material in release assets or support messages. Installing Postilla over the existing package preserves the Android app identity, but account or wallet restoration still follows the app's normal secure recovery process.
