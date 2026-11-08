package xyz.elouan.sobosubamodloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import xyz.elouan.sobosubamodloader.axml.AxmlManifestPatcher
import xyz.elouan.sobosubamodloader.signing.ApkSignerUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File
import java.util.zip.ZipFile

data class GameInfo(val packageName: String, val versionName: String, val sourceApk: String)
data class InstalledMod(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val files: List<String>
)

class LoaderRepository(val context: Context) {
    companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 100
        const val DEBUG_PACKAGE = "xyz.elouan.sobosuba.debug"
        private const val PREFERENCES = "loader_preferences"
        private const val SEPARATE_INSTALL = "separate_install"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val packages = listOf(DEBUG_PACKAGE, "com.keepeetron.sobosuba", "com.keepeetron.sobosuba.beta")

    fun separateInstallEnabled(): Boolean = preferences.getBoolean(SEPARATE_INSTALL, false)

    fun setSeparateInstallEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(SEPARATE_INSTALL, enabled).apply()
    }

    fun isShizukuReady(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun requestShizukuPermission(): String {
        if (!Shizuku.pingBinder()) {
            return "Shizuku service is unavailable. Open Shizuku and start its service first."
        }
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "Shizuku permission is already granted."
        }
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        return "Shizuku permission request sent."
    }

    fun detectGame(): GameInfo? {
        val packageManager = context.packageManager
        return packages.firstNotNullOfOrNull { name ->
            try {
                val info = packageManager.getPackageInfo(name, 0)
                val apk = runShell("pm path $name").lineSequence()
                    .firstOrNull { it.startsWith("package:") }
                    ?.removePrefix("package:")
                    ?: return@firstNotNullOfOrNull null
                GameInfo(name, info.versionName ?: "unknown", apk)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun isDebuggable(packageName: String): Boolean {
        val result = runShell("run-as $packageName id")
        return result.contains("uid=") && !result.contains("not debuggable")
    }

    fun patchGame(packageName: String, callback: (String) -> Unit) {
        scope.launch {
            try {
                val sourceGame = detectSourceGame() ?: error("Unable to locate the official Sobosuba APK")
                val installPackage = if (separateInstallEnabled()) DEBUG_PACKAGE else sourceGame.packageName
                val localApk = File.createTempFile("sobosuba-base-", ".apk", context.cacheDir)
                writeShellFileToLocal(sourceGame.sourceApk, localApk)
                val unsignedPatched = File(context.cacheDir, "sobosuba-patched-unsigned.apk")
                val patched = File(context.cacheDir, "sobosuba-patched.apk")
                AxmlManifestPatcher.patchApk(
                    localApk,
                    unsignedPatched,
                    AxmlManifestPatcher.Options(
                        packageName = installPackage.takeIf { it != sourceGame.packageName },
                        appLabel = "Sobosuba (Modded)"
                    )
                )
                ApkSignerUtil.sign(context, unsignedPatched, patched)
                val remote = "/data/local/tmp/${patched.name}"
                pushLocalFile(patched, remote)
                val install = runShell("pm install -r $remote")
                runShell("rm -f $remote")
                localApk.delete()
                unsignedPatched.delete()
                patched.delete()
                if (!install.contains("Success", ignoreCase = true)) error(install.ifBlank { "Package installation failed" })
                callback(
                    if (installPackage == DEBUG_PACKAGE) {
                        "Separate modded copy installed as $DEBUG_PACKAGE."
                    } else {
                        "Game patched and installed. Reopen Sobosuba before installing a mod."
                    }
                )
            } catch (e: Exception) {
                callback("Patch failed: ${e.message ?: "unknown error"}")
            }

            private fun detectSourceGame(): GameInfo? =
                packages.drop(1).firstNotNullOfOrNull { name ->
                    try {
                        val info = context.packageManager.getPackageInfo(name, 0)
                        val apk = runShell("pm path $name").lineSequence()
                            .firstOrNull { it.startsWith("package:") }
                            ?.removePrefix("package:")
                            ?: return@firstNotNullOfOrNull null
                        GameInfo(name, info.versionName ?: "unknown", apk)
                    } catch (_: Exception) {
                        null
                    }
                }
        }
    }

    fun installMod(packageName: String, uri: Uri, callback: (String) -> Unit) {
        scope.launch {
            try {
                val archive = File.createTempFile("sobosuba-mod-", ".zip", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    archive.outputStream().use(input::copyTo)
                } ?: error("Unable to read selected ZIP")
                val extraction = File(context.cacheDir, "mod-${System.nanoTime()}").apply { mkdirs() }
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        val target = File(extraction, entry.name).canonicalFile
                        require(target.path.startsWith(extraction.canonicalPath + File.separator)) { "ZIP contains an unsafe path" }
                        if (entry.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
                        }
                    }
                }
                val files = extraction.walkTopDown().filter { it.isFile }.toList()
                require(files.any { it.name == "mod-package.json" }) { "ZIP does not contain mod-package.json" }
                files.forEach { file ->
                    val relative = file.relativeTo(extraction).invariantSeparatorsPath
                    val remote = "/data/local/tmp/sobosuba-${System.nanoTime()}"
                    pushLocalFile(file, remote)
                    val destination = "files/$relative"
                    val result = runShell("run-as $packageName sh -c 'mkdir -p \"$(dirname \"$destination\")\" && cp \"$remote\" \"$destination\"'; rm -f $remote")
                    if (result.contains("denied", true) || result.contains("error", true)) error(result)
                }
                archive.delete()
                extraction.deleteRecursively()
                callback("Mod installed successfully.")
            } catch (e: Exception) {
                callback("Mod installation failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun listMods(packageName: String): List<InstalledMod> {
        val jsonFiles = runShell("run-as $packageName find files -name mod-package.json -type f")
            .lineSequence().filter { it.startsWith("files/") }.toList()
        return jsonFiles.mapNotNull { path ->
            val json = runShell("run-as $packageName cat $path")
            val directory = path.substringBeforeLast('/')
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return@mapNotNull null
            InstalledMod(
                id = directory,
                name = name,
                version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "unknown",
                author = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "unknown",
                description = Regex("\"description\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: "",
                files = listOf(directory)
            )
        }
    }

    fun removeMod(packageName: String, mod: InstalledMod) {
        runShell("run-as $packageName rm -rf '${mod.id}'")
    }

    fun launchIntent(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)

    private fun runShell(command: String): String {
        check(isShizukuReady()) { "Shizuku is not connected or permission was denied" }
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        return try {
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            if (error.isNotBlank()) "$output\n$error".trim() else output.trim()
        } finally {
            process.destroy()
        }
    }

    private fun writeShellFileToLocal(remotePath: String, local: File) {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", "cat '$remotePath'"), null, null)
        process.inputStream.use { input -> local.outputStream().use(input::copyTo) }
        process.waitFor()
    }

    private fun pushLocalFile(local: File, remote: String) {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", "cat > '$remote'"), null, null)
        try {
            local.inputStream().use { input ->
                process.outputStream.use { output -> input.copyTo(output) }
            }
            check(process.waitFor() == 0) { "Unable to transfer ${local.name} to Shizuku" }
        } finally {
            process.destroy()
        }
    }
}
