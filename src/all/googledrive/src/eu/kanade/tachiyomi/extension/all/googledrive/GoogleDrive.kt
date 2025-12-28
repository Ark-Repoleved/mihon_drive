package eu.kanade.tachiyomi.extension.all.googledrive

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
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

    private val folderUrl: String
        get() = preferences.getString(FOLDER_URL_PREF, "") ?: ""

    private val folderId: String
        get() = extractFolderId(folderUrl)

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        if (apiKey.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Cloud API Key")
        }
        if (folderUrl.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Drive 資料夾連結")
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$folderId' in parents and mimeType = 'application/vnd.google-apps.folder'")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name")
            .addQueryParameter("pageSize", "1000")
            .build()

        return GET(url.toString(), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val allFiles = fetchAllFiles(response)

        val mangas = allFiles.map { file ->
            SManga.create().apply {
                url = file.id
                title = file.name
                thumbnail_url = null
            }
        }

        return MangasPage(mangas, false)
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
        if (folderUrl.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Drive 資料夾連結")
        }

        // Search by name within the folder
        val queryCondition = if (query.isNotBlank()) {
            "'$folderId' in parents and mimeType = 'application/vnd.google-apps.folder' and name contains '$query'"
        } else {
            "'$folderId' in parents and mimeType = 'application/vnd.google-apps.folder'"
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", queryCondition)
            .addQueryParameter("key", apiKey)
            .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name")
            .addQueryParameter("pageSize", "1000")
            .build()

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // =============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList()

    // =========================== Manga Details ============================

    override fun getMangaUrl(manga: SManga): String {
        return "https://drive.google.com/drive/folders/${manga.url}"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (apiKey.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Cloud API Key")
        }

        // Fetch cover image and ComicInfo.xml from manga folder
        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'${manga.url}' in parents and (name contains 'cover' or name = 'ComicInfo.xml')")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .build()

        return GET(url.toString(), headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<DriveFilesResponse>()

        var comicInfo: ComicInfo? = null

        // Find and fetch ComicInfo.xml
        val comicInfoFile = result.files.firstOrNull { it.name == "ComicInfo.xml" }
        if (comicInfoFile != null) {
            try {
                val xmlUrl = "$baseUrl/files/${comicInfoFile.id}?alt=media&key=$apiKey"
                val xmlResponse = client.newCall(GET(xmlUrl, headers)).execute()
                if (xmlResponse.isSuccessful) {
                    comicInfo = parseComicInfo(xmlResponse.body.string())
                }
            } catch (_: Exception) {
                // Ignore parsing errors
            }
        }

        // Find cover image
        val coverFile = result.files.firstOrNull { file ->
            file.name.lowercase().startsWith("cover") &&
                file.mimeType.startsWith("image/")
        }

        return SManga.create().apply {
            initialized = true

            // Use ComicInfo data if available
            if (comicInfo != null) {
                author = comicInfo.writer.takeIf { it.isNotBlank() }
                artist = comicInfo.penciller.takeIf { it.isNotBlank() }
                description = comicInfo.summary.takeIf { it.isNotBlank() }
                genre = comicInfo.genre.takeIf { it.isNotBlank() }
                status = when (comicInfo.publishingStatus.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "completed", "ended" -> SManga.COMPLETED
                    "hiatus" -> SManga.ON_HIATUS
                    "cancelled", "canceled" -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            } else {
                status = SManga.UNKNOWN
            }

            thumbnail_url = coverFile?.let { buildImageUrl(it.id) }
        }
    }

    private fun parseComicInfo(xml: String): ComicInfo {
        var series = ""
        var summary = ""
        var writer = ""
        var penciller = ""
        var genre = ""
        var publishingStatus = ""

        // Simple XML parsing without external library
        series = extractXmlTag(xml, "Series")
        summary = extractXmlTag(xml, "Summary")
        writer = extractXmlTag(xml, "Writer")
        penciller = extractXmlTag(xml, "Penciller")
        genre = extractXmlTag(xml, "Genre")

        // Try to get Tachiyomi-specific status
        val statusMatch = Regex("""<ty:PublishingStatusTachiyomi[^>]*>([^<]*)</ty:PublishingStatusTachiyomi>""").find(xml)
        publishingStatus = statusMatch?.groupValues?.getOrNull(1) ?: ""

        return ComicInfo(series, summary, writer, penciller, genre, publishingStatus)
    }

    private fun extractXmlTag(xml: String, tagName: String): String {
        val regex = Regex("""<$tagName>([^<]*)</$tagName>""")
        return regex.find(xml)?.groupValues?.getOrNull(1) ?: ""
    }

    private data class ComicInfo(
        val series: String,
        val summary: String,
        val writer: String,
        val penciller: String,
        val genre: String,
        val publishingStatus: String
    )

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        if (apiKey.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Cloud API Key")
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'${manga.url}' in parents and mimeType = 'application/vnd.google-apps.folder'")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name desc")
            .addQueryParameter("pageSize", "1000")
            .build()

        return GET(url.toString(), headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val allFiles = fetchAllFiles(response)
            .filter { it.mimeType == "application/vnd.google-apps.folder" }

        return allFiles.mapIndexed { index, file ->
            SChapter.create().apply {
                url = file.id
                name = file.name
                chapter_number = (allFiles.size - index).toFloat()
                date_upload = 0L
            }
        }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        if (apiKey.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Cloud API Key")
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'${chapter.url}' in parents and (mimeType contains 'image/' or mimeType contains 'video/')")
            .addQueryParameter("key", apiKey)
            .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name")
            .addQueryParameter("pageSize", "1000")
            .build()

        return GET(url.toString(), headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val allFiles = mutableListOf<DriveFile>()
        var result = response.parseAs<DriveFilesResponse>()

        allFiles.addAll(result.files.filter { isMediaFile(it.mimeType) })

        // Fetch additional pages if needed
        while (result.nextPageToken != null) {
            val requestUrl = response.request.url
            val nextUrl = requestUrl.newBuilder()
                .setQueryParameter("pageToken", result.nextPageToken)
                .build()

            val nextResponse = client.newCall(GET(nextUrl.toString(), headers)).execute()
            result = nextResponse.parseAs<DriveFilesResponse>()
            allFiles.addAll(result.files.filter { isMediaFile(it.mimeType) })
        }

        return allFiles
            .sortedBy { it.name }
            .mapIndexed { index, file ->
                Page(index, "", buildImageUrl(file.id, file.mimeType))
            }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    // ============================= Utilities ==============================

    /**
     * Fetch all files with pagination support
     */
    private fun fetchAllFiles(response: Response): List<DriveFile> {
        val allFiles = mutableListOf<DriveFile>()
        var result = response.parseAs<DriveFilesResponse>()

        allFiles.addAll(result.files)

        // Fetch additional pages if needed
        while (result.nextPageToken != null) {
            val requestUrl = response.request.url
            val nextUrl = requestUrl.newBuilder()
                .setQueryParameter("pageToken", result.nextPageToken)
                .build()

            val nextResponse = client.newCall(GET(nextUrl.toString(), headers)).execute()
            result = nextResponse.parseAs<DriveFilesResponse>()
            allFiles.addAll(result.files)
        }

        return allFiles
    }

    private fun extractFolderId(url: String): String {
        if (url.isBlank()) return ""
        val regex = Regex("""folders/([a-zA-Z0-9_-]+)""")
        val match = regex.find(url)
        return match?.groupValues?.getOrNull(1) ?: url
    }

    private fun buildImageUrl(fileId: String, mimeType: String? = null): String {
        return if (mimeType?.startsWith("video/") == true) {
            // Use direct download link for videos + fake extension
            "https://drive.google.com/uc?export=download&id=$fileId&.mp4"
        } else {
            // Use direct download format for images
            "https://drive.usercontent.google.com/download?id=$fileId&export=view"
        }
    }

    private fun isMediaFile(mimeType: String): Boolean {
        return mimeType.startsWith("image/") || mimeType.startsWith("video/")
    }

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString(kotlinx.serialization.serializer(), body.string())
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

        EditTextPreference(screen.context).apply {
            key = FOLDER_URL_PREF
            title = "Google Drive 資料夾連結"
            summary = "輸入公開分享的 Google Drive 資料夾連結"
            setDefaultValue("")

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(FOLDER_URL_PREF, newValue as String).apply()
                true
            }
        }.let { screen.addPreference(it) }
    }

    companion object {
        private const val API_KEY_PREF = "api_key"
        private const val FOLDER_URL_PREF = "folder_url"
    }
}
