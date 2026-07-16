# YTSync 🎵

YTSync is an offline-first Android music companion designed to sync, download, and catalog YouTube playlists locally on your device. Built with modern Android development standards, it leverages a robust background synchronization engine powered by `yt-dlp` and `FFmpeg` to deliver high-quality audio files directly to your storage.

---

## 🚀 Key Features

* **Auto-Sync Engine (WorkManager)**: Robust background scheduler checks and reconciles local catalogs with online YouTube playlists every 24 hours. Includes a manual **Sync Now** trigger that is fully persistent and survives app process termination or force-kills.
* **Persistent Progress Tracking**: Displays ongoing notifications with real-time download and conversion phases (e.g., *Downloading 3/9 — Converting*) using a Foreground Service with the proper Android 13+ `dataSync` service declaration.
* **Granular Deletion & Exclusion**: Provides a 3-way deletion prompt that allows you to:
  * **Delete**: Removes the local audio file but keeps the item eligible for downloading during the next synchronization cycle.
  * **Delete and Exclude**: Removes the local audio file and flags the song metadata database record (`exclude_from_sync = true`), ensuring it is skipped in future sync reconciliation steps.
  * **Cancel**: Aborts deletion safely.
* **Premium UX Gestures**: Replaces standard dismiss-style gestures with a custom, fixed-anchor card reveal using the Compose Foundation `AnchoredDraggableState` API. Open items snap back to a closed state automatically when you swipe another card or tap anywhere outside the list.
* **Adaptive App Icon**: Set up with a modern Android Adaptive Icon (solid white background layer + transparent black chain logo foreground) covering all density buckets (mdpi through xxxhdpi).

---

## 🛠️ Technology Stack

* **UI Layer**: Jetpack Compose (Kotlin) with Material 3, modern typography, and curated dark/vibrant colors.
* **State Management**: Jetpack ViewModel with `StateFlow` and unidirectional data flow.
* **Dependency Injection**: Dagger Hilt.
* **Local Storage**: Room Database (SQLite) with clean, registered migration schemas (e.g., `MIGRATION_4_5`).
* **Background Processing**: Jetpack WorkManager integrated with Hilt-configured workers (`SyncWorker`).
* **Audio Engines**: `youtubedl-android` wrapper around `yt-dlp` for metadata extraction/downloading, and `ffmpeg-android` for audio conversion.

---

## 📁 Project Structure

```
app/src/main/kotlin/com/ytsync/app/
│
├── data/
│   ├── db/          # Room database definition, entities (SongEntity), and DAOs
│   ├── engine/      # Youtube-DL and FFmpeg engine initializations
│   ├── file/        # File system storage managers for local media
│   ├── repository/  # Playlist and Song repositories coordinates
│   ├── settings/    # User settings and datastore preferences
│   └── sync/        # WorkManager SyncWorker scheduler and notification controllers
│
├── di/              # Hilt Modules (DatabaseModule, EngineModule, etc.)
│
├── navigation/      # Jetpack Compose navigation graphs and target screens
│
└── ui/
    ├── screens/     # Screen Composables (Home, Downloads, Settings) & ViewModels
    └── theme/       # Application color systems, typography, and shape definitions
```

---

## ⚙️ Step-by-Step Build & Install Guide

Follow these simple, copy-pasteable terminal commands to clone, build, and run YTSync on your machine:

### 1. Clone the Repository
Clone the codebase from GitHub to your local machine:
```bash
git clone git@github.com:AquaA1/YTSync.git
cd YTSync
```

### 2. Verify Prerequisites
Make sure you have **JDK 17** and **Python** (required for `yt-dlp` runtime execution) installed and configured in your shell:
```bash
# Check Java version (should be 17 or higher)
java -version
```

### 3. Compile and Build the APK
Compile the project and build the debug APK:
```bash
# Clean build and compile the APK
./gradlew assembleDebug
```
*Once finished, the compiled APK will be located at:*  
`app/build/outputs/apk/debug/app-debug.apk`

### 4. Install the APK

#### Option A: Direct Install via USB Debugging (Recommended)
If you have a connected device or emulator with USB debugging enabled:
```bash
# Install the built APK directly onto your device
./gradlew installDebug
```

#### Option B: Locate and Transfer APK File
If you want to manually copy the APK to your phone or share it, copy the file from this path:
```bash
# Print the absolute path to your newly built APK
echo "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
```

---

## 📖 How It Works

1. **Adding Playlists**: Configure your target YouTube playlist URLs via the Settings/Home screen.
2. **Synchronization**:
   - The app reconciles remote playlist contents with local SQLite metadata database records.
   - Any new or modified tracks are enqueued in `SyncWorker` for download.
   - Songs flagged with `excludeFromSync == true` are completely filtered out of the synchronization pipeline.
3. **Download Process**:
   - Downloads raw streams in the background.
   - Uses `FFmpeg` to transcode streams into optimized local audio formats.
   - Saves final media files in internal scoped storage and updates the database row state to `DOWNLOADED`.
