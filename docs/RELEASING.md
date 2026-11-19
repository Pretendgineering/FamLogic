# Signing and releasing the Android app

## Why signing matters

Every Android app is signed with a private key. Android remembers the signature of the first version you install, and **only installs updates signed with that same key**. That stops someone else from pushing a fake "update" to your phone.

Three things follow from that:

- **One key, forever.** Every Libre360 APK must be signed with the same key so updates install over each other and nobody loses their settings or membership.
- **Lose the key, and updates break.** Everyone would have to uninstall and reinstall. Back it up.
- **Leak the key, and someone could sign fake updates.** Keep it private: never commit it, never paste it into chats or issues.

The key lives in a small file (`libre360-release.p12`) protected by a password. GitHub keeps an encrypted copy as a repository secret, so CI can sign releases without the key ever being in the repo.

## One-time setup

### 1. Create the key

On any machine with Docker, such as your TrueNAS shell:

```sh
mkdir -p ~/libre360-key && cd ~/libre360-key
docker run --rm -it -v "$PWD":/keys -w /keys eclipse-temurin:21-jre \
  keytool -genkeypair -keystore libre360-release.p12 -storetype PKCS12 \
  -alias libre360 -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Libre360"
```

It asks for a password twice. Use a long one and save it in your password manager.

(With Java installed you can run the `keytool ...` part directly instead.)

### 2. Back it up

Put **both** of these somewhere safe, ideally your password manager (most can hold file attachments) plus one offline copy:

- the file `libre360-release.p12`
- its password

### 3. Give it to GitHub

Turn the file into text:

```sh
base64 -w0 libre360-release.p12 > libre360-release.b64
```

Then in the GitHub repo, go to **Settings → Secrets and variables → Actions → New repository secret** and add two secrets:

| Name | Value |
|---|---|
| `LIBRE360_KEYSTORE_B64` | the entire contents of `libre360-release.b64` (one long line) |
| `LIBRE360_KEYSTORE_PASSWORD` | the password from step 1 |

Then delete the `.b64` file: `rm libre360-release.b64`.

### 4. Check it

Go to **Actions → Android → Run workflow** on `main`. When it finishes, the run summary shows a **signing certificate SHA-256**. That fingerprint is your key's public identity. It's safe to share, and it appears in every release's notes, so anyone can check an APK really came from you.

If the secret is missing or wrong, the build warns about a throwaway key (or fails) and **does not publish a release**. An APK signed with the wrong key can't slip out.

## Making a release

1. Bump `versionCode` (by 1) and `versionName` in `android/app/build.gradle.kts`. Android uses `versionCode` to decide what counts as an update.
2. Merge to `main`.
3. CI builds, signs and publishes a GitHub release `v<versionName>` with `libre360.apk` attached.

## Getting updates on your phone automatically

[Obtainium](https://github.com/ImranR98/Obtainium) installs apps straight from GitHub releases and checks for updates. Install it (from F-Droid or its GitHub page) and add `https://github.com/Spasm0dic/Libre360`. New releases then arrive like any other app update. This works today, before Libre360 is in any app store.

## F-Droid and other app stores

| Store | Who signs the APK | Notes |
|---|---|---|
| **GitHub releases** (+ Obtainium) | You | Available now. |
| **[IzzyOnDroid](https://apt.izzysoft.de/fdroid/)** | You (it ships your APK from GitHub releases) | An F-Droid-compatible repo with quick inclusion and the easiest first step. Installs from here and from GitHub update each other. |
| **F-Droid (main repo)** | F-Droid **by default** | F-Droid builds the app from source itself. With its own signature, an F-Droid install and a GitHub install **can't update each other**. |
| **F-Droid with reproducible builds** | You | If F-Droid's build matches your APK byte for byte, it publishes your signed APK instead. All sources then share one signature. |

**Recommended path:** keep one key (above) → list on IzzyOnDroid when the app feels ready → apply to F-Droid with reproducible builds so everyone keeps the same signature.

Already in place for F-Droid:
- 100% free-software dependencies, with no Google Play Services
- the Google "dependency info" blob is disabled in the APK
- builds from source with a plain `./gradlew assembleRelease`

Still to do when we get there:
- store listing text and screenshots in `fastlane/metadata/android/`
- check that two builds of the same commit are byte-identical (reproducible), fixing anything that isn't
- the F-Droid metadata merge request
