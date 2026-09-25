package com.lilyanmuller.glassislands.environment

import com.intellij.openapi.application.ApplicationInfo

data class EnvironmentInfo(
    val ideName: String,
    val ideVersion: String,
    val ideBuild: String,
    val runtimeVersion: String,
    val runtimeVendor: String,
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val nativePlatform: NativePlatform?,
) {
    fun summary(): String =
        "$ideName $ideVersion ($ideBuild), runtime $runtimeVendor $runtimeVersion, $osName $osVersion ($osArch)"

    companion object {
        fun detect(): EnvironmentInfo {
            val app = ApplicationInfo.getInstance()
            val osName = System.getProperty("os.name").orEmpty()
            val osArch = System.getProperty("os.arch").orEmpty()
            return EnvironmentInfo(
                ideName = app.versionName,
                ideVersion = app.fullVersion,
                ideBuild = app.build.asString(),
                runtimeVersion = System.getProperty("java.runtime.version").orEmpty(),
                runtimeVendor = System.getProperty("java.vendor").orEmpty(),
                osName = osName,
                osVersion = System.getProperty("os.version").orEmpty(),
                osArch = osArch,
                nativePlatform = NativePlatform.detect(osName, osArch),
            )
        }
    }
}
