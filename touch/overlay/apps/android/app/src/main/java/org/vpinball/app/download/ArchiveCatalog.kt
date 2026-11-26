// license:GPLv3+
package org.vpinball.app.download

import java.io.InputStream
import java.io.StringReader
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class ArchiveFile(val path: String, val size: Long, val sha1: String) {
    val name: String get() = path.substringAfterLast('/')
}
data class ArchiveTable(val title: String, val table: ArchiveFile, val companions: List<ArchiveFile>) {
    val bytes: Long get() = table.size + companions.sumOf { it.size }
}
data class ArchiveCatalog(val tables: List<ArchiveTable>, val roms: Map<String, ArchiveFile>, val nvram: Map<String, ArchiveFile>) {
    companion object {
        const val ITEM = "Visual-Pinball-Collection-2025-12-29"
        const val BASE = "https://archive.org/download/$ITEM"
        const val INDEX = "$BASE/${ITEM}_files.xml"

        fun safePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
            !path.contains('\\') && !path.contains(':') && path.none { it.code < 32 } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        fun url(path: String): String {
            require(safePath(path)) { "Invalid archive path" }
            return BASE + "/" + path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        }

        fun parse(input: InputStream): ArchiveCatalog {
            // Android's Harmony parser does not support Xerces security feature flags.
            // Parse a UTF-8 character source after rejecting DTDs/entities, on every platform.
            val bytes = input.readNBytes(32 * 1024 * 1024 + 1)
            require(bytes.size <= 32 * 1024 * 1024) { "Catalog is too large" }
            val xml = bytes.toString(Charsets.UTF_8)
            require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "External XML declarations are forbidden" }
            val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw SAXException("External XML entities are forbidden") }
            val doc = builder.parse(InputSource(StringReader(xml)))
            val files = mutableListOf<ArchiveFile>()
            val nodes = doc.getElementsByTagName("file")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                val path = el.getAttribute("name")
                val size = el.getElementsByTagName("size").item(0)?.textContent?.trim()?.toLongOrNull() ?: continue
                val sha = el.getElementsByTagName("sha1").item(0)?.textContent?.trim().orEmpty()
                if (safePath(path) && size > 0 && sha.matches(Regex("[0-9a-fA-F]{40}"))) files.add(ArchiveFile(path, size, sha))
            }
            val groups = files.filter { it.path.startsWith("tables/") }.groupBy { it.path.split('/').take(2).joinToString("/") }
            val tables = groups.flatMap { (folder, group) ->
                group.filter { it.name.endsWith(".vpx", true) }.map { table ->
                    // Keep all auxiliary files and their relative paths, including VBS patches, music and backglass.
                    ArchiveTable(folder.removePrefix("tables/") + if (group.count { it.name.endsWith(".vpx", true) } > 1) " — ${table.name}" else "",
                        table, group.filter { it != table && !it.name.endsWith(".vpx", true) })
                }
            }.sortedBy { it.title.lowercase() }
            fun index(folder: String, ext: String) = files.filter { it.path.startsWith("$folder/") && it.name.endsWith(ext, true) }
                .associateBy { it.name.dropLast(ext.length).lowercase() }
            return ArchiveCatalog(tables, index("roms", ".zip"), index("nvram", ".nv"))
        }

        fun romNames(script: String): List<String> {
            // Match literal declarations, including references where .GameName = cGameName.
            val clean = script.lineSequence().filterNot { it.trimStart().startsWith("'") }.joinToString("\n")
            return Regex("""(?im)(?:^\s*(?:(?:Const|Dim)\s+)?cGameName|\.GameName|^\s*(?:Const\s+)?GameName)\s*=\s*"([A-Za-z0-9_\-]+)"""")
                .findAll(clean).map { it.groupValues[1].lowercase() }.distinct().toList()
        }
    }
}
