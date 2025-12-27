package eu.kanade.tachiyomi.extension.all.googledrive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
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

    private val context: Context by lazy {
        Injekt.get<android.app.Application>()
    }

    private val preferences: android.content.SharedPreferences by lazy {
        context.getSharedPreferences("source_$id", 0x0000)
    }

    // OAuth 設定
    private val clientId: String
        get() = preferences.getString(CLIENT_ID_PREF, "") ?: ""

    private var accessToken: String
        get() = preferences.getString(ACCESS_TOKEN_PREF, "") ?: ""
        set(value) = preferences.edit().putString(ACCESS_TOKEN_PREF, value).apply()

    private var refreshToken: String
        get() = preferences.getString(REFRESH_TOKEN_PREF, "") ?: ""
        set(value) = preferences.edit().putString(REFRESH_TOKEN_PREF, value).apply()

    private var tokenExpiry: Long
        get() = preferences.getLong(TOKEN_EXPIRY_PREF, 0L)
        set(value) = preferences.edit().putLong(TOKEN_EXPIRY_PREF, value).apply()

    private val folderUrl: String
        get() = preferences.getString(FOLDER_URL_PREF, "") ?: ""

    private val folderId: String
        get() = extractFolderId(folderUrl)

    private val isLoggedIn: Boolean
        get() = accessToken.isNotBlank() && refreshToken.isNotBlank()

    // ============================== Auth ================================

    private fun getValidAccessToken(): String {
        if (!isLoggedIn) {
            throw Exception("請先登入 Google 帳號")
        }

        // Check if token is expired (with 5 min buffer)
        if (System.currentTimeMillis() > tokenExpiry - 300000) {
            refreshAccessToken()
        }

        return accessToken
    }

    private fun refreshAccessToken() {
        if (refreshToken.isBlank()) {
            throw Exception("請重新登入 Google 帳號")
        }

        val requestBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val request = POST("https://oauth2.googleapis.com/token", body = requestBody)
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            // Token refresh failed, clear tokens
            accessToken = ""
            refreshToken = ""
            tokenExpiry = 0L
            throw Exception("Token 已過期，請重新登入")
        }

        val tokenResponse = json.decodeFromString(TokenResponse.serializer(), response.body.string())
        accessToken = tokenResponse.accessToken
        tokenExpiry = System.currentTimeMillis() + (tokenResponse.expiresIn * 1000L)

        // Refresh token is only returned on initial auth, not on refresh
        if (tokenResponse.refreshToken != null) {
            refreshToken = tokenResponse.refreshToken
        }
    }

    private fun buildAuthenticatedRequest(url: String): Request {
        val token = getValidAccessToken()
        val authHeaders = Headers.Builder()
            .addAll(headers)
            .add("Authorization", "Bearer $token")
            .build()
        return GET(url, authHeaders)
    }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        if (!isLoggedIn) {
            throw Exception("請先登入 Google 帳號（在擴充功能設定中）")
        }
        if (folderUrl.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Drive 資料夾連結")
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$folderId' in parents and mimeType = 'application/vnd.google-apps.folder'")
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name")
            .build()

        return buildAuthenticatedRequest(url.toString())
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<DriveFilesResponse>()

        val mangas = result.files.map { file ->
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
        if (!isLoggedIn) {
            throw Exception("請先登入 Google 帳號（在擴充功能設定中）")
        }
        if (folderUrl.isBlank()) {
            throw Exception("請在擴充功能設定中輸入 Google Drive 資料夾連結")
        }

        val queryCondition = if (query.isNotBlank()) {
            "'$folderId' in parents and mimeType = 'application/vnd.google-apps.folder' and name contains '$query'"
        } else {
            "'$folderId' in parents and mimeType = 'application/vnd.google-apps.folder'"
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", queryCondition)
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name")
            .build()

        return buildAuthenticatedRequest(url.toString())
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
        if (!isLoggedIn) {
            throw Exception("請先登入 Google 帳號")
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'${manga.url}' in parents and (name contains 'cover' or name = 'ComicInfo.xml')")
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .build()

        return buildAuthenticatedRequest(url.toString())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<DriveFilesResponse>()

        var comicInfo: ComicInfo? = null

        // Find and fetch ComicInfo.xml
        val comicInfoFile = result.files.firstOrNull { it.name == "ComicInfo.xml" }
        if (comicInfoFile != null) {
            try {
                val xmlUrl = "$baseUrl/files/${comicInfoFile.id}?alt=media"
                val xmlResponse = client.newCall(buildAuthenticatedRequest(xmlUrl)).execute()
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
        val series = extractXmlTag(xml, "Series")
        val summary = extractXmlTag(xml, "Summary")
        val writer = extractXmlTag(xml, "Writer")
        val penciller = extractXmlTag(xml, "Penciller")
        val genre = extractXmlTag(xml, "Genre")

        val statusMatch = Regex("""<ty:PublishingStatusTachiyomi[^>]*>([^<]*)</ty:PublishingStatusTachiyomi>""").find(xml)
        val publishingStatus = statusMatch?.groupValues?.getOrNull(1) ?: ""

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
        if (!isLoggedIn) {
            throw Exception("請先登入 Google 帳號")
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'${manga.url}' in parents and mimeType = 'application/vnd.google-apps.folder'")
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name desc")
            .build()

        return buildAuthenticatedRequest(url.toString())
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<DriveFilesResponse>()

        return result.files
            .filter { it.mimeType == "application/vnd.google-apps.folder" }
            .mapIndexed { index, file ->
                SChapter.create().apply {
                    url = file.id
                    name = file.name
                    chapter_number = (result.files.size - index).toFloat()
                    date_upload = 0L
                }
            }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        if (!isLoggedIn) {
            throw Exception("請先登入 Google 帳號")
        }

        val url = "$baseUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'${chapter.url}' in parents and mimeType contains 'image/'")
            .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType)")
            .addQueryParameter("orderBy", "name")
            .addQueryParameter("pageSize", "1000")
            .build()

        return buildAuthenticatedRequest(url.toString())
    }

    override fun pageListParse(response: Response): List<Page> {
        val allFiles = mutableListOf<DriveFile>()
        var result = response.parseAs<DriveFilesResponse>()

        allFiles.addAll(result.files.filter { it.mimeType.startsWith("image/") })

        while (result.nextPageToken != null) {
            val requestUrl = response.request.url
            val nextUrl = requestUrl.newBuilder()
                .setQueryParameter("pageToken", result.nextPageToken)
                .build()

            val nextResponse = client.newCall(buildAuthenticatedRequest(nextUrl.toString())).execute()
            result = nextResponse.parseAs<DriveFilesResponse>()
            allFiles.addAll(result.files.filter { it.mimeType.startsWith("image/") })
        }

        return allFiles
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
        if (url.isBlank()) return ""
        val regex = Regex("""folders/([a-zA-Z0-9_-]+)""")
        val match = regex.find(url)
        return match?.groupValues?.getOrNull(1) ?: url
    }

    private fun buildImageUrl(fileId: String): String {
        return "https://lh3.googleusercontent.com/d/$fileId=s0"
    }

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString(kotlinx.serialization.serializer(), body.string())
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // OAuth Client ID
        EditTextPreference(screen.context).apply {
            key = CLIENT_ID_PREF
            title = "OAuth Client ID"
            summary = "輸入 Google Cloud OAuth 2.0 Client ID"
            setDefaultValue("")

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(CLIENT_ID_PREF, newValue as String).apply()
                true
            }
        }.let { screen.addPreference(it) }

        // Login button
        Preference(screen.context).apply {
            key = "oauth_login"
            title = if (isLoggedIn) "✓ 已登入 Google 帳號" else "登入 Google 帳號"
            summary = if (isLoggedIn) {
                "點擊以重新登入或登出"
            } else {
                "點擊以開始 OAuth 登入流程"
            }

            setOnPreferenceClickListener {
                startOAuthLogin(screen)
                true
            }
        }.let { screen.addPreference(it) }

        // Folder URL
        EditTextPreference(screen.context).apply {
            key = FOLDER_URL_PREF
            title = "Google Drive 資料夾連結"
            summary = "輸入 Google Drive 資料夾連結"
            setDefaultValue("")

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(FOLDER_URL_PREF, newValue as String).apply()
                true
            }
        }.let { screen.addPreference(it) }
    }

    private fun startOAuthLogin(screen: PreferenceScreen) {
        val context = screen.context

        if (clientId.isBlank()) {
            Toast.makeText(context, "請先輸入 OAuth Client ID", Toast.LENGTH_LONG).show()
            return
        }

        // Request device code
        val requestBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", "https://www.googleapis.com/auth/drive.readonly")
            .build()

        Thread {
            try {
                val deviceCodeRequest = POST(
                    "https://oauth2.googleapis.com/device/code",
                    body = requestBody
                )
                val deviceCodeResponse = client.newCall(deviceCodeRequest).execute()

                if (!deviceCodeResponse.isSuccessful) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "無法取得驗證碼", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val deviceCode = json.decodeFromString(
                    DeviceCodeResponse.serializer(),
                    deviceCodeResponse.body.string()
                )

                // Show user code and URL
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "請在瀏覽器開啟 ${deviceCode.verificationUrl}\n並輸入驗證碼: ${deviceCode.userCode}",
                        Toast.LENGTH_LONG
                    ).show()

                    // Copy to clipboard
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    val clip = ClipData.newPlainText("驗證碼", deviceCode.userCode)
                    clipboard.setPrimaryClip(clip)

                    Toast.makeText(context, "驗證碼已複製到剪貼簿", Toast.LENGTH_SHORT).show()
                }

                // Poll for token
                pollForToken(deviceCode, context)
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "登入失敗: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun pollForToken(deviceCode: DeviceCodeResponse, context: android.content.Context) {
        val pollInterval = (deviceCode.interval * 1000L).coerceAtLeast(5000L)
        val expiryTime = System.currentTimeMillis() + (deviceCode.expiresIn * 1000L)

        while (System.currentTimeMillis() < expiryTime) {
            Thread.sleep(pollInterval)

            val tokenRequestBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()

            val tokenRequest = POST("https://oauth2.googleapis.com/token", body = tokenRequestBody)
            val tokenResponse = client.newCall(tokenRequest).execute()
            val responseBody = tokenResponse.body.string()

            if (tokenResponse.isSuccessful) {
                val token = json.decodeFromString(TokenResponse.serializer(), responseBody)
                accessToken = token.accessToken
                refreshToken = token.refreshToken ?: ""
                tokenExpiry = System.currentTimeMillis() + (token.expiresIn * 1000L)

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "登入成功！", Toast.LENGTH_LONG).show()
                }
                return
            } else {
                // Check if still pending
                try {
                    val error = json.decodeFromString(OAuthErrorResponse.serializer(), responseBody)
                    if (error.error != "authorization_pending" && error.error != "slow_down") {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "登入失敗: ${error.errorDescription}", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                } catch (_: Exception) {
                    // Continue polling
                }
            }
        }

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "驗證碼已過期，請重試", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val CLIENT_ID_PREF = "oauth_client_id"
        private const val ACCESS_TOKEN_PREF = "oauth_access_token"
        private const val REFRESH_TOKEN_PREF = "oauth_refresh_token"
        private const val TOKEN_EXPIRY_PREF = "oauth_token_expiry"
        private const val FOLDER_URL_PREF = "folder_url"
    }
}
