# Setup: signed releases and installing with Obtainium

Releases are built by GitHub Actions when you push a tag like `v0.1.0`. The release has exactly
one file, `loopkeep.apk`, signed with **your** key, so Obtainium can install and update it.

## One-time setup (about 5 minutes)

Prerequisites on your computer: `git`, a JDK 17+ (for `keytool`), and ideally the
[GitHub CLI](https://cli.github.com) logged in (`gh auth login`).

```
scripts/setup.sh
```

The script:
1. checks the prerequisites;
2. creates a release keystore at `~/.loopkeep-signing/loopkeep-release.jks` (outside the repo; `*.jks` is also gitignored), asking you for a password;
3. if `gh` is logged in, stores the four secrets in the GitHub repo for you; otherwise it prints exactly what to paste where;
4. offers to push `main` and create the first tag `v0.1.0`, which starts the first release build;
5. explains how to add the app to Obtainium.

> **Back up the keystore file and its password** (password manager, USB stick, …).
> Android only installs updates signed with the same key. If you lose it, you'll have to uninstall
> Loopkeep and install the new build fresh. Your folder of notes and videos is not affected; just pick it again.

### Doing it by hand instead

```
keytool -genkeypair -v -storetype PKCS12 -keystore ~/.loopkeep-signing/loopkeep-release.jks \
  -alias loopkeep -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Loopkeep"
base64 < ~/.loopkeep-signing/loopkeep-release.jks | tr -d '\n' > /tmp/ks.txt
```

GitHub → your repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | contents of `/tmp/ks.txt` (delete the file afterwards) |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
| `RELEASE_KEY_ALIAS` | `loopkeep` |
| `RELEASE_KEY_PASSWORD` | the same password |

If any secret is missing, the release workflow stops with an error pointing here. It never
publishes an APK signed with some other key.

## Making a release

```
scripts/release.sh            # v0.1.0 → v0.1.1
scripts/release.sh minor      # → v0.2.0
scripts/release.sh 1.0.0      # exact
```

It checks the tree is clean and up to date, shows the commits since the last tag, then tags and
pushes. The version comes only from the tag (`versionName` 1.2.3, `versionCode` 1002003), so there's
nothing to edit. The release notes are the commit subjects since the previous tag.

You can also rebuild an existing tag from GitHub → Actions → Release → *Run workflow*.

## Installing with Obtainium

1. Install [Obtainium](https://obtainium.imranr.dev) on the phone.
2. **Add App** → paste `https://github.com/<you>/<repo>` → Add. The defaults work: one APK per release.
3. Install. Obtainium checks for new releases and offers updates.

- **Public repo:** nothing else needed (simplest).
- **Private repo:** Obtainium needs a token. Create a fine-grained token at
  https://github.com/settings/personal-access-tokens with **read-only Contents** access to this
  repository, and paste it into Obtainium → Settings → GitHub personal access token.

## CI

Every push and pull request runs unit tests, lint, and a debug build. The debug APK is attached to the
workflow run as an artifact (`loopkeep-debug-apk`). It installs side by side with the release app
(package `…loopkeep.debug`), but it's signed with a throwaway debug key, so use releases for everyday use.
