# Mihon Google Drive 擴充功能

以 Google Drive 為漫畫來源的 Mihon (Tachiyomi) 擴充功能。

**[English README](README.md)**

## 功能

- 輸入 Google Drive 資料夾連結即可讀取漫畫
- 支援資料夾和 ZIP/CBZ 格式的章節
- 自動偵測 `cover.jpg` 作為封面

## 使用前準備

### 1. 申請 Google Cloud API Key

1. 前往 [Google Cloud Console](https://console.cloud.google.com/)
2. 建立專案
3. 啟用 **Google Drive API**
4. 建立 **API 金鑰** (Credentials → Create credentials → API key)

### 2. 準備 Google Drive 資料夾

```
📁 我的漫畫 (設定為「任何知道連結的人都可以檢視」)
├── 📁 漫畫A
│   ├── cover.jpg (選用)
│   ├── 📁 第1話/
│   │   ├── 001.jpg
│   │   └── ...
│   └── 📄 第2話.zip
└── 📁 漫畫B
    └── ...
```

## 安裝

1. 前往 [Actions](../../actions) 頁面
2. 點擊最新的成功編譯
3. 下載 `googledrive-extension` artifact
4. 解壓縮並安裝 APK

## 設定

1. 在 Mihon 中找到 **Google Drive** 擴充功能
2. 長按進入設定
3. 輸入你的 **API Key**
4. 輸入你的 **Google Drive 資料夾連結**
5. 返回後瀏覽擴充功能即可看到漫畫

## 本地編譯

```bash
# Windows
.\gradlew.bat :src:all:googledrive:assembleDebug

# Linux/Mac
./gradlew :src:all:googledrive:assembleDebug
```

APK 會產生在 `src/all/googledrive/build/outputs/apk/debug/`
