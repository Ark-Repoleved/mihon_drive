# Mihon Google Drive Extension

A custom Mihon (Tachiyomi) extension that uses Google Drive as a manga source.

**[繁體中文版 README](README_zh-TW.md)**

## Features

- Read manga from Google Drive by entering a folder link
- Supports folder and ZIP/CBZ chapter formats
- Automatically detects `cover.jpg` as cover image

## Prerequisites

### 1. Get a Google Cloud API Key

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a project
3. Enable **Google Drive API**
4. Create an **API Key** (Credentials → Create credentials → API key)

### 2. Prepare Your Google Drive Folder

```
📁 My Manga (Set to "Anyone with the link can view")
├── 📁 Manga A
│   ├── cover.jpg (optional)
│   ├── 📁 Chapter 1/
│   │   ├── 001.jpg
│   │   └── ...
│   └── 📄 Chapter 2.zip
└── 📁 Manga B
    └── ...
```

## Installation

1. Go to the [Actions](../../actions) page
2. Click on the latest successful build
3. Download the `googledrive-extension` artifact
4. Extract and install the APK

## Configuration

1. Find **Google Drive** extension in Mihon
2. Long press to enter settings
3. Enter your **API Key**
4. Enter your **Google Drive folder link**
5. Go back and browse the extension to see your manga

## Local Build

```bash
# Windows
.\gradlew.bat :src:all:googledrive:assembleDebug

# Linux/Mac
./gradlew :src:all:googledrive:assembleDebug
```

APK will be generated at `src/all/googledrive/build/outputs/apk/debug/`
