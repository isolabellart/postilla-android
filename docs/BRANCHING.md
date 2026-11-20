# Git branching model

## Stable integration line

- `main` is the canonical, integrated and verified project line. It must never be used as a personal working branch.
- Feature and fix work happens on short-lived `feature/*`, `fix/*`, `docs/*` and `chore/*` branches.
- Changes enter `main` through pull requests after CI and review.
- Do not force-push `main`; do not rewrite published history.

## Staging and releases

- `integration/*` is temporary and reserved for controlled repository integrations. Remove it only after the corresponding PR is merged and a backup exists.
- `staging/postilla-vX.Y.Z` identifies an exact candidate source. It is used for automated validation and manual device testing; it is not a public release.
- `release/postilla-vX.Y.Z` is a protected, version-scoped release preparation branch created from an approved staging commit. It must not receive unrelated development changes.
- `vX.Y.Z` tags and published GitHub Releases are immutable. Never reuse or move them.

## Backups and legacy branches

- `backup/*` branches are immutable recovery pointers. Do not delete or repurpose them as working branches.
- Existing historical release branches, tags and workflow snapshots remain available for traceability.
- Older naming such as `release/dark-wisp-enhanced` is legacy compatibility, not the preferred name for new Postilla releases.
- The former default branch `feature/postilla-commerce-on-rebrand` is a historical development line. Do not use it as the new canonical base after the migration to `main`.

## Required checks

1. Create and verify a backup before risky history or release operations.
2. Check the exact source SHA, package IDs and signing certificate.
3. Run unit tests and staging validation.
4. Install the candidate APK and perform manual regression tests.
5. Merge only the PR whose head SHA was actually tested.
6. Keep release publication separate from code integration. No merge or CI run should publish a stable APK or Zapstore listing by itself.
