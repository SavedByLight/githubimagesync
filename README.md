# GitHub Image Sync (Android / Kotlin)

A simple Android app that keeps photos in sync between your phone and a GitHub repository.

- **Upload** – takes the photos currently on the device and pushes them into a folder named after the **device codename** (`Build.DEVICE`, e.g. `raven`, `cheetah`, `oriole`).
- **Sync (Download)** – pulls every image that already exists in that same device-codename folder on GitHub and saves them into the phone’s gallery under `Pictures/GitHubSync/<codename>/`.

## Features

- Material 3 UI with owner / repo / token fields
- Device codename shown on the main screen (the exact folder name used on GitHub)
- Progress log while uploading or downloading
- Works with Android 8+ (API 26); uses the modern `READ_MEDIA_IMAGES` permission on Android 13+
- Overwrites existing files on GitHub when the same file name is uploaded again
- Skips empty files and files larger than 50 MB (GitHub Contents API practical limit)

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
2. Tap **Save Settings**.
3. Grant the storage / media permission when asked.
4. Tap **Upload** to push local photos into `/<device-codename>/` on the repo.
5. Tap **Sync (Download)** to pull the photos that already live in that folder back onto the phone.

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
- Only image extensions (jpg, jpeg, png, webp, gif, heic, heif, bmp) are downloaded.
- On Android 10+ the downloaded images appear in the system Gallery under `Pictures/GitHubSync/<codename>`.
- The token is stored in plain SharedPreferences. For a production app you should encrypt it (EncryptedSharedPreferences) or use the Android Keystore.

## Project structure

```
app/
├── src/main/
│   ├── java/com/example/githubimagesync/
│   │   ├── MainActivity.kt          # UI + permission + button handlers
│   │   ├── Prefs.kt                 # Simple SharedPreferences helper
│   │   ├── ImageRepository.kt       # MediaStore query, gallery save, high-level sync
│   │   └── github/
│   │       ├── GitHubClient.kt      # OkHttp wrapper for Contents API
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
