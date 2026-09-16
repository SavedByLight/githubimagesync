# GitHub Image Sync (Android / Kotlin)

A simple Android app that keeps photos in sync between your phone and a GitHub repository.

- **Upload** – takes the photos currently on the device and pushes them into a folder named after the **device codename** (`Build.DEVICE`, e.g. `raven`, `cheetah`, `oriole`).
- **Sync (Download)** – pulls every image that already exists in that same device-codename folder on GitHub and saves them into the phone’s gallery under `Pictures/GitHubSync/<codename>/`.

## Features

- Material 3 UI with owner / repo / token fields
- **Optional AES-256-CTR + HMAC-SHA256 encryption** of media before upload (and automatic decryption on download)
- Device codename shown on the main screen (the exact folder name used on GitHub)
- Progress log while uploading or downloading
- Works with Android 8+ (API 26); uses the modern `READ_MEDIA_IMAGES` permission on Android 13+
- Overwrites existing files on GitHub when the same file name is uploaded again
- Skips empty files; large files use Git LFS

## Setup

### 1. Create a GitHub repository

Create an empty public or private repository that will hold the photos.

### 2. Create a Personal Access Token

1. GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens (or classic).
2. Give the token **Contents: Read and write** permission on the target repository (classic token needs the `repo` scope).
3. Copy the token – you will paste it into the app.

### 3. Open the project in Android Studio

1. File → Open → select the `GitHubImageSync` folder.
2. Let Gradle sync finish.
3. (Optional) Change the applicationId / package name if you want.
4. Run on a real device or emulator (API 26+).

> The project uses View Binding, OkHttp, Coroutines, Gson and Material 3.  
> Minimum SDK 26, target / compile SDK 34.

### 4. Use the app

1. Enter **GitHub username/org**, **repository name** and the **token**.
2. (Optional) Enter an **Encryption password**. When set, every uploaded photo/video is encrypted with AES-256-CTR + HMAC-SHA256 before it leaves the device. The same password is required to decrypt files on download. Leave blank for normal (plaintext) behaviour.
3. Tap **Save Settings**.
4. Grant the storage / media permission when asked.
5. Tap **Upload** to push local photos into `/<device-codename>/` on the repo.
6. Tap **Sync (Download)** to pull the photos that already live in that folder back onto the phone (and decrypt them if a password is set).

## How the folder structure looks on GitHub

```
your-repo/
└── raven/                 ← device codename (example)
    ├── IMG_20240101_120000.jpg
    ├── Screenshot_2024.png
    └── ...
```

Each physical device writes into its own folder, so you can keep photos from several phones in the same repository without collisions.

## Important notes / limitations

- The GitHub **Contents API** is used (simple base64 upload). It is not ideal for very large media libraries; each file is a separate commit. For heavy use consider the Git Data API or a different backend.
- Rate limits apply (especially with a classic token). Fine-grained tokens are recommended.
- Only image extensions (jpg, jpeg, png, webp, gif, heic, heif, bmp) and common video extensions are considered media.
- On Android 10+ the downloaded images appear in the system Gallery under `Pictures/GitHubSync/<codename>`.
- The token (and encryption password) are stored in plain SharedPreferences. For a production app you should encrypt them (EncryptedSharedPreferences) or use the Android Keystore.
- **Encryption details**: AES-256-CTR (streaming) + HMAC-SHA256 (encrypt-then-MAC). Key material is derived with PBKDF2-HMAC-SHA256 (100 000 iterations) from the password + a random 16-byte salt per file → 64 bytes (32 for AES, 32 for HMAC). Each file gets its own random 16-byte IV. The encrypted blob format is `[16 salt][16 IV][ciphertext][32 HMAC]`. The HMAC covers `(IV || ciphertext)`.  
  GCM was deliberately avoided because Android’s Conscrypt implementation buffers the entire plaintext and OOMs on large videos.  
  Files uploaded without a password cannot be decrypted with a password (and vice-versa). Use the same password on every device that should be able to read the encrypted files.

## Project structure

```
app/
├── src/main/
│   ├── java/com/example/githubimagesync/
│   │   ├── MainActivity.kt          # UI + permission + button handlers
│   │   ├── Prefs.kt                 # Simple SharedPreferences helper
│   │   ├── CryptoHelper.kt          # AES-256-CTR + HMAC-SHA256 encrypt / decrypt
│   │   ├── ImageRepository.kt       # MediaStore query, gallery save, high-level sync
│   │   └── github/
│   │       ├── GitHubClient.kt      # OkHttp wrapper for Contents API + LFS
│   │       └── GitHubModels.kt      # Gson data classes
│   ├── res/layout/activity_main.xml
│   └── AndroidManifest.xml
└── build.gradle.kts
```

## License

This sample is provided as-is for educational purposes. Use at your own risk.

## GitHub Actions – automatic release

The repo includes `.github/workflows/build-release.yml`.

- **Triggers:** push to `main` / `master`, or manual “Run workflow”
- **What it does:**
  1. Builds a signed release APK (`assembleRelease`)
  2. Creates a GitHub Release with tag & title **`1.0.<run_number>`**  
     (e.g. first run → `1.0.1`, second → `1.0.2`, …)
  3. Attaches the APK to that release

No extra secrets are required – the workflow uses the built-in `GITHUB_TOKEN` and the debug keystore for signing (fine for personal / testing builds).
