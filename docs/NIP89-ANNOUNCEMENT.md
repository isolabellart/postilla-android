# Postilla — NIP-89 announcement preparation

**Status:** prepared, not published  
**Checked:** 20 November 2026

NIP-89 is marked draft/optional. A kind 31990 handler announcement can improve discovery, but it must not be published with an unverified author key or a handler URI that receiving clients cannot use.

## What is already implemented

Postilla already adds the canonical `client` tag (`["client", "Postilla"]`) to events when the user has client-tagging enabled. The user can opt out. This identifies the client on events; it is not a substitute for a kind 31990 application-handler announcement.

## Information to publish

- Event kind: `31990`
- Stable `d` identifier: `postilla-android`
- App name: `Postilla`
- Application ID: `com.isolabellart.darkwispenhanced`
- Repository: `https://github.com/isolabellart/postilla-android`
- Platform: Android
- Candidate supported kinds: `1`, `20`, `21`, `22`, `1068`, `30023`, `30402`

The candidate kind list must be reviewed against actual deep-link handling before publication. A kind tag should only be included when Postilla can open that event type reliably.

## Publication requirements

1. Confirm the public key of the official Postilla publisher account. The developer contact key in `CrashHandler.kt` is only a candidate and must not be assumed to be the Zapstore/release publisher without checking.
2. Confirm the Android handler URI template. The app currently registers the `nostr:` URI scheme, but NIP-89's platform-specific Android tag convention is not universally implemented by clients. Test the chosen template with at least one independent Nostr client before announcing it.
3. Build and sign a kind 31990 event with the confirmed publisher key. Do not put a private key, seed phrase, or API credential in this repository.
4. Publish to several well-known relays and verify retrieval by author, kind, and `d` identifier. Record the event ID, author pubkey, publication timestamp, and relays that returned it.
5. Recheck discovery after publication. Do not mark the audit finding as resolved merely because this draft exists.

## NIP-89 reference

[NIP-89 — Recommended Application Handlers](https://github.com/nostr-protocol/nips/blob/master/89.md)

## Why this is not yet marked complete

The repository does not contain the publisher's private signing material, and the report's observation alone does not establish which public key should author the announcement. Publishing from an arbitrary logged-in user's key would incorrectly attribute the official app descriptor. The remaining step is a deliberate publication by the verified Postilla publisher identity.
