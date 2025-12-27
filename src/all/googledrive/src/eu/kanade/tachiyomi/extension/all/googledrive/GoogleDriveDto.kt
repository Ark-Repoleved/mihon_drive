package eu.kanade.tachiyomi.extension.all.googledrive

import kotlinx.serialization.Serializable

@Serializable
data class DriveFilesResponse(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val webContentLink: String? = null,
)
