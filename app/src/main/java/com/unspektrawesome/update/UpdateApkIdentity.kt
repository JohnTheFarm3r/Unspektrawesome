package com.unspektrawesome.update

internal data class UpdateApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val debuggable: Boolean,
    val channel: String?,
    val minSdk: Int,
    val signers: Set<String>,
) {
    fun validate(installed: UpdateApkIdentity, releaseVersion: String, deviceSdk: Int) {
        require(packageName == installed.packageName) { "This update is for a different app." }
        require(versionCode > installed.versionCode && versionName == releaseVersion) {
            "This APK does not contain a newer version of the app."
        }
        require(!debuggable && channel == "staging") { "Only staging updates can be installed." }
        require(minSdk <= deviceSdk) { "This update needs a newer Android version." }
        require(installed.signers.isNotEmpty() && installed.signers == signers) {
            "The update signature does not match the installed app."
        }
    }
}
