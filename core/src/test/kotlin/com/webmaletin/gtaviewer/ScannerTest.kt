package com.webmaletin.gtaviewer

import com.webmaletin.gtaviewer.catalog.*
import com.webmaletin.gtaviewer.gta.*
import com.webmaletin.gtaviewer.zip.ZipArchive
import java.io.File
import kotlin.test.*

class ScannerTest {
    private fun dff(size: Float = 1f): ByteArray = RwTestWriter.dff(
        listOf(RwTestWriter.TestFrame(null, -1), RwTestWriter.TestFrame("chassis", 0)),
        listOf(RwTestWriter.box(0f, 0f, 0f, size, size * 2, size, listOf(RwTestWriter.TestMaterial(255, 255, 255, 255, "body")))),
        listOf(RwTestWriter.TestAtomic(1, 0)),
    )
    private fun partDff(): ByteArray = RwTestWriter.dff(
        listOf(RwTestWriter.TestFrame(null, -1), RwTestWriter.TestFrame("wheel", 0)),
        listOf(RwTestWriter.box(0f, 0f, 0f, .3f, .6f, .6f, listOf(RwTestWriter.TestMaterial(1, 1, 1, 255, null)))),
        listOf(RwTestWriter.TestAtomic(1, 0)),
    )
    private val txd = RwTestWriter.txd(listOf(RwTestWriter.TestTexture("body", 4, 4, 21, RwTestWriter.solidBgra(4, 4, 1, 2, 3))))

    private fun scan(files: Map<String, ByteArray>): ScanReport {
        val f = File.createTempFile("scan", ".zip"); f.deleteOnExit()
        f.writeBytes(RwTestWriter.zip(files))
        val z = ZipArchive.open(0, f)
        try { return GtaSaScanner().scan(z.entries) } finally { z.close() }
    }

    private fun ScanReport.names() = vehicles.map { it.displayName }

    // ---- Tests obligatorios del enunciado -------------------------------------------------------------

    @Test fun t1_admiral() {
        val r = scan(mapOf("admiral/admiral.dff" to dff(), "admiral/admiral.txd" to txd, "admiral/admiral.txt" to "x".toByteArray()))
        assertEquals(1, r.vehicles.size)
        val v = r.vehicles[0]
        assertEquals("Admiral", v.displayName); assertEquals(VehicleStatus.COMPLETE, v.status)
        assertEquals("admiral.dff", v.dff.fileName); assertEquals("admiral.txd", v.txd!!.fileName)
    }

    @Test fun t2_audiRs6a_formatB() {
        val r = scan(mapOf(
            "AUDI/audi.fxt" to "x".toByteArray(),
            "AUDI/audicars/rs6a.dff" to dff(1f), "AUDI/audicars/rs6a.txd" to txd,
            "AUDI/audicars/r8.dff" to dff(1.1f), "AUDI/audicars/r8.txd" to txd,
            "BMW/bmw.fxt" to "x".toByteArray(),
            "BMW/bmwcars/m4gts.dff" to dff(1.2f), "BMW/bmwcars/m4gts.txd" to txd,
            "BMW/bmwcars/m3gtr.dff" to dff(1.3f), "BMW/bmwcars/m3gtr.txd" to txd,
            "BMW/bmwcars/m5.dff" to dff(1.4f), "BMW/bmwcars/m5.txd" to txd,
            "BUGATTI/bugatticars/veyron.dff" to dff(1.5f), "BUGATTI/bugatticars/veyron.txd" to txd,
            "BUGATTI/bugatticars/chiron.dff" to dff(1.6f), "BUGATTI/bugatticars/chiron.txd" to txd,
        ))
        assertEquals(7, r.vehicles.size, r.names().toString())
        assertTrue(r.vehicles.all { it.status == VehicleStatus.COMPLETE })
        assertTrue("RS6A" in r.names()); assertTrue("M4GTS" in r.names()); assertTrue("Veyron" in r.names())
        assertEquals(0, r.orphanTxds.size)
    }

    @Test fun t3_bagboxa_looseFilesAtRoot() {
        val r = scan(mapOf("bagboxa.DFF" to dff(), "bagboxa.txd" to txd))
        assertEquals(1, r.vehicles.size)
        assertEquals("bagboxa", r.vehicles[0].rawName)
        assertEquals("Bagboxa", r.vehicles[0].displayName)
        assertNotNull(r.vehicles[0].txd)
    }

    @Test fun t4_audiA3_tunableUsesFolderName() {
        val r = scan(mapOf(
            "Audi A3/audia3.fxt" to "x".toByteArray(),
            "Audi A3/tunable/audia3.dff" to dff(), "Audi A3/tunable/audia3.txd" to txd,
            "Audi A4/audia4.dff" to dff(1.1f), "Audi A4/audia4.fxt" to "x".toByteArray(), "Audi A4/audia4.txd" to txd,
            "Audi TT/auditt.dff" to dff(1.2f), "Audi TT/auditt.fxt" to "x".toByteArray(), "Audi TT/auditt.txd" to txd,
        ))
        assertEquals(listOf("Audi A3", "Audi A4", "Audi TT"), r.names())
        assertTrue(r.vehicles.all { it.nameSource == NameSource.FOLDER && it.txd != null })
    }

    @Test fun t5_dffWithoutTxd_isVehicleWithWarning() {
        val r = scan(mapOf("lonely/lonely.dff" to dff()))
        assertEquals(1, r.vehicles.size)
        assertEquals(VehicleStatus.TXD_MISSING, r.vehicles[0].status)
        assertNull(r.vehicles[0].txd)
        assertTrue(r.vehicles[0].warnings.any { it.contains("TXD faltante") })
        assertEquals(1, r.dffWithoutTxd)
    }

    @Test fun t6_txdWithoutDff_createsNoVehicle() {
        val r = scan(mapOf("ghost/ghost.txd" to txd, "real/real.dff" to dff(), "real/real.txd" to txd))
        assertEquals(listOf("Real"), r.names())
        assertEquals(1, r.orphanTxds.size)
        assertEquals("ghost.txd", r.orphanTxds[0].fileName)
    }

    @Test fun t7_sameBasenameInDifferentFolders_resolvedByPath() {
        val txdA = RwTestWriter.txd(listOf(RwTestWriter.TestTexture("a", 4, 4, 21, RwTestWriter.solidBgra(4, 4, 9, 9, 9))))
        val txdB = RwTestWriter.txd(listOf(RwTestWriter.TestTexture("b", 4, 4, 21, RwTestWriter.solidBgra(4, 4, 7, 7, 7))))
        val r = scan(mapOf(
            "folderA/car.dff" to dff(1f), "folderA/car.txd" to txdA,
            "folderB/car.dff" to dff(2f), "folderB/car.txd" to txdB,
        ))
        assertEquals(2, r.vehicles.size)
        for (v in r.vehicles) assertEquals(v.dff.dir, v.txd!!.dir, "cada DFF debe usar el TXD de su carpeta")
        assertEquals(setOf("Folder A", "Folder B"), r.names().toSet().ifEmpty { setOf() }.let { r.names().toSet() })
    }

    @Test fun t7b_sameBasename_secondHasNoTxd_doesNotStealOthers() {
        val r = scan(mapOf("A/car.dff" to dff(1f), "A/car.txd" to txd, "B/car.dff" to dff(2f)))
        assertEquals(2, r.vehicles.size)
        assertEquals(1, r.vehicles.count { it.txd != null })
        assertEquals("A", r.vehicles.first { it.txd != null }.dff.dir)
        assertEquals(VehicleStatus.TXD_MISSING, r.vehicles.first { it.txd == null }.status)
    }

    @Test fun t8_caseInsensitiveExtensionsAndNames() {
        val r = scan(mapOf("Sub/CAR.DFF" to dff(), "Sub/car.TXD" to txd))
        assertEquals(1, r.vehicles.size)
        assertNotNull(r.vehicles[0].txd)
        assertEquals(VehicleStatus.COMPLETE, r.vehicles[0].status)
    }

    // ---- Más casos -------------------------------------------------------------------------------------

    @Test fun formatA_manyAtRoot_andFormatC_mixed() {
        val r = scan(mapOf(
            "yosemite/yosemite.dff" to dff(1f), "yosemite/yosemite.txd" to txd, "yosemite/DeclasseYosemite.fxt" to "x".toByteArray(),
            "zr350/TruffadeAdder.fxt" to "x".toByteArray(), "zr350/zr350.dff" to dff(1.1f), "zr350/zr350.txd" to txd,
            "bagboxa.DFF" to dff(1.2f), "bagboxa.txd" to txd, "bagboxb.DFF" to dff(1.3f), "bagboxb.txd" to txd,
            "barracks.dff" to dff(1.4f), "barracks.txd" to txd,
        ))
        assertEquals(5, r.vehicles.size, r.names().toString())
        assertTrue(r.vehicles.all { it.status == VehicleStatus.COMPLETE })
        // .fxt mejora el nombre
        assertTrue("Declasse Yosemite" in r.names(), r.names().toString())
        assertTrue("Truffade Adder" in r.names(), r.names().toString())
        assertEquals(5, r.automaticPairs)
    }

    @Test fun normalizedNameMatchingAudi_A3() {
        val r = scan(mapOf("x/Audi_A3.dff" to dff(), "x/audi-a3.txd" to txd))
        assertEquals(1, r.vehicles.size)
        assertNotNull(r.vehicles[0].txd)
        assertTrue(r.vehicles[0].warnings.any { it.contains("normalizado") })
    }

    @Test fun dffAndTxdInDifferentFolders() {
        val r = scan(mapOf("models/audia3.dff" to dff(), "textures/audia3.txd" to txd))
        assertNotNull(r.vehicles.single().txd)
    }

    @Test fun logicalDuplicatesCollapsed_realVariantsKept() {
        val same = dff(1f)
        val r = scan(mapOf(
            "Audi/audia3.dff" to same, "Audi/audia3.txd" to txd,
            "Cars/audia3.dff" to same, "Cars/audia3.txd" to txd,
            "Backup/audia3.dff" to same, "Backup/audia3.txd" to txd,
            "Other/audia3.dff" to dff(3f), "Other/audia3.txd" to txd,
        ))
        assertEquals(2, r.vehicles.size, r.names().toString())
        assertEquals(2, r.duplicatesCollapsed)
        val names = r.names()
        assertEquals(2, names.toSet().size, "los nombres deben diferenciarse: $names")
        assertTrue(names.any { it.endsWith("(2)") } || names.toSet().size == 2)
    }

    @Test fun identicalDffWithoutTxdFoldsIntoOneWithTxd() {
        val same = dff(1f)
        val r = scan(mapOf("Audi/audia3.dff" to same, "Audi/audia3.txd" to txd, "Backup/audia3.dff" to same))
        assertEquals(1, r.vehicles.size)
        assertNotNull(r.vehicles[0].txd)
    }

    @Test fun separatedPartsAreListedAsRelatedNotDropped() {
        val r = scan(mapOf(
            "cool/cool.dff" to dff(), "cool/cool.txd" to txd,
            "cool/wheels.dff" to partDff(), "cool/tuning_parts.dff" to partDff(),
        ))
        assertEquals(1, r.vehicles.size, r.names().toString())
        val v = r.vehicles[0]
        assertEquals(2, v.relatedParts.size)
        assertEquals(VehicleStatus.SEPARATE_PARTS, v.status)
        assertEquals(2, r.separatedPartFiles)
    }

    @Test fun orphanPartIsKeptVisibleAsSuspect() {
        val r = scan(mapOf("lonelyparts/wheels.dff" to partDff()))
        assertEquals(1, r.vehicles.size)
        assertEquals(VehicleStatus.SUSPECT_DFF, r.vehicles[0].status)
    }

    @Test fun vehicleWithChassisIsNotMistakenForPartEvenIfNameLooksLikePart() {
        val r = scan(mapOf("seat/seat.dff" to dff(), "seat/seat.txd" to txd))
        assertEquals(1, r.vehicles.size)
        assertEquals(DffKind.VEHICLE, r.vehicles[0].classification.kind)
    }

    @Test fun unreadableDffReportsReadError() {
        val r = scan(mapOf("bad/bad.dff" to ByteArray(100) { 7 }, "bad/bad.txd" to txd))
        assertEquals(VehicleStatus.READ_ERROR, r.vehicles.single().status)
        assertEquals(1, r.errors)
    }

    @Test fun twoZipsAreNeverMixed() {
        val f1 = File.createTempFile("zipa", ".zip").also { it.deleteOnExit(); it.writeBytes(RwTestWriter.zip(mapOf("car.dff" to dff()))) }
        val f2 = File.createTempFile("zipb", ".zip").also { it.deleteOnExit(); it.writeBytes(RwTestWriter.zip(mapOf("car.txd" to txd))) }
        val z1 = ZipArchive.open(0, f1); val z2 = ZipArchive.open(1, f2)
        val r = GtaSaScanner().scan(z1.entries + z2.entries)
        assertNull(r.vehicles.single().txd)
        assertEquals(1, r.orphanTxds.size)
        z1.close(); z2.close()
    }

    @Test fun macosxJunkAndTraversalIgnored() {
        val r = scan(mapOf("__MACOSX/._car.dff" to ByteArray(10), "car.dff" to dff(), "car.txd" to txd, "../evil.dff" to dff()))
        assertEquals(1, r.vehicles.size)
    }

    @Test fun logTextHasCounters() {
        val r = scan(mapOf("a/a.dff" to dff(), "a/a.txd" to txd, "b/b.dff" to dff(2f), "c/c.txd" to txd))
        val t = r.toLogText()
        assertTrue(t.contains("2 vehículos detectados")); assertTrue(t.contains("TXD sin DFF"))
    }

    @Test fun prettyNames() {
        assertEquals("Audi A3", Names.pretty("audi_a3")); assertEquals("Audi A3", Names.pretty("audi-a3"))
        assertEquals("Audi A3", Names.pretty("AudiA3")); assertEquals("Audi A3", Names.pretty("Audi A3"))
        assertEquals("Audi", Names.pretty("AUDI")); assertEquals("BMW", Names.pretty("BMW"))
        assertEquals("Audi 100", Names.pretty("audi_100")) // no destruye nombres legítimos
        assertEquals("Audi", Names.pretty("401_audi"))
    }
}
