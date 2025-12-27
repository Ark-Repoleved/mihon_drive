package eu.kanade.tachiyomi.extension.all.googledrive

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GoogleDrive : HttpSource(), ConfigurableSource {

    override val name = "Google Drive"
    override val baseUrl = "https://www.googleapis.com/drive/v3"
    override val lang = "all"
    override val supportsLatest = false

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiKey: String
        get() = preferences.getString(API_KEY_PREF, "") ?: ""

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        throw UnsupportedOperationException("Use search with folder URL")
    }

    override fun popularMangaParse(response: Response): MangasPage {
        throw UnsupportedOperationException("Use search with folder URL")
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException("Not supported")
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        throw UnsupportedOperationException("Not supported")
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (apiKey.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Cloud API Key")
        }

        var folderId = ""
        filters.forEach { filter ->
            if (filter is FolderUrlFilter && filter.state.isNotBlank()) {
                folderId = extractFolderId(filter.state)
            }
        }

        if (folderId.isBlank()) {
            throw Exception("請輸入 Google Drive 資料夾連結")
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$folderId' in parents and mimeType = 'application/vnd.google-apps.folder'")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name")
            .build()

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<DriveFilesResponse>(response.body.string())

        val mangas = result.files.map { file ->
            SManga.create().apply {
                url = file.id
                title = file.name
                thumbnail_url = null // Will be fetched in getMangaDetails
            }
        }

        return MangasPage(mangas, false)
    }

    // =============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("輸入 Google Drive 資料夾連結"),
        FolderUrlFilter()
    )

    class FolderUrlFilter : Filter.Text("資料夾連結")

    // =========================== Manga Details ============================

    override fun getMangaUrl(manga: SManga): String {
        return "https://drive.google.com/drive/folders/${manga.url}"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (apiKey.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Cloud API Key")
        }

        // Fetch cover image from manga folder
        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'${manga.url}' in parents and name contains 'cover'")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("fields", "files(id,name,mimeType,webContentLink)")
            .build()

        return GET(url.toString(), headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = json.decodeFromString<DriveFilesResponse>(response.body.string())

        return SManga.create().apply {
            initialized = true
            status = SManga.UNKNOWN

            // Find cover image
            val coverFile = result.files.firstOrNull { file ->
                file.name.lowercase().startsWith("cover") &&
                    file.mimeType.startsWith("image/")
            }

            thumbnail_url = coverFile?.let { buildImageUrl(it.id) }
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        if (apiKey.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Cloud API Key")
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'${manga.url}' in parents and (mimeType = 'application/vnd.google-apps.folder' or mimeType = 'application/zip' or mimeType = 'application/x-cbz' or mimeType = 'application/octet-stream')")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name desc")
            .build()

        return GET(url.toString(), headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = json.decodeFromString<DriveFilesResponse>(response.body.string())

        return result.files
            .filter { file ->
                file.mimeType == "application/vnd.google-apps.folder" ||
                    file.name.lowercase().endsWith(".zip") ||
                    file.name.lowercase().endsWith(".cbz")
            }
            .mapIndexed { index, file ->
                SChapter.create().apply {
                    url = "${file.id}|${file.mimeType}|${file.name}"
                    name = file.name.removeSuffix(".zip").removeSuffix(".cbz").removeSuffix(".ZIP").removeSuffix(".CBZ")
                    chapter_number = (result.files.size - index).toFloat()
                    date_upload = 0L
                }
            }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        if (apiKey.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Cloud API Key")
        }

        val parts = chapter.url.split("|")
        val fileId = parts[0]
        val fileName = parts.getOrNull(2) ?: ""

        // Check if it's a ZIP/CBZ file - return file info request
        if (fileName.lowercase().endsWith(".zip") || fileName.lowercase().endsWith(".cbz")) {
            // For ZIP/CBZ, we return the download URL as a single page
            // Mihon's reader should handle the archive
            val url = "$baseUrl/files/$fileId".toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("fields", "id,name,webContentLink")
                .build()
            return GET(url.toString(), headers)
        }

        // For folder, list image files
        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$fileId' in parents and mimeType contains 'image/'")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name")
            .build()

        return GET(url.toString(), headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val responseBody = response.body.string()

        // Check if this is a single file response (ZIP/CBZ)
        if (responseBody.contains("\"webContentLink\"") && !responseBody.contains("\"files\"")) {
            val file = json.decodeFromString<DriveFile>(responseBody)
            // Return the direct download URL for the archive
            // Note: Mihon may not support reading archives directly via URL
            // In that case, users should use folder structure instead
            val downloadUrl = "$baseUrl/files/${file.id}?alt=media&key=$apiKey"
            return listOf(Page(0, "", downloadUrl))
        }

        val result = json.decodeFromString<DriveFilesResponse>(responseBody)

        return result.files
            .filter { it.mimeType.startsWith("image/") }
            .sortedBy { it.name }
            .mapIndexed { index, file ->
                Page(index, "", buildImageUrl(file.id))
            }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    // ============================= Utilities ==============================

    private fun extractFolderId(url: String): String {
        // Handle various Google Drive URL formats
        // https://drive.google.com/drive/folders/FOLDER_ID
        // https://drive.google.com/drive/folders/FOLDER_ID?usp=sharing
        // https://drive.google.com/drive/u/0/folders/FOLDER_ID

        val regex = Regex("""folders/([a-zA-Z0-9_-]+)""")
        val match = regex.find(url)
        return match?.groupValues?.getOrNull(1) ?: url
    }

    private fun buildImageUrl(fileId: String): String {
        return "$baseUrl/files/$fileId?alt=media&key=$apiKey"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = API_KEY_PREF
            title = "Google Cloud API Key"
            summary = "輸入您的 Google Cloud API Key"
            setDefaultValue("")

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(API_KEY_PREF, newValue as String).apply()
                true
            }
        }.let { screen.addPreference(it) }
    }

    companion object {
        private const val API_KEY_PREF = "api_key"
    }
}
