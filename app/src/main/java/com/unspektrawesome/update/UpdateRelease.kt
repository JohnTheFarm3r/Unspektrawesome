package com.unspektrawesome.update

import org.json.JSONObject

internal data class UpdateRelease(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
) {
    companion object {
        const val REPOSITORY = "JohnTheFarm3r/Unspektrawesome"
        // Earlier releases were published here. Checked as well until it is retired.
        const val LEGACY_REPOSITORY = "JohnTheFarm3r/Unspektrawesome-Releases"
        val REPOSITORIES = listOf(REPOSITORY, LEGACY_REPOSITORY)
        fun latestUrl(repository: String) = "https://api.github.com/repos/$repository/releases/latest"
        private val versionPattern = Regex("[0-9]+(?:\\.[0-9]+){1,3}")

        fun isNewer(current: String, remote: String): Boolean {
            require(versionPattern.matches(current) && versionPattern.matches(remote)) {
                "The release has an unsupported version."
            }
            val installed = current.split('.').map(String::toLong)
            val available = remote.split('.').map(String::toLong)
            for (index in 0 until maxOf(installed.size, available.size)) {
                val comparison = available.getOrElse(index) { 0 }.compareTo(installed.getOrElse(index) { 0 })
                if (comparison != 0) return comparison > 0
            }
            return false
        }

        /** Newest release among the repositories; a failure counts only when no repository offers an update. */
        fun newestOf(results: List<Result<UpdateRelease?>>): UpdateRelease? {
            val releases = results.mapNotNull { it.getOrNull() }
            if (releases.isNotEmpty()) return releases.reduce { best, next -> if (isNewer(best.version, next.version)) next else best }
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
            return null
        }

        fun parse(json: String, current: String, repository: String): UpdateRelease? {
            val release = JSONObject(json)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
            val tag = release.getString("tag_name")
            require(tag.startsWith("v")) { "The release has an unsupported tag." }
            val version = tag.removePrefix("v")
            if (!isNewer(current, version)) return null
            val expectedName = "Unspektrawesome-$version.apk"
            val assets = release.getJSONArray("assets")
            val matches = (0 until assets.length()).map(assets::getJSONObject)
                .filter { it.optString("name") == expectedName && it.optString("state") == "uploaded" }
            require(matches.size == 1) { "The release APK is not available yet. Try again later." }
            val asset = matches.single()
            val url = asset.getString("browser_download_url")
            require(repository in REPOSITORIES &&
                url == "https://github.com/$repository/releases/download/$tag/$expectedName") {
                "The release APK has an unexpected download address."
            }
            val size = asset.getLong("size")
            require(size in 1..MAX_APK_BYTES) { "The release APK has an invalid size." }
            val digest = asset.optString("digest").takeIf { it.isNotEmpty() && it != "null" }
            require(digest == null || Regex("sha256:[a-fA-F0-9]{64}").matches(digest)) {
                "The release APK has an invalid checksum."
            }
            return UpdateRelease(version, release.optString("body").takeUnless { it == "null" }.orEmpty(),
                url, size, digest?.substringAfter(':')?.lowercase())
        }

        const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}
