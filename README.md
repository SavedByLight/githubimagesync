# GitHub Image Sync (Android / Kotlin)

A simple Android app that keeps photos in sync between your phone and a GitHub repository.

- **Upload** – pushes device photos into `/<device-codename>/p1`, `p2`, … (1000 files per part folder). Names that already exist under **any** device folder in the repo are skipped (no re-upload of the same picture/video from another phone).
- **Sync (Download)** – pulls every image from all part folders under the **current** device codename and saves them into the gallery under `Pictures/GitHubSync/<codename>/`.
- **Download from other device** – manually choose any other device folder in the repo and download its photos/videos (local duplicates by filename are still skipped).

## Features

- Material 3 UI with owner / repo / token fields
- **Optional AES-256-CTR + HMAC-SHA256 encryption** of media before upload (and automatic decryption on download)
- **Background upload & download** – foreground services keep transferring after you close the app (progress notification + Stop action)
- Uploads **skip filenames that already exist under any device** (cross-device deduplication)
- Downloads **skip filenames already on the device** (no local duplicates)
- **Manual download from other devices** via a device-folder picker
- Device codename shown on the main screen (the exact folder name used on GitHub)
- Progress log while uploading or downloading
- Works with Android 8+ (API 26); uses the modern `READ_MEDIA_IMAGES` permission on Android 13+
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
5. Tap **Upload** to push local photos into `/<device-codename>/p1`, `p2`, ….  
   Runs in a **foreground service** – you can leave the app; a notification shows progress and a **Stop** action.  
   Filenames that already exist under **any** device folder are skipped.
6. Tap **Sync (Download)** to pull photos from **this device’s** part folders (decrypts if a password is set).  
   Also runs in the background. Filenames already present on the device are **skipped**.
7. Tap **Download from other device…** to list every device folder in the repo, pick one, and download its media into the gallery under `Pictures/GitHubSync/<that-codename>/`.

## How the folder structure looks on GitHub

Files are split into **part folders** of at most **1000 files** each (GitHub’s Contents API listing limit):

```
your-repo/
└── frankel/                 ← device codename (example)
    ├── p1/                  ← first 1000 files
    │   ├── IMG_001.jpg
    │   └── …
    ├── p2/                  ← next 1000 files
    │   └── …
    └── p3/
        └── …
```

- Before every upload the app **scans every device folder** in the repo (all part folders and legacy flat files) and **skips any name that already exists anywhere** — so the same photo/video is not uploaded again from a second device.
- New files go into the lowest-numbered part that still has room under **this** device’s codename (`p1`, then `p2`, …).
- Each physical device uses its own codename root, so several phones can share one repo without path collisions, while filename-based deduplication prevents duplicate content.
- Use **Download from other device…** to pull media from another phone’s folder into this device’s gallery.

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
│   │   ├── MainActivity.kt            # UI + permission + button handlers
│   │   ├── UploadForegroundService.kt # Background upload
│   │   ├── DownloadForegroundService.kt # Background download
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
