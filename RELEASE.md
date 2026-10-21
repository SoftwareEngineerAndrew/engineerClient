# Committing and releasing

How changes go from the working tree to a GitHub release on
https://github.com/duetigh/ADS. Check this file before every commit and
every push.

## Rules

- **Never push automatically.** Commits stay local until the owner
  explicitly asks for a push. Committing does not imply pushing.
- **Push only to `origin` (duetigh/ADS).** Never push to `upstream`
  (SoftwareEngineerAndrew/engineerClient); its push URL is set to `DISABLED`
  locally.
- **Every push to `main` is a release.** The `release` workflow builds the
  jar and publishes a GitHub release for the version in
  `gradle.properties`. A push that skips the release steps below fails CI.
- Both upstream mods (Engineer Client and Rustclear) are
  All-Rights-Reserved. Keep the repo and its releases private unless their
  authors agree otherwise.

## Building locally

Needs Java 25 and the Odin jar (compile-only, kept out of git). Fetch Odin
once:

```sh
gh run download 37808382947 -R odtheking/OdinFabric -n Odin-0.3.7.jar -D libs/odin
```

## Every commit

1. **Build passes.**
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew build
   ```
   For sim, engine or gameplay changes, also check the change in game
   (`./gradlew runClient`).
2. **Log the change in `CHANGELOG.md`** under `## [Unreleased]`, in the
   matching section. Write it for a player or user of the mod, one line per
   change:
   - `### Added` – new features, commands, settings
   - `### Changed` – behaviour that works differently now
   - `### Performance` – speedups (include before → after numbers)
   - `### Fixed` – bugs fixed
   - `### Removed` – features, files or tooling taken out

   Pure housekeeping with no visible effect (formatting, comments) needs no
   entry.
3. **Write the commit message.**
   - Subject: short, imperative, no trailing period
     (`Add a Practice tile to the ADS menu`).
   - Body: what changed and why, plus measurements for performance work.
   - Commits written with Claude end with the `Co-Authored-By:` line.
4. **Commit one logical change at a time.** Do not push.

## Every push (release)

Only when the owner asks to push:

1. **Pick the version** (semantic versioning, still pre-1.0):
   - patch (`0.2.0` → `0.2.1`): only fixes and performance work
   - minor (`0.2.1` → `0.3.0`): anything added, removed or changed
2. **Bump `mod_version`** in `gradle.properties`. (The first release is
   `0.1.0`, which is already set; no bump for it.)
3. **Cut the changelog.** Rename `## [Unreleased]` to
   `## [x.y.z] - YYYY-MM-DD`, drop empty sections, and add a fresh empty
   `## [Unreleased]` above it.
4. **Commit** as `Release x.y.z` (gradle.properties + CHANGELOG.md only).
5. **Push:** `git push origin main`.
6. **Watch the release:**
   ```sh
   gh run watch -R duetigh/ADS
   gh release view vx.y.z -R duetigh/ADS
   ```

## What the release workflow does

`.github/workflows/release.yml` runs on every push to `main`:

1. Reads `mod_version` and fails if the tag `vX.Y.Z` already exists
   (version not bumped).
2. Takes the `## [X.Y.Z]` section of `CHANGELOG.md` as the release notes
   and fails if it is missing or empty (changelog not cut).
3. Appends every commit since the previous `v*` tag as a "Commits" list.
4. Downloads the Odin jar, builds with JDK 25 and publishes release
   `vX.Y.Z` titled `ADS X.Y.Z`, with `ads-X.Y.Z.jar` attached.

The tag is only created when the release succeeds, so after a failed run,
fix the problem, commit, and push again with the same version.

Pushes to other branches and pull requests only run the `build` workflow
(build check plus the jar as a workflow artifact, no release).
