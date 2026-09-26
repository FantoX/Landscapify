package dev.landscapify.app

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.io.File

data class LibraryApp(
    val packageName: String,
    val name: String,
    val addedAt: Long,
    val unsupported: Boolean = false,
)

class AppCatalog(private val app: Application) {
    private val prefs = app.getSharedPreferences("landscapify", 0)
    private val pm = app.packageManager

    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) { prefs.edit().putBoolean("paused", value).apply() }

    fun library(): List<LibraryApp> = runCatching {
        val json = JSONArray(prefs.getString("library", "[]"))
        (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            LibraryApp(
                item.getString("package"),
                item.getString("name"),
                item.getLong("added"),
                item.optBoolean("unsupported"),
            )
        }.sortedBy { it.addedAt }
    }.getOrDefault(emptyList())

    private fun save(items: List<LibraryApp>) {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("package", it.packageName).put("name", it.name)
                .put("added", it.addedAt).put("unsupported", it.unsupported))
        }
        prefs.edit().putString("library", array.toString()).apply()
    }

    fun add(items: List<LibraryApp>) {
        val existing = library().associateBy { it.packageName }.toMutableMap()
        items.forEach { existing.putIfAbsent(it.packageName, it) }
        save(existing.values.sortedBy { it.addedAt })
    }

    fun remove(packageName: String) = save(library().filterNot { it.packageName == packageName })

    fun markUnsupported(packageName: String) = save(library().map {
        if (it.packageName == packageName) it.copy(unsupported = true) else it
    })

    fun resetUnsupported() = save(library().map { it.copy(unsupported = false) })

    fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)

    fun installedApps(): List<LibraryApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val collator = Collator.getInstance()
        return activities.asSequence()
            .filter { it.activityInfo.packageName != app.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                LibraryApp(it.activityInfo.packageName, it.loadLabel(pm).toString(),
                    System.currentTimeMillis())
            }
            .sortedWith { a, b -> collator.compare(a.name, b.name) }
            .toList()
    }

}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val catalog = AppCatalog(application)
    val library = mutableStateListOf<LibraryApp>()
    var paused = mutableStateOf(catalog.paused)
        private set
    var message = mutableStateOf<String?>(null)
        private set
    var recoveryNeeded = mutableStateOf(false)
        private set

    init {
        val prefs = application.getSharedPreferences("landscapify", 0)
        if (!prefs.contains("pending_restore")) {
            prefs.edit().remove("adb_paired").remove("adb_port").apply()
            File(application.noBackupFilesDir, "adb-cert.der").delete()
            File(application.noBackupFilesDir, "adb-private.pk8").delete()
        }
        if (prefs.getInt("orientation_method", 0) == 0) {
            catalog.resetUnsupported()
            prefs.edit().putInt("orientation_method", 1).apply()
        }
        if (prefs.getString("last_error", null) ==
            "This Android build does not expose the required orientation override.") {
            prefs.edit().remove("last_error").apply()
        }
        refresh()
    }

    fun refresh() {
        library.clear()
        library.addAll(catalog.library())
        paused.value = catalog.paused
        val prefs = getApplication<Application>().getSharedPreferences("landscapify", 0)
        prefs.getString("last_error", null)?.let { message.value = it }
        recoveryNeeded.value = prefs.contains("pending_restore") && !SessionService.running
        prefs.edit().remove("last_error").apply()
    }

    fun clearMessage() { message.value = null }
    fun showMessage(value: String) { message.value = value }

    fun add(packages: List<LibraryApp>) {
        catalog.add(packages)
        refresh()
    }

    fun remove(packageName: String) {
        catalog.remove(packageName)
        refresh()
    }

    fun setPaused(value: Boolean) {
        catalog.paused = value
        paused.value = value
    }
}
