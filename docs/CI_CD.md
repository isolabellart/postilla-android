# CI and release workflows

This document describes the active workflow entry points. Version-specific workflows from earlier releases are preserved under `docs/archive/github-workflows/` for audit and are not discovered by GitHub Actions.

## Active paths

| Workflow | Trigger | Purpose |
|---|---|---|
| `.github/workflows/postilla-ci.yml` | Pull requests to `main`; pushes to `main` and `integration/**` | Unit tests only. No signing, artifact publication or release creation. |
| `.github/workflows/postilla-staging.yml` | Pushes to the current integration branch or `staging/**`; manual dispatch | Signed staging build, unit tests, package/version/certificate verification and CI artifact upload. Never creates a GitHub Release. |
| `.github/workflows/postilla-release.yml` | Manual dispatch only | Protected release build from `release/postilla-vX.Y.Z`; verifies package, Gradle version, APK signature and permanent certificate before creating a GitHub Release. |
| `.github/workflows/zapstore-publish.yml` | Published GitHub Release or manual dispatch with an exact tag | Checks the exact stable APK and Zapstore metadata, verifies the delegated NIP-46 identity, publishes and checks the catalog. This is a public publishing action. |
| `.github/workflows/zapstore-bunker-check.yml` | Manual dispatch only | Read-only NIP-46 identity check. It does not sign or publish an event. |

## Release safeguards

- A push to `main`, a feature branch or an integration branch cannot publish a stable release.
- The release workflow accepts only a versioned `release/postilla-vX.Y.Z` branch and a new `vX.Y.Z` tag.
- The production package remains `com.isolabellart.darkwispenhanced`; staging remains `com.isolabellart.darkwispenhanced.staging`.
- The permanent APK certificate SHA-256 remains `86868a3659729a6531f3e8a31d5dcfe04405e2a86866f3e0eb0abb6965cdb841`.
- Zapstore publication requires an already-published, stable GitHub Release with the exact `Postilla-vX.Y.Z.apk` asset. The preflight checks app ID, version, version code, APK hash and signing certificate.
- The staging artifact is for testing only. It is not a public production release.

## Historical workflow snapshots

Older branch- and version-specific YAML files are retained in `docs/archive/github-workflows/`. Their corresponding release branches, tags, CI runs and Git history are also retained. Do not reactivate or dispatch an archived workflow without first checking its pinned source SHA, version, triggers and publication side effects.

## Secrets

Only GitHub Actions secret names are documented. Values must remain in repository secrets or a local password manager; never add them to the source tree.

Android release secrets retain their legacy names for compatibility:
- `DARK_WISP_ENHANCED_KEYSTORE_BASE64`
- `DARK_WISP_ENHANCED_STORE_PASSWORD`
- `DARK_WISP_ENHANCED_KEY_ALIAS`
- `DARK_WISP_ENHANCED_KEY_PASSWORD`
- `DARK_WISP_ENHANCED_RELEASE_TOKEN`

Zapstore:
- `ZAPSTORE_SIGN_WITH`
- `ZAPSTORE_BUNKER_CLIENT_KEY`

The release and Zapstore workflows are intentionally separate. Do not run the Zapstore workflow during ordinary development or repository integration.
