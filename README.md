# Touch Pinball

Personal, noncommercial Android fork build of Visual Pinball X with an integrated Archive.org table downloader. Android 13+; ARM64; touch and gamepad controls from VPX.

## Use

Install the APK from Releases. Open **+ → Download from Archive.org**, search, select a table, and choose **Download & install**. Downloads display progress and can be cancelled. After installation, return to **Library** and tap the table to play.

The source is **Visual-Pinball-Collection-2025-12-29** on Archive.org, the collection used by the referenced Quest downloader. The catalog contains raw VPX tables and companions. Each download verifies size and SHA-1 from the collection manifest. The installer preserves companion paths, aligns backglass names with the table, extracts the table script, resolves literal ROM declarations, downloads matching ROM/NVRAM, and places them under the table's `pinmame/` folder. Missing ROMs, ambiguous ROM selection, or copy failures produce errors; partial downloads are cleaned up. Installed entries are tracked against the current library to avoid duplicate installs. The catalog is cached for offline browsing; downloading requires a connection.

Tables with dynamic ROM selection or failed script extraction are marked as potentially needing manual setup. Some desktop/VR tables need standalone script patches, external media, or mobile performance adjustments; installation does not certify gameplay compatibility. No content or rights are supplied by this repository. Download files only where you have permission. No accounts, DRM bypass, advertising, or billing are implemented.

## Source and build

`python3 scripts/prepare.py` checks out **vpinball/vpinball at 320985d9b056ba2620f759b4f8463956d10956ad** into `engine/`, applies checked integration patches, and copies `touch/overlay/`. This repository tracks all Touch source changes and a pinned upstream revision, rather than duplicating the upstream binary assets. Edit Touch files here; generated `engine/` is disposable. `prepare.py` fails if an integration point drifts.

The GitHub Actions **Touch Pinball APK** workflow compiles the UI, runs downloader unit tests, builds native dependencies and VPX, assembles/signs the APK, verifies native player inclusion and signing, and publishes a prerelease with checksums and complete materialized source. A native build failure never publishes an APK. `touch/development-keystore.b64` is an intentionally public **development-only** key for consistent personal sideload updates; it is not a production signing secret.

To build locally, install the prerequisites described in upstream `make/README.md`, Android SDK 35, NDK 28.2.13676358 and JDK 21. Prepare the source, run `engine/platforms/android-arm64-v8a/external.sh` from `engine/`, configure/build CMake as in the workflow, then run `./gradlew assembleMobileDebug` from `engine/standalone/android`. Restore the provided development key to `~/.android/debug.keystore` for compatible updates.

## Licensing and credits

Visual Pinball X © Randy Davis, the Visual Pinball development team, and contributors. Upstream retains mixed GPLv3+ and older noncommercial MAME-like licensing plus third-party notices; see the pinned upstream LICENSE and individual files. Touch additions are GPLv3-or-later. This fork is intended for personal noncommercial use. VPX's name and upstream credits remain in the software and source. The Archive.org integration was independently implemented after inspecting the publicly documented collection structure and the SgtBilko76/VPinball-VR downloader for interoperability; no downloader implementation was copied.

## Validation scope

Automated tests cover manifest grouping, ROM/NVRAM indexing, URL encoding, unsafe paths, malformed records, literal ROM parsing, ambiguity detection, and rejection of XML external entities. CI must pass before calling the APK built. End-to-end gameplay on a physical Android device remains a manual check; test at least one original table and one ROM-backed table, cancellation, offline retry, deleting/reinstalling a table, and a failed companion download.
