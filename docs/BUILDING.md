# Crew Radio — building and releasing

Step by step, from an empty machine to a published Release. The [README](../README.md) tells the
crew how to install a build; [ARCHITECTURE.md](ARCHITECTURE.md) tells a developer how the code
works; this page is only about the procedures.

Commands are written for a POSIX shell: Git Bash on Windows (it comes with Git for Windows),
or any shell on macOS and Linux. Most of them are the same everywhere — Gradle, `adb`, `git`,
`gh`, `keytool` and `npm` differ only in the wrapper, `.\gradlew.bat` in PowerShell and `cmd`
for `./gradlew`. The few steps that pipe through `grep`, `sed`, `tr`, `base64` or `sha256sum`
do need Git Bash or WSL; where a native form is worth having it is given beside the other,
for the signing variables (5.2), the keystore's base64 (6) and a download's checksum (8).

## 1. What the machine needs, once

1. **JDK 17.** The build compiles on a real JDK 17 toolchain and deliberately will not download
   one (`org.gradle.java.installations.auto-download=false` in `gradle.properties`). Without it
   Gradle stops with *Cannot find a Java installation on your machine matching:
   {languageVersion=17…}*.

   ```sh
   winget install EclipseAdoptium.Temurin.17.JDK     # Windows
   brew install --cask temurin@17                    # macOS
   sudo apt install openjdk-17-jdk                   # Linux (Debian, Ubuntu)
   ```

   Leave `JAVA_HOME` alone: Gradle finds the 17 in the standard install location by itself, and a
   newer JDK in `JAVA_HOME` still runs Gradle itself. A JDK installed somewhere unusual is named
   with `-Porg.gradle.java.installations.paths=<dir>`.
2. **Android SDK with platform 37**, plus build-tools (Android Studio installs both; the
   command-line tools do it with `sdkmanager "platforms;android-37" "build-tools;37.0.0"`).
   Point the build at it with the `ANDROID_HOME` environment variable, or write a `sdk.dir` line
   into `local.properties` at the repository root — that file is git-ignored and belongs to the
   machine, not to the project.
3. **A full git clone.** `versionCode` is the commit count, so a shallow clone would produce a
   lower version than the last Release and phones would refuse it as a downgrade. A
   release-signed build from a shallow clone is refused outright; `git fetch --unshallow` fixes it.
4. **`adb`** (in the SDK's `platform-tools`) to put builds on a phone.
5. **Node 24 or newer**, only if you also work on the Signal K plugin (section 9).

Check it:

```sh
./gradlew --version          # Gradle 9.7, and the JVM it found
./gradlew printVersion       # 1.<commit count> — also written to app/build/version.txt
```

Nothing else is fetched blind: the Gradle distribution is checked against the checksum in
`gradle/wrapper/gradle-wrapper.properties` and every dependency against
`gradle/verification-metadata.xml` (see section 10).

## 2. Debug build and install — the everyday loop

1. Build:

   ```sh
   ./gradlew assembleDebug
   ```

   The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
2. Plug in a phone with USB debugging switched on and check it is seen:

   ```sh
   adb devices
   ```
3. Install it:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   With more than one phone attached, name it: `adb -s <serial> install -r …`.
4. If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (a signature mismatch), a
   release-signed build is already on the phone. **Note the channel key down first** — it is
   excluded from cloud backup and from device transfer on purpose — then
   `adb uninstall fi.crewradio` and install again.
5. Real testing needs two or more physical phones. The emulator has neither Bluetooth nor
   Wi‑Fi Aware, and `MediaCodec` behaviour can only be trusted on a device.

Android Studio does the same thing: open the folder in a release that supports Android Gradle
Plugin 9.4 and press Run.

## 3. Tests and lint, before pushing

```sh
./gradlew testDebugUnitTest        # report: app/build/reports/tests/testDebugUnitTest/index.html
./gradlew lintRelease              # report: app/build/reports/lint-results-release.html
```

Both run in CI on every push and pull request, and `lintRelease` is a gate: `lint.abortOnError` is
on, so an error fails the build. Warnings are reported, not fatal. Suppress an issue only inline,
with a comment saying why.

The unit tests are pure Kotlin (JUnit 4) with no Android runtime, and cover the packet format and
its replay window, the hello payload, the ingress pipeline, settings rules, the rate limiter,
backoff, the send queue, the LAN addressing and peer table, the Aware discovery tag, the Bluetooth
tie-break, the mixer, decimator, concealment, tones, the sequence tracker and the voice gate.
Anything with a transport or a codec needs real phones.

## 4. Release build with the debug key

This is what every push and pull request gets from CI as a workflow artifact: a real release build
(R8-shrunk, with class and method names kept so a crash trace reads without a mapping file), signed
with the debug key because the release key never leaves `main`.

1. Make sure none of the `CREWRADIO_*` variables of section 5 are set in the shell, and that
   `app/release.keystore` does not exist.
2. Build:

   ```sh
   ./gradlew assembleRelease
   ```
3. Read the one line the build prints about signing. It is there so nobody reads *BUILD
   SUCCESSFUL* as *release-signed*:

   ```text
   Release signing: debug key (no release keystore; see README, Releases)
   ```
4. The APK is `app/build/outputs/apk/release/app-release.apk`. Install it the way section 2
   installs the debug one — and note that Android will not replace a release-signed install with a
   debug-signed one, or the reverse, so uninstall first when switching.

## 5. The release keystore

### 5.1 Creating one — once per crew, and never again

A new key means every phone must uninstall and reinstall, losing its channel key. Create one only
if there is none, and **back the file up** somewhere that is not this machine.

1. Make a directory outside the repository. The maintainer keeps it in `~/.crewradio/`
   (`%USERPROFILE%\.crewradio\` on Windows). `*.keystore`, `*.jks` and `keystore.properties` are
   git-ignored, but the safe habit is that the key is never under the repository at all.
2. Create the key with `keytool`, which ships with the JDK:

   ```sh
   keytool -genkeypair -v \
     -keystore ~/.crewradio/crewradio.keystore \
     -storetype PKCS12 \
     -alias crewradio \
     -keyalg RSA -keysize 4096 \
     -validity 10000
   ```

   `keytool` asks for a store password (used again as the key password with PKCS12) and for the
   name fields; any honest answer will do, they are only shown in the certificate. The alias
   `crewradio` is what the build assumes when `CREWRADIO_KEY_ALIAS` is unset, and 10000 days is
   about 27 years — an APK signing key should outlive the phones.
3. Write the password down where the crew's other secrets live. There is no recovery.

### 5.2 The environment variables the build reads

`app/build.gradle.kts` reads these from the environment and nowhere else — there is no properties
file to commit by accident.

| Variable | What it is | Default |
| --- | --- | --- |
| `CREWRADIO_KEYSTORE` | Path to the keystore file | `app/release.keystore` |
| `CREWRADIO_KEYSTORE_PASSWORD` | The store password | none — no password, no release signing |
| `CREWRADIO_KEY_ALIAS` | The key's alias inside the store | `crewradio` |
| `CREWRADIO_KEY_PASSWORD` | The key's own password | the store password |

Two guards are worth knowing before you hit them:

* **A keystore named but unusable is an error, not a fallback.** If `CREWRADIO_KEYSTORE` is set and
  the file is missing or the password is empty, the build stops. A typo would otherwise sign with
  the debug key without a word.
* **A shallow clone is refused** for a release-signed build, because its commit count is wrong.

Set them for the shell you are about to build in, rather than in a startup file:

```powershell
# PowerShell
$env:CREWRADIO_KEYSTORE = "$env:USERPROFILE\.crewradio\crewradio.keystore"
$env:CREWRADIO_KEYSTORE_PASSWORD = (Get-Credential -UserName crewradio -Message "keystore password").GetNetworkCredential().Password
```

```sh
# Git Bash, macOS, Linux — `read -s` keeps the password out of the shell history
export CREWRADIO_KEYSTORE="$HOME/.crewradio/crewradio.keystore"
read -s -p "keystore password: " CREWRADIO_KEYSTORE_PASSWORD; echo
export CREWRADIO_KEYSTORE_PASSWORD
```

### 5.3 Building release-signed locally

1. Set the variables (5.2) in the shell.
2. Build:

   ```sh
   ./gradlew assembleRelease
   ```
3. Check the signing line names the crew's key, not the debug one:

   ```text
   Release signing: crew release key /home/you/.crewradio/crewradio.keystore
   ```
4. The APK is again `app/build/outputs/apk/release/app-release.apk`. `./gradlew printVersion`
   says which version it is.

A local release-signed APK is for testing an upgrade path, or for a phone that cannot reach
GitHub. The APKs the crew installs come from the Releases page, built by the workflow (section 7).

### 5.4 The certificate fingerprint

The release workflow refuses to publish an APK that is not signed with the crew's certificate, and
compares against the repository variable `CREWRADIO_CERT_SHA256`: lowercase hex, no colons.

From a signed APK, with `apksigner` from the SDK's `build-tools` (`apksigner.bat` on Windows):

```sh
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

or straight from the keystore, converting keytool's colon-separated uppercase into the form the
workflow wants:

```sh
keytool -list -v -keystore ~/.crewradio/crewradio.keystore -alias crewradio \
  | grep -i "SHA256:" | head -1 | sed 's/.*SHA256: *//' | tr -d ':' | tr 'A-F' 'a-f'
```

## 6. Teaching GitHub Actions the key, once

The `release` job fails closed: without these secrets it stops rather than publishing a
debug-signed APK.

1. Base64 the keystore into a file:

   ```sh
   base64 -w0 ~/.crewradio/crewradio.keystore > keystore.b64      # Linux, Git Bash
   base64 -i ~/.crewradio/crewradio.keystore -o keystore.b64      # macOS
   ```

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.crewradio\crewradio.keystore")) |
     Set-Content -NoNewline keystore.b64
   ```
2. Set the four secrets and the one variable — with the `gh` CLI, or by hand under *Settings ›
   Secrets and variables › Actions*. The three `gh secret set` calls without a file prompt for the
   value:

   ```sh
   gh secret set CREWRADIO_KEYSTORE_BASE64 < keystore.b64
   gh secret set CREWRADIO_KEYSTORE_PASSWORD
   gh secret set CREWRADIO_KEY_ALIAS
   gh secret set CREWRADIO_KEY_PASSWORD
   gh variable set CREWRADIO_CERT_SHA256 --body "<the fingerprint from 5.4>"
   ```
3. Delete `keystore.b64`. It is the signing key in another dress.
4. Confirm: the next push to `main` should reach the `release` job's *Verify the APK is signed with
   the release certificate* step and print the same fingerprint.

## 7. Publishing a release

Nobody bumps a number and nobody uploads an APK by hand. `versionCode` is the commit count and
`versionName` is `1.<count>`, both taken from git by `app/build.gradle.kts`, so **merging to
`main` is the release procedure**:

1. Open a pull request. The `build` job runs the unit tests, `assembleRelease` with the debug key
   and Android Lint, with a read-only token and no secrets, and attaches the APK as a workflow
   artifact for testing.
2. Merge it to `main`. Now three jobs run in a row (`.github/workflows/build.yml`):
   * `build` — as above.
   * `release` — decodes the keystore from the secrets, runs `assembleRelease sbom printVersion`,
     deletes the keystore, packs the Signal K plugin (`npm pack --ignore-scripts`, deliberately
     after the key is gone), verifies the signer certificate against `CREWRADIO_CERT_SHA256`, and
     attests build provenance over all four files. Its token cannot write to the repository.
   * `publish` — the only job with a write token, and it does nothing but `gh release create` for
     `v<version>` from the files `release` handed it. No checkout, no Gradle, no third-party code.
3. Watch it land, if you want to:

   ```sh
   gh run watch
   gh release view "v$(./gradlew -q printVersion | tail -1)"
   ```
4. The Release page then carries `CrewRadio-<version>.apk`, its `.sha256`, the CycloneDX SBOM
   `.sbom.cdx.json` and `signalk-crewradio-<plugin version>.tgz` (the plugin keeps its own
   semantic version, which is not the app's `1.<count>`).
5. Tell the crew. Every phone on a channel must run the same build — the app tags a phone on
   another one as **OLD BUILD** or **NEWER BUILD** on the Status screen.

If a job fails, fix it and push again: a new commit is a new version. Re-running a failed
`publish` for the same commit is safe; re-running one that already created the Release is not, and
fails on the existing tag.

## 8. Verifying a published release

Anyone can check what the Releases page offers, without trusting it:

```sh
sha256sum -c CrewRadio-<version>.apk.sha256
gh attestation verify CrewRadio-<version>.apk --repo KEGustafsson/crew-radio
apksigner verify --print-certs CrewRadio-<version>.apk
```

`sha256sum` is the one line with no PowerShell equivalent; there the checksum is compared by eye
against the `.sha256` file, which holds it followed by the file name:

```powershell
Get-FileHash CrewRadio-<version>.apk -Algorithm SHA256 | Format-List
Get-Content CrewRadio-<version>.apk.sha256
```

The attestation proves the file came out of this repository's workflow, from the commit named in
the release notes.

## 9. The Signal K plugin

In `sk-plugin/` (Node 24+, CommonJS, one dependency):

```sh
cd sk-plugin
npm install                # only for the runtime dependency; there is no lockfile here
npm test                   # node --test
npm run coverage           # gate: 80 % lines and functions, 75 % branches
npm pack --ignore-scripts  # signalk-crewradio-<version>.tgz, the same file CI attaches
```

There is deliberately no `package-lock.json` in `sk-plugin/` (it is git-ignored): the Signal K
project's reusable CI workflow switches on setup-node's npm cache as soon as any lockfile exists in
the checkout and then looks for it at the repository root, which fails every job.

Install a packed plugin on a boat's server from the file:

```sh
npm install /path/to/signalk-crewradio-<version>.tgz
```

The plugin's version in `sk-plugin/package.json` is semantic and moved by hand, with a
`CHANGELOG.md` entry — unlike the app's, which comes from the commit count.

## 10. After changing a dependency

Every artifact the build downloads is checked against `gradle/verification-metadata.xml`, and
neither Dependabot nor a hand edit of a version adds the new checksums. On the branch that changed
the version, with the SDK and a JDK 17 on the machine:

```sh
./gradlew --write-verification-metadata sha256 help testDebugUnitTest assembleRelease assembleDebug lintRelease sbom
```

Then read the diff — only the bumped modules should change; Gradle merges into the existing file —
and commit it together with the version change. A new AGP also needs its Windows and macOS `aapt2`
checksums added by hand, since the run above only sees the platform it ran on. The same recipe is
in `.github/dependabot.yml`.

## 11. When the build stops

| What it says | What it means |
| --- | --- |
| *Cannot find a Java installation … {languageVersion=17}* | No JDK 17 on the machine; section 1. The build will not download one. |
| *SDK location not found* | No `ANDROID_HOME` and no `local.properties`; section 1. |
| *Dependency verification failed* | A dependency changed without its checksum; section 10. |
| *CREWRADIO_KEYSTORE names …, which is not a file* | The path is wrong. The build refuses to fall back to the debug key. |
| *CREWRADIO_KEYSTORE is set but CREWRADIO_KEYSTORE_PASSWORD is empty* | The password is not in this shell's environment. |
| *Release-signed builds need the full git history* | Shallow clone: `git fetch --unshallow`. |
| *APK is not signed with the crew's release certificate* (CI) | The secrets hold a different key than `CREWRADIO_CERT_SHA256` names; section 5.4. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (adb) | Debug- and release-signed builds cannot replace each other. Note the channel key down, uninstall, install again. |
