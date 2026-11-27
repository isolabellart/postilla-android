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

## Implementation progress — 2026-11-26

- Added `ConcordCommunityRepository` on the feature branch.
- Creation uses Quartz `ConcordCommunityFactory` for the CORD-02 genesis and the Quartz kind-33302 fragmented Community List codec.
- Membership key material (community root and staff control root included) is placed only in the NIP-44 self-encrypted membership fragments. The local journal stores signed event JSON, not plaintext community secrets.
- Genesis wraps and membership fragments are journaled before relay publication. Publication waits for an actual NIP-20 OK through `RelayPool.publishWithConfirmation`; unconfirmed event IDs are returned as pending and remain journaled.
- Added a JVM unit test for fragmented-list packing and base64url wire encoding of 32-byte key material.

This is an initial repository slice, not yet a user-facing Community chat. It does not yet restore the journal into live community state, subscribe to Control/Chat planes, implement channel messaging, or wire a Community UI. Those remain explicit implementation gates; no staging APK from this slice is intended for community testing.

- CI validation request for the repository slice is recorded on the staging branch; this build is compile/test validation only, not a Community-chat test APK.

- Added a first CORD-03 chat transport: public channel keys use Quartz `ConcordChannelKeys.publicChannel`; messages are kind-9 rumors wrapped as encrypted kind-1059 stream events, with channel/epoch binding checked on receive.
- Added local membership recovery by decrypting and merging the journaled kind-33302 fragments.
- Added a dedicated Concord tab, community creation/list UI, general-channel screen, and navigation. The existing NIP-29 Rooms tab remains available and unchanged.
- Added a Vector invite-link flow using `https://vector.chat`, Quartz kind-13303 private Invite List and kind-33301 encrypted bundle. The Invite List is merged from all configured read relays and must be confirmed before the bundle is published; the bundle must also receive relay confirmation before the URL is shared.
- These flows are implementation work in progress. Remote discovery/join, full control-plane folding, role/invite administration, key rotation and end-to-end Vector device testing remain open.

- Added invite redemption: Postilla parses a Vector link, resolves and validates the kind-33301 bundle, verifies the read-only Control Plane through Quartz, discovers the live `general` channel, and merges the membership into the user's fragmented kind-33302 list.
- Community creation now reads and merges the existing list before publishing, using Quartz `ConcordListFragmentSet.planWrites` so incomplete fragment sets are updated safely without overwriting memberships that were not fetched.

- Added member Guestbook discovery, Quartz role/grant creation and assignment, and Control Root delivery to staff grants. Staff invite redemption opens and verifies the delivered Control Root against the invite's `control_pk`.
- Added moderation paths: public-community kicks remain Guestbook moderation; private kicks are coupled to CORD-06 refounding. Bans follow Quartz's public/private rule, and staff demotion/role deletion triggers a whole-community refounding when staff write authority is removed.
- Added retry-safe refounding: the pending root/control-root pair is stored in encrypted preferences; signed control, registry, invite, rekey, membership, kick and new-Guestbook events are journaled before publication and replayed with the same IDs after failure. Private-channel rotations are explicitly refused until their independent channel keys can be rekeyed.

## Concord functional follow-up — staging 1.0.74

The 1.0.73 device test exposed that the first visible Concord screen was not yet a complete community experience. The following remediation is isolated on the 1.0.74 staging branch:

- Community List reads now wait for the configured relays but can return the fragments received from relays that completed before the timeout. A warning is retained for the UI when synchronization is partial; a failed refresh no longer clears the last displayed list.
- Membership-list writes remain fail-closed. A partial read is writable only when at least one kind-33302 fragment was observed, allowing Quartz to perform its scoped fragment update. If no fragment was seen and not every read relay completed, creation/join/refounding is refused rather than assuming that the user's list is empty.
- When a membership arrives from another device or Concord client, opening the community attempts to recover the #general channel id from the read-only Control Plane and persist it locally. The chat collector restarts when that id becomes available.
- The Concord screen now offers a direct entry to Vector Hub, the public directory for listed communities, and an explicit refresh action for the private membership list. Hub discovery is external; this client does not yet embed or cache the Hub catalogue.
- Added unit coverage for the fail-closed partial-list write rule.

These are code changes awaiting CI and manual device verification. In particular, successful compilation does not prove that the relay set returns the complete private list or that Control Plane reads work against real Vector communities.
