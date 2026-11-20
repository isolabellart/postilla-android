# Postilla — release process

## 1. Rules

- A public release starts from a staging candidate that passed CI and manual device testing.
- Record the staging commit and create a verifiable `backup/*` reference before preparing the release.
- Keep package IDs, permanent signing certificate, keystore and alias unchanged.
- Never reuse a published version code, Git tag or GitHub Release.
- Do not rebuild or replace a published release to repair a later Zapstore failure.
- Do not run a release or Zapstore workflow as part of ordinary integration work.

## 2. Prepare a candidate

1. Develop and merge changes into `main`.
2. Create `staging/postilla-vX.Y.Z` from the exact candidate commit.
3. Run the Postilla CI and staging workflows.
4. Install the signed Preview APK over the existing Postilla installation and complete the relevant manual regression tests.
5. Record the CI run, APK SHA-256, staging source SHA and device-test result.

## 3. Prepare the release branch

1. Create a verified backup branch at the approved staging commit.
2. Create `release/postilla-vX.Y.Z` from that exact commit.
3. Update `versionName` and `versionCode` in `app/build.gradle.kts` before the candidate is tested; never change application code or build inputs after staging approval.
4. Confirm the release tag `vX.Y.Z` does not already exist.
5. Dispatch `.github/workflows/postilla-release.yml` manually from the versioned release branch, with the exact new tag.

The workflow builds the signed production APK, verifies its package ID, version, version code and permanent signing fingerprint, then creates the stable GitHub Release with the asset named `Postilla-vX.Y.Z.apk`.

## 4. Zapstore

The dedicated `zapstore-publish.yml` workflow is separate from the Android release build. It is triggered by a published stable GitHub Release and also supports a manual dispatch with the exact tag.

Before publishing, it checks:
- the GitHub Release is stable, not draft and not a pre-release;
- the exact `Postilla-vX.Y.Z.apk` asset exists;
- package ID, version, version code, APK hash and certificate fingerprint match;
- the Zapstore delegated identity is confirmed through NIP-46.

After publication, the workflow checks that the catalog reports the same version code, APK hash and signing certificate. A successful `zsp publish` command alone is not sufficient confirmation.

Never run this workflow during reorganization, routine CI or staging validation.

## 5. Recovery

If GitHub Release succeeds but Zapstore fails, do not rebuild the APK, move the tag or recreate the release. Inspect the diagnostics and, after confirming the exact release remains correct, rerun only the Zapstore workflow with that already-published tag. Its preflight is designed to avoid duplicate publication and downgrades.

## 6. Recordkeeping

For each release record:
- source staging SHA and backup branch/SHA;
- release branch and commit;
- GitHub Actions run and conclusion;
- GitHub Release URL and asset name;
- APK SHA-256 and signing certificate SHA-256;
- Zapstore run and catalog confirmation.
