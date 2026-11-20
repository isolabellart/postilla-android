# Git branching model

## Stable integration line

- `main` is the canonical, integrated and verified project line. It must never be used as a personal working branch.
- Feature and fix work happens on short-lived `feature/*`, `fix/*`, `docs/*` and `chore/*` branches.
- Changes enter `main` through pull requests after CI and review.
- Do not force-push `main`; do not rewrite published history.

## Staging and releases

- `integration/*` is temporary and reserved for controlled repository integrations. Remove it only after the corresponding PR is merged and a backup exists.
- `staging/postilla-vX.Y.Z` identifies an exact candidate source. It is used for automated validation and manual device testing; it is not a public release.
- `release/postilla-vX.Y.Z` is a version-scoped release preparation branch created from an approved staging commit. It must not receive unrelated development changes. Enforce this with GitHub branch rules; a naming convention alone is not protection.
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


## GitHub repository settings after integration

After the integration PR has been merged and verified:

1. Set the repository default branch to `main` in **Settings → General → Default branch**. The former default, `feature/postilla-commerce-on-rebrand`, is a historical documentation/rebrand branch, not the current application source.
2. Add a ruleset or branch protection rule for `main`: require pull requests, block force pushes and deletion, and require the `Postilla CI / Staging variant unit tests` check before merging.
3. Protect `release/postilla-v*` branches against force pushes and deletion, and limit updates to the intended release maintainers.

The GitHub connector used during this reorganization could not inspect branch protection (GitHub returned 403) and does not expose an API for changing the repository default branch or rulesets. These settings are therefore **not claimed as completed** and must be applied in the GitHub web settings after the merge.
