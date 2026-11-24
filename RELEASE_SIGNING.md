# Postilla — release signing and publishing

## Current Android identity

| Setting | Value |
|---|---|
| Current verified source line | Postilla 1.0.42 |
| Version code | 42 |
| Production application ID | `com.isolabellart.darkwispenhanced` |
| Staging application ID | `com.isolabellart.darkwispenhanced.staging` |
| Minimum Android | Android 8.0 (API 26) |
| Target SDK | 35 |
| Packaged ABI | `arm64-v8a` |

The Android namespace remains `com.darkwisp.app` for source compatibility. The production application ID is intentionally preserved from the former Dark Wisp Enhanced installation line so correctly signed Postilla builds can update that same app. The original upstream Dark Wisp app remains separate.

## Permanent APK signing identity

Every production and CI staging update must preserve the established signing identity. Never generate a replacement keystore, change the alias or change the expected certificate fingerprint.

- Keystore file: `dark-wisp-enhanced-release.jks` (outside the repository)
- Keystore type: JKS
- Key alias: `dark-wisp-enhanced-release`
- Key algorithm: RSA 4096 / SHA256withRSA
- Certificate SHA-256: `86868a3659729a6531f3e8a31d5dcfe04405e2a86866f3e0eb0abb6965cdb841`

Keep secure backups of the keystore and passwords. Never commit the JKS file, passwords, base64 keystore contents, access tokens or wallet secrets.

## GitHub Actions secrets

The existing secret names are retained intentionally for compatibility. Do not rename or recreate them during repository cleanup.

- `DARK_WISP_ENHANCED_KEYSTORE_BASE64`
- `DARK_WISP_ENHANCED_STORE_PASSWORD`
- `DARK_WISP_ENHANCED_KEY_ALIAS`
- `DARK_WISP_ENHANCED_KEY_PASSWORD`
- `DARK_WISP_ENHANCED_RELEASE_TOKEN`

The release token must be a fine-grained PAT limited to this repository with **Contents: Read and write** permission, used only by the final GitHub Release creation step. Workflow execution itself uses the read-only `GITHUB_TOKEN`. The PAT is not a signing password and must never be used as one.

## Breez Spark SDK integration key

The Spark wallet requires the repository Actions secret `BREEZ_API_KEY`.

- Supply the API key exactly as issued by Breez (the SDK expects a Base64-encoded DER X.509 certificate).
- Staging and release workflows validate that the value decodes to a Breez-issued certificate before compiling.
- Gradle accepts `BREEZ_API_KEY` from the environment; local development may alternatively use `breez.api.key` in the ignored `local.properties` file.
- The value is embedded in the Android application because the Spark SDK consumes it client-side. Treat it as an application integration credential, not as a private key or wallet secret; assume it can be extracted from a distributed APK.
- Never commit it to source control or print it in CI logs.

Zapstore secrets are separate:
- `ZAPSTORE_SIGN_WITH`
- `ZAPSTORE_BUNKER_CLIENT_KEY`

Do not paste credentials into source files, issues, pull requests, logs or chat messages.

## Workflow entry points

The current workflow map and exact triggers are documented in [docs/CI_CD.md](docs/CI_CD.md).

- Staging: `.github/workflows/postilla-staging.yml` builds a Preview APK using the staging application ID and verifies the permanent certificate.
- Production: `.github/workflows/postilla-release.yml` is manual-only and accepts only a branch named `release/postilla-vX.Y.Z`.
- Zapstore: `.github/workflows/zapstore-publish.yml` runs only after a stable GitHub Release is published or when manually dispatched with the exact published tag.

The historical v1.0.42 workflow is preserved in `docs/archive/github-workflows/` and in its original release branch/tag. Archived workflows are not active Actions entry points.

## Local signing

Gradle reads signing properties from local `local.properties` or environment variables:
`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`.

Keep `local.properties` outside version control. Local staging builds without the production signing properties fall back to the debug key; CI staging builds require the permanent key and verify its fingerprint.

## Wallet and user-data safety

Postilla is separate from the upstream Dark Wisp app. Installing it does not migrate data from that separate application. Users must import or restore accounts and wallet access through the app's normal secure recovery flow. Never ask users to share recovery phrases, private keys or wallet connection strings.
