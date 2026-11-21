# Signing and releasing the Android app

## Why signing matters

Every Android app is signed with a private key. Android remembers the signature of the first version you install, and **only installs updates signed with that same key**. That stops someone else from pushing a fake "update" to your phone.

Three things follow from that:

- **One key, forever.** Every Thereabouts APK must be signed with the same key so updates install over each other and nobody loses their settings or membership.
- **Lose the key, and updates break.** Everyone would have to uninstall and reinstall. Back it up.
- **Leak the key, and someone could sign fake updates.** Keep it private: never commit it, never paste it into chats or issues.

The key lives in a small file (`thereabouts-release.p12`) protected by a password. GitHub keeps an encrypted copy as a repository secret, so CI can sign releases without the key ever being in the repo.

## One-time setup

### 1. Create the key

You only need a machine with Docker. These steps are for a **TrueNAS shell** (System → Shell, or SSH). On TrueNAS, Docker needs `sudo`, and your user may have no writable home folder, so the key goes in a folder on one of your **SMB shares**. That way you can reach it from your computer afterwards.

```sh
# A folder on a share you can open from your computer. Change this path.
KEYDIR=/mnt/POOL/SHARE/thereabouts-key

sudo mkdir -p "$KEYDIR"
sudo docker run --rm -it -v "$KEYDIR":/keys -w /keys eclipse-temurin:21-jre \
  keytool -genkeypair -keystore thereabouts-release.p12 -storetype PKCS12 \
  -alias thereabouts -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Thereabouts"
```

It asks for a password twice. Use a long one and save it in your password manager right away.

Then turn the key into text for GitHub, and give the files to your own user so you can open them over SMB:

```sh
sudo sh -c "base64 -w0 '$KEYDIR/thereabouts-release.p12' > '$KEYDIR/thereabouts-release.b64'"
sudo chown -R "$(id -un)" "$KEYDIR"
```

(On a regular Linux or Mac machine with Java installed, you can instead run just the `keytool ...` part in any folder, then `base64 -w0 thereabouts-release.p12 > thereabouts-release.b64`. On a Mac, use `base64 -i thereabouts-release.p12 -o thereabouts-release.b64`.)

### 2. Give it to GitHub

From your computer, open the share and the `thereabouts-key` folder. In the GitHub repo, go to **Settings → Secrets and variables → Actions → New repository secret** and add two secrets:

| Name | Value |
|---|---|
| `THEREABOUTS_KEYSTORE_B64` | the entire contents of `thereabouts-release.b64`: open it in a text editor, select all, copy. It's one long line. |
| `THEREABOUTS_KEYSTORE_PASSWORD` | the password from step 1 |

CI finds the key's alias by itself, so there's nothing else to set. (Secrets made before the rename, named `LIBRE360_KEYSTORE_B64` and `LIBRE360_KEYSTORE_PASSWORD`, still work, so there's no need to redo them.)

### 3. Back it up, then clean up

- Put `thereabouts-release.p12` and its password in your password manager (most accept file attachments). Keep a second copy offline, such as a USB stick in a drawer.
- Then **delete the `thereabouts-key` folder from the share**. GitHub and your backups now hold the only copies, and a key sitting on a network share is easy to leak.

### 4. Check it

Go to **Actions → Android → Run workflow** on `main`. When it finishes, the run summary shows a **signing certificate SHA-256**. That fingerprint is your key's public identity. It's safe to share, and it appears in every release's notes, so anyone can check an APK really came from you.

If the secret is missing or wrong, the build warns about a throwaway key (or fails) and **does not publish a release**. An APK signed with the wrong key can't slip out.

## Making a release

1. Bump `versionCode` (by 1) and `versionName` in `android/app/build.gradle.kts`. Android uses `versionCode` to decide what counts as an update.
2. Merge to `main`.
3. CI builds, signs and publishes a GitHub release `v<versionName>` with `thereabouts.apk` attached.

## Getting updates on your phone automatically

[Obtainium](https://github.com/ImranR98/Obtainium) installs apps straight from GitHub releases and checks for updates. Install it (from F-Droid or its GitHub page) and add `https://github.com/Spasm0dic/Thereabouts`. New releases then arrive like any other app update. This works today, before Thereabouts is in any app store.

## F-Droid and other app stores

| Store | Who signs the APK | Notes |
|---|---|---|
| **GitHub releases** (+ Obtainium) | You | Available now. |
| **[IzzyOnDroid](https://apt.izzysoft.de/fdroid/)** | You (it ships your APK from GitHub releases) | An F-Droid-compatible repo with quick inclusion and the easiest first step. Installs from here and from GitHub update each other. |
| **F-Droid (main repo)** | F-Droid **by default** | F-Droid builds the app from source itself. With its own signature, an F-Droid install and a GitHub install **can't update each other**. |
| **F-Droid with reproducible builds** | You | If F-Droid's build matches your APK byte for byte, it publishes your signed APK instead. All sources then share one signature. |

**Recommended path:** keep one key (above) → list on IzzyOnDroid when the app feels ready → apply to F-Droid with reproducible builds so everyone keeps the same signature.

Already in place for F-Droid:
- 100% free-software dependencies in the `foss` flavour, with no Google Play Services (only the `plus` flavour adds them; F-Droid builds `foss`)
- the Google "dependency info" blob is disabled in the APK
- builds from source with a plain `./gradlew assembleFossRelease`

Still to do when we get there:
- store listing text and screenshots in `fastlane/metadata/android/`
- check that two builds of the same commit are byte-identical (reproducible), fixing anything that isn't
- the F-Droid metadata merge request
