# Working on this fork

This is `savek-cc/FrankensteinAPS`, a private fork of `nightscout/AndroidAPS`. It is not a GitHub
fork, so it does not show up in the fork list of the upstream repository. Some safety gates are
patched out on purpose (see "What is patched out" below), so builds from here must not be handed to
other people.

Remotes:

| Name | URL | Meaning |
|---|---|---|
| `origin` | `nightscout/AndroidAPS` | upstream, read only |
| `frankenstein` | `savek-cc/FrankensteinAPS` | our own repository |

## Two lines, two phones

The fork exists twice, once per phone. Both are "upstream ref plus our patch stack", they only sit
on a different upstream branch.

| Branch | Sits on | Version | Phone |
|---|---|---|---|
| `dev` | `origin/dev` | `4.0.0-dev-b` | the adult phone, has the new Compose UI |
| `pre-ui-rewrite` | `origin/master` | `3.4.2.6` | the child phone, still the old UI |

`origin/master` is a live maintenance line. It gets bug fixes for the 3.4.x releases and does **not**
get the UI rewrite, so the child phone can follow upstream without changing how the app looks.

Working copies:

```
/storage/projects/AndroidAPS-dev                      -> dev
/storage/projects/AndroidAPS-dev/.worktrees/pre-ui-rewrite -> pre-ui-rewrite
```

## Tags

Both branches are rebased and force pushed, so the branch history is thrown away on every sync. Tags
are what makes an old build reproducible: a tag is a ref of its own, a force push does not touch it,
and everything it points at survives garbage collection.

| Prefix | Meaning |
|---|---|
| `build/` | this exact commit was built and installed on a phone |
| `sync/` | state after an upstream sync, not installed anywhere |
| `archive/` | a line we stopped working on |

Name pattern: `<prefix>/<appVersion>-<short sha>-<yyyy.MM.dd>`, for example
`build/4.0.0-dev-b-e34b95f-2026.07.27`. That is the same shape AAPS itself reports in the app info
and uploads to Nightscout, so a version string seen on the phone maps straight to a tag.

**Use lightweight tags** (`git tag <name> <commit>`, no `-a`). `app/build.gradle.kts` builds the
version string with `git describe --always`, which only looks at annotated tags. Upstream tags are
lightweight too, so today the version string is a plain short sha. An annotated tag would replace
that sha with the tag name and change what the phone reports.

Lightweight tags are not pushed by `--follow-tags`. Push them by name.

**Tag before you force push, not after.** After the push the old head is only in the local reflog,
and that expires.

## How to sync a line with upstream

The same steps for both lines, only the upstream ref differs: `origin/dev` for `dev`,
`origin/master` for `pre-ui-rewrite`.

```bash
git fetch origin --tags

# 1. Write down what is running now, so it stays reproducible.
git tag build/<appVersion>-<sha>-<date> <the commit that is on the phone>

# 2. Replay our patches on the new upstream state.
#    --autosquash folds any fixup! commits into their target.
git rebase --autosquash origin/dev        # or origin/master

# 3. Build and run the tests. Both have to pass before anything goes on a phone.
export JAVA_HOME=/opt/android-studio/jbr
./gradlew :app:assembleFullDebug --console=plain > /tmp/build.log 2>&1; echo "exit=$?"
./gradlew testFullDebugUnitTest  --console=plain > /tmp/test.log  2>&1; echo "exit=$?"

# 4. Tag the new state, then push branch and tags.
git tag sync/<appVersion>-<sha>-<date>
git push --force frankenstein <branch>
git push frankenstein <tag>
```

Conflicts hit one commit at a time, and each commit message says why the change exists. If a patch
target is gone upstream, do not force the old diff in - decide again what the feature should do on
the new base and write that.

### What usually breaks, and why

Upstream test sources are the usual problem, not the app itself. `assembleFullDebug` does not
compile test code, so a broken test only shows up in `testFullDebugUnitTest`.

- **We add a parameter, an upstream test does not pass it.** Always add new parameters **last and
  with a default that keeps upstream behaviour**. Then every existing caller, including upstream
  tests, still compiles. Examples in this tree: `SyncPumpCancelExtendedBolusIfAnyTransaction(...,
  amount: Double? = null)` and `OapsProfile(..., smbPercent: Int = 50, ...)`.
- **We switch a feature off, an upstream test asserts it is on.** Adjust the test to assert what
  this build really does and say why in a comment. Do not delete the test - it is what notices if
  the behaviour comes back after a rebase.
- **Do not comment code out to disable it.** Write an explicit pass-through
  (`override fun isSMBModeEnabled(value: Constraint<Boolean>) = value`). A comment block rots
  silently when upstream changes the interface; an override breaks loudly.

## Build

Needs JDK 21. The only usable one on this machine is the JDK that ships with Android Studio:

```bash
export JAVA_HOME=/opt/android-studio/jbr     # 21.0.8
```

`/usr/lib/jvm/java-21-openjdk` looks right but is an empty directory. The system `javac` is JDK 17
and too old.

Every fresh worktree needs a `local.properties` with `sdk.dir=/home/korte/Android/Sdk`. It is not
checked in and is not copied when a worktree is created.

```bash
./gradlew :app:assembleFullDebug        # main app, flavor "full"
./gradlew :wear:assembleFullDebug       # Wear OS app
./gradlew testFullDebugUnitTest         # all JVM unit tests, what CI runs
./gradlew ktlintCheck                   # ktlintFormat to fix
```

Flavors are `full` (APS), `pumpcontrol`, `aapsclient`, `aapsclient2`. Always name a flavor, there is
no plain `assembleDebug`.

Signed release builds take the keystore from
`/storage/private/Anwendungsdaten/Android/Keystore`; there is no `signingConfig` in the build files,
pass it in the way CI does.

### Two traps

- **`pre-ui-rewrite` refuses to build with a dirty working tree.** `app/build.gradle.kts` treats a
  version without a `-` in it as a release build, and `3.4.2.6` has none. Commit first, or the build
  stops with "There are uncommitted changes". On `dev` the version is `4.0.0-dev-b`, so a dirty tree
  is fine there.
- **KSP sometimes fails with "could not be resolved".** That is a stale incremental state, not a
  real error. Run the same command again. A clean build is not needed.

`ktlint` checks every file, not only the ones you changed, so an upstream file can fail the check
without you having touched anything.

## What is patched out

The loop and pump safety limits are untouched. What is removed is the learning path and the
release-gating around it:

- objectives gate nothing, and all of them are marked as accomplished
- the build expiry date no longer caps max IOB, and there are no update notifications
- engineering mode is always on

What those normally guard is still guarded: the Safety plugin limits, the pump driver constraints
and the loop's own checks all run unchanged.

`docs/fork-patches/FEATURES.md` **on the `dev` branch** describes every deviation and why it exists.
The `*.patch` files next to it are the historical stack against an old base and are kept only as
evidence. The `pre-ui-rewrite` branch does not carry that documentation; its patches are the same
features on an older base, so read them there.
