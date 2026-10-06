package com.v2ray.ang.dto

/**
 * Update the owner published for the customer app through the panel.
 *
 * The panel writes this as `update.json` at the root of the same repository the
 * activation row lives in, so the app reads it without knowing any panel address.
 */
data class AppUpdateNotice(
    val version: String = "",
    val url: String = "",
    val notes: String = "",
) {
    /**
     * Whether this notice is worth showing. A notice with no version or no download
     * link cannot drive an update, and one that is not newer than the running build is
     * already installed.
     */
    fun isNewerThan(currentVersion: String): Boolean =
        version.isNotBlank() && url.isNotBlank() && compareVersions(version, currentVersion) > 0

    companion object {
        /**
         * Compares dotted numeric versions, tolerating missing parts so `2.4` and
         * `2.4.0` are the same version.
         */
        fun compareVersions(version1: String, version2: String): Int {
            val v1 = version1.trim().removePrefix("v").split(".")
            val v2 = version2.trim().removePrefix("v").split(".")
            for (i in 0 until maxOf(v1.size, v2.size)) {
                val num1 = v1.getOrNull(i)?.toIntOrNull() ?: 0
                val num2 = v2.getOrNull(i)?.toIntOrNull() ?: 0
                if (num1 != num2) return num1.compareTo(num2)
            }
            return 0
        }
    }
}