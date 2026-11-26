package org.vpinball.app.download

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class ArchiveCatalogTest {
    private fun file(path: String, size: Long = 10) = """<file name="$path"><size>$size</size><sha1>${"a".repeat(40)}</sha1></file>"""
    private fun parse(vararg files: String) = ArchiveCatalog.parse(ByteArrayInputStream(("<files>" + files.joinToString("") + "</files>").toByteArray()))
    @Test fun catalogGroupsCompanionsAndRoms() {
        val catalog = parse(file("tables/Test/Test.vpx"), file("tables/Test/Test.directb2s"), file("tables/Test/music/a.mp3"),
            file("roms/test_123.zip"), file("nvram/test_123.nv"), file("misc/other.vpt"))
        assertEquals(1, catalog.tables.size)
        assertEquals(2, catalog.tables.single().companions.size)
        assertEquals(30L, catalog.tables.single().bytes)
        assertEquals("roms/test_123.zip", catalog.roms["test_123"]?.path)
        assertEquals("nvram/test_123.nv", catalog.nvram["test_123"]?.path)
    }
    @Test fun malformedAndUnsafeEntriesAreExcluded() {
        assertTrue(parse(file("tables/../Test.vpx"), file("tables/Bad/Test.vpx", -1), "<file name=\"tables/X/X.vpx\"><size>10</size></file>").tables.isEmpty())
        listOf("../x", "/root", "a/../../x", "a\\x", "C:/x", "a//x", "a/./x").forEach { assertFalse(it, ArchiveCatalog.safePath(it)) }
    }
    @Test fun urlEncodesFilenameWithoutLosingDirectories() {
        assertEquals(ArchiveCatalog.BASE + "/tables/A%20%26%20B/A%2BB%23.vpx", ArchiveCatalog.url("tables/A & B/A+B#.vpx"))
    }
    @Test fun romDeclarationsIgnoreCommentedChoicesAndPreserveAmbiguity() {
        assertEquals(listOf("test_123"), ArchiveCatalog.romNames("' Const cGameName = \"unused\"\nConst cGameName = \"test_123\"\n.GameName = cGameName"))
        assertEquals(listOf("one", "two"), ArchiveCatalog.romNames("cGameName = \"one\"\ncGameName = \"two\""))
        assertTrue(ArchiveCatalog.romNames(".GameName = variable").isEmpty())
        assertEquals(listOf("direct"), ArchiveCatalog.romNames(".GameName = \"direct\""))
    }
    @Test(expected = Exception::class) fun rejectsExternalEntityDocuments() {
        ArchiveCatalog.parse(ByteArrayInputStream("<!DOCTYPE files [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><files>&x;</files>".toByteArray()))
    }
}
