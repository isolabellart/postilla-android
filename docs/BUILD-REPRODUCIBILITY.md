# Postilla — build integrity and reproducibility

**Status reviewed:** 20 November 2026

## What is currently verified

The staging workflow:

1. checks that the permanent APK signing secrets are present;
2. checks out the source commit;
3. uses JDK 17 and the Gradle wrapper;
4. runs `testStagingUnitTest`;
5. builds `assembleStaging`;
6. verifies the staging application ID, version name, version code, and permanent signing certificate;
7. computes a SHA-256 digest, uploads the signed APK as a GitHub Actions artifact, and creates a GitHub build-provenance attestation for the APK.

The release workflow also applies release safeguards. The Zapstore and GitHub APKs for the audited v1.0.42 release were reported to have the same SHA-256 digest.

**Important distinction:** matching hashes prove that the two distribution channels supplied the same bytes. They do not prove that those bytes can be independently rebuilt from the published source.

## Reproducibility status

Postilla does not yet claim fully reproducible builds. The following work remains before making that claim:

- Pin every third-party GitHub Action used in build and release workflows to a reviewed immutable commit SHA, rather than a movable major-version tag.
- Enable and commit Gradle dependency verification metadata, and review updates to dependency checksums.
- Record the exact JDK, Gradle, Android Gradle Plugin, SDK/build-tools versions and source commit for every release.
- Run two clean builds from the same commit in separate fresh environments, then compare the resulting APKs. If signatures or archive metadata differ, compare unsigned APK payloads and document the expected differences.
- Extend the staging APK provenance attestation to the official release workflow and preserve the SHA-256 digest.
- Provide a documented independent rebuild procedure for contributors.

A provenance attestation helps connect an artifact to a build workflow; it is not, by itself, proof that another builder can reproduce the artifact.

## Release verification checklist

For every candidate release:

- [ ] Source commit and release tag are recorded.
- [ ] Staging unit tests pass.
- [ ] Staging APK package, version and permanent certificate checks pass.
- [ ] APK SHA-256 is recorded.
- [ ] A second clean build has been compared with the first.
- [ ] GitHub and Zapstore distribution files have matching SHA-256 digests.
- [ ] Release provenance and build environment are published.
- [ ] No release is published until manual device tests are complete.

## Scope

This document describes build-integrity controls. It does not claim an independent security audit and does not replace a third-party review of key management, cryptographic flows, wallet integration, relay behavior, or network traffic.
