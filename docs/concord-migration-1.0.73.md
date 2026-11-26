# Concord migration — Postilla 1.0.73

## Source of truth and safety

- Integration baseline: official Postilla v1.0.72 commit `669752857c3f443383fe055f91d7e959829cadff`.
- Backup branch: `backup/postilla-1.0.72-pre-concord-20261126`.
- Working branch: `feature/concord-communities-1.0.73`.
- Staging branch: `staging/postilla-1.0.73-concord`.
- Do not use the stale `main` branch as the implementation baseline.
- No production release is created by this migration.

## Quartz compatibility spike

Quartz 2.15.1 is the published dependency selected for the first Android validation. Its source contains the Concord community factory, fragmented private community list, Control Plane fold, channel chat/envelope, invite bundle/link, role/authority and rekey modules.

The earlier November 18 integration attempt used Quartz 2.15.1 but stopped at `:app:checkStagingAarMetadata`: the app had compileSdk 35 and AGP 8.13.2, while Quartz/AndroidX required compileSdk 37 and AGP 9.1.0 or later. It never reached Kotlin compilation. The current spike aligns Kotlin 2.4.21, AGP 9.1.1, Gradle 9.3.1, JDK 21 and compileSdk 37. minSdk 26 and targetSdk 35 remain unchanged.

The Quartz dependency excludes its transitive secp256k1 group for this initial build check so the app retains its existing 0.16.0 native crypto pin. This is a compatibility hypothesis until CI compiles and runtime tests pass; it is not yet a verified runtime decision.

## Signer boundary

`PostillaQuartzSigner` forwards event signing and NIP-44 to the active Postilla signer, including NIP-55 remote signer. Unsupported Quartz surfaces fail closed. No private-key fallback is permitted.

## Integration gates

1. Staging compile, unit tests, package id/version and permanent APK certificate.
2. Concord community genesis and self-encrypted kind-33302 list persistence.
3. Interop test: create/join and exchange channel messages with Vector.
4. Invite links, channel access, roles, removal and key rotation.
5. Remove NIP-29 group routes and code only after parity tests pass and legacy chat data has an export/recovery path.

The Coinos repository URL supplied for reference was not accessible from the build environment; its screenshot is used only as the UI layout reference. The protocol implementation is based on Quartz/Concord and cross-client conformance, not copied Coinos source.
