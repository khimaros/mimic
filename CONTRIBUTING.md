# contributing

## layout

```
app/                kotlin android app (the accessibility service + receiver)
  src/main/kotlin/com/khimaros/mimic/
  src/main/res/
  src/main/AndroidManifest.xml
cli/mimic            posix shell cli for termux / adb
tests/              python end-to-end tests (driven over adb)
SKILL.md            intent + cli reference for agents
Makefile            build / install / test entry points
mise.toml           pinned toolchain
```

## toolchain

`mise` pins the jdk and gradle. install them with:

```
mise install
```

the android sdk platform and build-tools are large and license-gated, so they
are not vendored. `mise` defaults `ANDROID_HOME` to `~/android-sdk`; install the
needed packages there (and accept licenses) once:

```
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
sdkmanager --licenses
```

if your sdk lives elsewhere, export a different `ANDROID_HOME`, or set `sdk.dir`
in a `local.properties` (gitignored, takes precedence). no machine-specific paths
are checked into the repo.

## build and test

```
make            # assemble debug apk
make install    # adb install onto a connected device/emulator
make test-e2e   # python e2e over adb (skips when no device attached)
make precommit  # run before committing: lint + build + e2e
make release    # assemble release apk (see signing below)
```

## release

`make release` runs `assembleRelease`; output is under
`app/build/outputs/apk/release/`. signing is env-gated, and the keystore is never
checked into the repo:

- with no env set, you get `app-release-unsigned.apk` (sign it yourself with
  `zipalign` + `apksigner`).
- set the signing env to get a signed `app-release.apk` directly:

  ```
  # one-time: create a keystore kept outside the repo
  keytool -genkeypair -v -keystore ~/.android/mimic-release.jks -alias mimic \
      -keyalg RSA -keysize 2048 -validity 10000

  MIMIC_KEYSTORE=~/.android/mimic-release.jks MIMIC_KEYSTORE_PASS=... \
  MIMIC_KEY_ALIAS=mimic MIMIC_KEY_PASS=... make release
  ```

  updates to an installed copy must use the same key (android rejects a re-sign
  with a different key unless you uninstall first).

### tagged releases

pushing a `vX.Y.Z` tag runs `.github/workflows/release.yml`, which builds the
signed release apk and attaches it to a github release named after the tag. it
refuses to publish rather than ship something unusable:

- the tag must equal the `versionName` the **built apk** carries (read back with
  `aapt2 dump badging`) -- the version comes from the tagged source, never from
  the workflow. checking the artifact rather than grepping the build file means
  the check cannot quietly stop matching when that file is refactored.
- the tag must not already have a release; the workflow will not overwrite one.
- `MIMIC_KEYSTORE_BASE64` must be set, and the built apk must not carry a debug
  key, or every install would be un-upgradable.

repository secrets, already set on `khimaros/mimic`:

| secret | holds |
| --- | --- |
| `MIMIC_KEYSTORE_BASE64` | `base64 -w0 < mimic-release.jks` |
| `MIMIC_KEYSTORE_PASSWORD` | the keystore password |
| `MIMIC_KEY_ALIAS` | `mimic` |
| `MIMIC_KEY_PASSWORD` | the key password (same as the keystore's; pkcs12 requires it) |

secrets are per-repository on a personal account -- there is no org to share them
from, so another repo's keystore secrets are not reachable here.

**the keystore is the release identity and cannot be regenerated.** it lives at
`~/.android/mimic-release.jks` with its password beside it in
`~/.android/mimic-release.pass` (both mode 600, outside the repo). back both up
somewhere durable: if they are lost, no future build can ever upgrade an
installed copy, and every user has to uninstall first. releases up to v0.5.0
shipped `app-release-unsigned.apk` and carry no key at all, so the first signed
release starts that identity rather than continuing one.

to release, edit one line at the top of `app/build.gradle.kts` and tag it:

```kotlin
val mimicVersionName = "0.6.0"    // -> versionCode 600000, tag v0.6.0
```

`versionCode` is parsed from that string as
`MAJOR * 10_000_000 + MINOR * 100_000 + PATCH * 1_000`, so a bump is one edit and
the name and code cannot disagree. no `v` in the string -- it is the user-visible
`versionName` and reaches `BuildConfig.VERSION_NAME` and the `status` payload;
only the git tag carries the prefix. a malformed version fails the build with the
reason (`version part "v0" is not a number, in "v0.6.0"`) rather than quietly
producing a wrong code.

android refuses an update whose `versionCode` did not increase, and fixed field
widths keep that true across every bump -- `0.9.0` (900000) < `0.10.0` (1000000)
< `1.0.0` (10000000). a naive `major.minor.patch` concatenation breaks exactly
there. major can reach 214 before overflowing a signed int.

the low three digits are reserved and always `000` today. mimic is pure kotlin
with no native code, so it builds one universal apk and needs no abi splits; if
that ever changes, the offset goes there (`+1` armeabi-v7a, `+2` arm64-v8a, `+3`
x86, `+4` x86_64) so each architecture gets a distinct, ordered code from the
same release. note kotlin has no octal literals and rejects a leading zero, so
write `600000`, never `0600000`.

## tests

prefer end-to-end integration tests over unit tests. e2e tests live in `tests/`,
are written in python, and are managed with `uv`. they install the apk, enable
the service, pair, and exercise the intent protocol against a real device or
emulator over `adb`. when no device is attached they skip rather than fail.

the suite drives whatever `adb` resolves, and it reaches the http surface through
`adb forward` to device-localhost -- so it needs the app on its default loopback
bind (a reinstall resets it; a full uninstall is the clean slate). on a machine
with more than one device, point it at one rather than letting it pick:

```
ANDROID_ADB_SERVER_PORT=<port> ANDROID_SERIAL=<serial> make test-e2e
```

the suite installs and configures from scratch each run, so it needs no prepared
device state -- but it does revoke existing clients, so do not aim it at a device
another client is paired to.

## workflow

- add a task to [ROADMAP.md](ROADMAP.md) before starting it; mark it done after.
- update [DESIGN.md](DESIGN.md) after architectural changes and
  [README.md](README.md) / [SKILL.md](SKILL.md) after user-visible changes.
- never regress a requirement in [REQUIREMENTS.md](REQUIREMENTS.md).
- magical constants live as named values at the top of the file that uses them,
  or in the shared protocol file (`Protocol.kt`: `Cmd`/`Actions`/`Extras`/`Defaults`).
- keep dependencies minimal. ascii only. lowercase docs and output.
- version control is the maintainer's job; do not commit, tag, or push.
