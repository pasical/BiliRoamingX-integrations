package app.revanced.bilibili.settings.fragments

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.Preference
import app.revanced.bilibili.integrations.BuildConfig
import app.revanced.bilibili.patches.okhttp.hooks.Upgrade
import app.revanced.bilibili.settings.Settings
import app.revanced.bilibili.utils.*
import java.io.File
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AboutFragment : BiliRoamingBaseSettingFragment("biliroaming_setting_about") {
    @Keep
    private var checkUpdateMethod = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findPreference<Preference>("version")?.run {
            summary =
                Utils.getString("biliroaming_version_summary_pattern", BuildConfig.VERSION_NAME)
            onClick { onVersionClick(); true }
        }
        findPreference<Preference>("debug")?.summary =
            Utils.getString("biliroaming_debug_summary", logFile.absolutePath)
        findPreference<Preference>("feedback")?.onClick {
            val uri = Uri.parse(Utils.getString("biliroaming_feedback_url"))
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
            true
        }
        findPreference<Preference>("project_url")?.onClick {
            val uri = Uri.parse(Utils.getString("biliroaming_project_url"))
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
            true
        }
        findPreference<Preference>("tg_link")?.onClick {
            val uri = Uri.parse(Utils.getString("biliroaming_tg_url"))
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
            true
        }
        findPreference<Preference>("save_log")?.run {
            summary = Utils.getString("biliroaming_save_log_summary", pubLogFile.absolutePath)
            onClick { saveLog(); true }
        }
    }

    private fun saveLog() {
        fun saveLog() = runCatching {
            val pubLogFile = pubLogFile.also {
                it.parentFile?.mkdirs(); it.delete()
            }
            val context = Utils.getContext()
            val tombstonesDir = File(context.filesDir, "tombstones")
            val tombstonesFiles = tombstonesDir.listFiles().orEmpty()
                .sortedByDescending { it.lastModified() }.take(5)
            ZipOutputStream(pubLogFile.outputStream()).use { zipOutput ->
                tombstonesFiles.forEach { file ->
                    val zipEntry = ZipEntry("tombstones/${file.name}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        zipEntry.lastModifiedTime = FileTime.fromMillis(file.lastModified())
                    zipOutput.putNextEntry(zipEntry)
                    file.inputStream().use { it.copyTo(zipOutput) }
                }
                if (logFile.isFile) {
                    zipOutput.putNextEntry(ZipEntry(logFile.name))
                    logFile.inputStream().use { it.copyTo(zipOutput) }
                }
                if (oldLogFile.isFile) {
                    zipOutput.putNextEntry(ZipEntry(oldLogFile.name))
                    oldLogFile.inputStream().use { it.copyTo(zipOutput) }
                }
                val appArch64 = context.applicationInfo.nativeLibraryDir.substringAfterLast('/')
                    .let { it == "arm64" || it == "x86_64" }
                mapOf(
                    "os_ver" to Build.VERSION.RELEASE,
                    "api_level" to Build.VERSION.SDK_INT,
                    "device" to Build.MANUFACTURER + " " + Build.MODEL,
                    "abi_list" to Build.SUPPORTED_ABIS.joinToString(","),
                    "os_arch64" to Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
                    "prebuilt" to (sigMd5() == Constants.PRE_BUILD_SIG_MD5),
                    "app_id" to context.packageName,
                    "app_ver_name" to versionName,
                    "app_ver_code" to versionCode,
                    "app_arch64" to appArch64,
                    "module_ver_name" to BuildConfig.VERSION_NAME,
                    "module_ver_code" to BuildConfig.VERSION_CODE,
                    "module_settings" to Settings.entries.associate { it.key to it.value }
                ).toJSONObject().toString(2).run {
                    zipOutput.putNextEntry(ZipEntry("info.json"))
                    zipOutput.write(toByteArray())
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(pubLogFile.absolutePath), null, null)
            File(context.cacheDir, "boxing/${pubLogFile.name}").also {
                pubLogFile.copyTo(it, true)
            }
        }.onSuccess { shareFile ->
            Utils.runOnMainThread {
                AlertDialog.Builder(context)
                    .setMessage("日志成功保存至 ${pubLogFile.absolutePath}，是否立即分享？")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val uri = Uri.Builder()
                            .scheme("content")
                            .encodedAuthority("${Utils.getContext().packageName}.fileprovider")
                            .path("/internal/${shareFile.name}")
                            .build()
                        Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            setType("application/zip")
                        }, "分享日志").also { startActivity(it) }
                    }.create().constraintSize().show()
            }
        }.onFailure {
            Toasts.showShort("日志保存失败")
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            Utils.async { saveLog() }
        } else (context as Activity).requestPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) { granted, shouldExplain ->
            if (granted) {
                Utils.async { saveLog() }
            } else if (shouldExplain) {
                Toasts.showShortWithId("biliroaming_write_storage_failed")
            }
        }
    }

    private fun onVersionClick() {
        if (Upgrade.customUpdate(fromSelf = true)) {
            runCatching {
                val (serviceClass, checkUpdateMethod) = checkUpdateMethod.split('#', limit = 2)
                val activity = context as Activity
                Upgrade.fromSelf = true
                Class.forName(serviceClass).new().callMethod(checkUpdateMethod, activity)
            }.onFailure {
                Upgrade.fromSelf = false
                Logger.error(it) { "Update check failed" }
            }
        } else if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
            Toasts.showShortWithId("biliroaming_custom_update_only_64")
        } else if (sigMd5() != Constants.PRE_BUILD_SIG_MD5) {
            Toasts.showShortWithId("biliroaming_custom_update_invalid_sig")
        }
    }
}