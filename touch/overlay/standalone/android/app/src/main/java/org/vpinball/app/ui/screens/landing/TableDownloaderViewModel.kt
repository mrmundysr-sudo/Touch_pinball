// license:GPLv3+
package org.vpinball.app.ui.screens.landing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vpinball.app.TableManager
import org.vpinball.app.VPinballManager
import org.vpinball.app.download.*
import org.vpinball.app.jni.VPinballStatus

data class DownloaderState(
    val entries: List<ArchiveTable> = emptyList(), val loading: Boolean = false,
    val busy: Boolean = false, val importing: Boolean = false,
    val progress: Float? = null, val message: String = "", val installed: Set<String> = emptySet(),
)

class TableDownloaderViewModel(private val context: Context) : ViewModel() {
    private val mutable = MutableStateFlow(DownloaderState())
    val state = mutable.asStateFlow()
    private var catalog: ArchiveCatalog? = null
    private var job: Job? = null
    @Volatile private var connection: HttpURLConnection? = null
    private val prefs = context.getSharedPreferences("archive_installs", Context.MODE_PRIVATE)
    private fun update(change: (DownloaderState) -> DownloaderState) { mutable.value = change(mutable.value) }

    fun loadIndex(refresh: Boolean = false) {
        if (state.value.busy || state.value.loading) return
        if (catalog != null && !refresh) { updateInstalled(); return }
        job = viewModelScope.launch {
            update { it.copy(loading = true, message = "Loading Archive.org tables…") }
            val cache = File(context.cacheDir, "touch-archive-index.xml")
            val temporary = File(context.cacheDir, "touch-archive-index.part")
            try {
                var offline = false
                val parsed = withContext(Dispatchers.IO) {
                    if (refresh || !cache.exists() || System.currentTimeMillis() - cache.lastModified() > 86400000L) {
                        try {
                            fetch(ArchiveCatalog.INDEX, temporary, null) {}
                            temporary.inputStream().use { ArchiveCatalog.parse(it) }.also {
                                check(it.tables.isNotEmpty()) { "The collection has no supported tables" }
                                check(temporary.renameTo(cache)) { "Could not save catalog" }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            if (!cache.exists()) throw e
                            offline = true
                            cache.inputStream().use { ArchiveCatalog.parse(it) }
                        }
                    } else cache.inputStream().use { ArchiveCatalog.parse(it) }
                }
                catalog = parsed
                update { it.copy(entries = parsed.tables, message = "${parsed.tables.size} tables" + if (offline) " • saved catalog; refresh when online" else " • select a table to download") }
                updateInstalled()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { update { it.copy(message = "Could not load tables: ${describe(e)}. Tap Refresh to retry.") } }
            finally { temporary.delete(); update { it.copy(loading = false) } }
        }
    }

    private fun updateInstalled() {
        val ids = TableManager.getInstance().tables.value.map { it.uuid }.toSet()
        update { it.copy(installed = prefs.all.filterValues { uuid -> uuid in ids }.keys) }
    }

    fun install(entry: ArchiveTable) {
        val index = catalog ?: return
        if (state.value.busy || entry.table.path in state.value.installed) return
        job = viewModelScope.launch {
            update { it.copy(busy = true, progress = 0f, message = "Downloading ${entry.title}…") }
            val work = File(context.cacheDir, "touch-download-${UUID.randomUUID()}")
            try {
                withContext(Dispatchers.IO) {
                    check(work.mkdirs()) { "Could not create download folder" }
                    check(work.usableSpace > entry.bytes * 3 + 64L * 1024 * 1024) { "Not enough free storage" }
                }
                val table = File(work, entry.table.name)
                val extras = linkedMapOf<String, File>()
                var done = 0L
                suspend fun get(file: ArchiveFile, dest: File, label: String) {
                    update { it.copy(message = label) }
                    fetch(ArchiveCatalog.url(file.path), dest, file) { bytes ->
                        update { it.copy(progress = ((done + bytes).toFloat() / entry.bytes.coerceAtLeast(1)).coerceIn(0f, 1f)) }
                    }
                    done += file.size
                }
                get(entry.table, table, "Downloading table: ${entry.title}")
                val folder = entry.table.path.substringBeforeLast('/') + "/"
                for (file in entry.companions) {
                    val relative = file.path.removePrefix(folder)
                    require(ArchiveCatalog.safePath(relative))
                    val target = File(work, "extras/$relative")
                    get(file, target, "Downloading ${file.name}")
                    // Match the backglass filename to the table, as required by the player.
                    val destination = if (file.name.endsWith(".directb2s", true)) table.nameWithoutExtension + ".directb2s" else relative
                    check(extras.put(destination, target) == null) { "Conflicting companion filenames" }
                }
                update { it.copy(progress = null, message = "Checking required ROM…") }
                val extracted = File(work, table.nameWithoutExtension + ".vbs")
                val patch = extras[table.nameWithoutExtension + ".vbs"]
                val script = withContext(Dispatchers.IO) {
                    if (patch != null) patch.readText(Charsets.ISO_8859_1)
                    else if (VPinballManager.vpinballJNI.VPinballExtractTableScript(table.absolutePath) == VPinballStatus.SUCCESS.value && extracted.exists())
                        extracted.readText(Charsets.ISO_8859_1) else null
                }
                val names = script?.let { ArchiveCatalog.romNames(it) }.orEmpty()
                // Ambiguous switching scripts need an explicit compatibility message, not a guessed ROM.
                check(names.size <= 1) { "This table selects multiple ROMs and needs manual setup. Import it using Files." }
                var note = if (script == null) "ROM requirements could not be checked; manual setup may be needed" else ""
                if (names.isNotEmpty()) {
                    val romName = names.single()
                    val rom = index.roms[romName] ?: error("Required ROM $romName is missing from this collection")
                    val dest = File(work, "roms/${rom.name}")
                    fetch(ArchiveCatalog.url(rom.path), dest, rom) { transferred -> update { it.copy(message = "Downloading ROM $romName: ${itSize(transferred)}") } }
                    extras["pinmame/roms/${rom.name}"] = dest
                    index.nvram[romName]?.let { nv ->
                        val nvDest = File(work, "nvram/${nv.name}")
                        fetch(ArchiveCatalog.url(nv.path), nvDest, nv) {}
                        extras["pinmame/nvram/${nv.name}"] = nvDest
                    }
                } else if (script != null && Regex("(?i)\\b(?:cGameName|VPinMAME|GameName)\\b").containsMatchIn(script)) {
                    note = "ROM name is dynamic; manual setup may be needed"
                }
                ensureActive()
                update { it.copy(importing = true, message = "Installing table and companion files…", progress = null) }
                withContext(NonCancellable) {
                    val imported = TableManager.getInstance().importDownloadedTable(table, extras) ?: error("Table installation failed")
                    prefs.edit().putString(entry.table.path, imported.uuid).apply()
                    updateInstalled()
                    update { it.copy(message = "Installed ${entry.title}. Return to Library to play." + if (note.isNotEmpty()) " $note." else "") }
                }
            } catch (e: CancellationException) {
                update { it.copy(message = "Download cancelled. No table was installed.") }
                throw e
            } catch (e: Exception) { update { it.copy(message = "Installation failed: ${describe(e)}. Select the table to retry.") } }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { work.deleteRecursively() }
                update { it.copy(busy = false, importing = false, progress = null) }
            }
        }
    }

    fun cancel() { if (!state.value.importing) { job?.cancel(); connection?.disconnect() } }
    override fun onCleared() { connection?.disconnect(); super.onCleared() }

    /** Stream to disk with cancellation, size and SHA-1 verification. Never accept an HTML error as a table. */
    private suspend fun fetch(url: String, dest: File, expected: ArchiveFile?, bytes: (Long) -> Unit) = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 1..3) {
            ensureActive()
            try {
                dest.parentFile?.mkdirs()
                val conn = URL(url).openConnection() as HttpURLConnection
                connection = conn
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "TouchPinball/1.0 (personal Android client)")
                try {
                    val code = conn.responseCode
                    if (code == 401 || code == 403) error("Archive.org has restricted this file (HTTP $code)")
                    if (code == 429 || code >= 500) throw IOException("Archive.org is busy (HTTP $code)")
                    check(code in 200..299) { "Download unavailable (HTTP $code)" }
                    check(!conn.contentType.orEmpty().contains("text/html", true)) { "Server returned an error page" }
                    val limit = expected?.size ?: 32L * 1024 * 1024
                    val digest = MessageDigest.getInstance("SHA-1")
                    var count = 0L
                    var lastReport = 0L
                    conn.inputStream.use { input -> dest.outputStream().use { output ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            count += n
                            check(count <= limit) { "Downloaded file exceeds expected size" }
                            digest.update(buf, 0, n)
                            output.write(buf, 0, n)
                            if (count - lastReport > 512 * 1024) { lastReport = count; bytes(count) }
                        }
                    } }
                    if (expected != null) {
                        check(count == expected.size) { "Incomplete download: ${expected.name}" }
                        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        check(sha.equals(expected.sha1, true)) { "Checksum mismatch: ${expected.name}" }
                    }
                    bytes(count)
                    return@withContext
                } finally { conn.disconnect(); connection = null }
            } catch (e: CancellationException) { dest.delete(); throw e }
            catch (e: IOException) { ensureActive(); last = e; dest.delete(); if (attempt < 3) delay(attempt * 1500L) }
        }
        throw last ?: IOException("Download failed")
    }
    private fun itSize(bytes: Long) = "${bytes / (1024 * 1024)} MB"
    private fun describe(e: Exception): String = when(e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "Archive.org did not respond in time"
        else -> e.message ?: e.javaClass.simpleName
    }
}
