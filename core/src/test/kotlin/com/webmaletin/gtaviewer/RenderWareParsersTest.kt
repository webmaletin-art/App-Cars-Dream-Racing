package com.webmaletin.gtaviewer

import com.webmaletin.gtaviewer.dff.GtaSaDffParser
import com.webmaletin.gtaviewer.renderware.RwException
import com.webmaletin.gtaviewer.txd.GtaSaTxdParser
import kotlin.test.*

class RenderWareParsersTest {
    @Test fun dffParsesFramesGeometriesMaterialsAndAtomics() {
        val doc = GtaSaDffParser().parse(RwTestWriter.sampleVehicleDff())
        assertEquals(10, doc.frames.size)
        assertEquals("chassis", doc.frames[1].name)
        assertEquals("wheel_rf_dummy", doc.frames[4].name)
        assertEquals(0.9f, doc.frames[4].position[0])
        assertEquals(5, doc.geometries.size)
        assertEquals(5, doc.atomics.size)
        val g0 = doc.geometries[0]!!
        assertEquals(8, g0.vertexCount)
        assertEquals(12, g0.triangles.size / 3)
        assertEquals(2, g0.materials.size)
        assertEquals("body", g0.materials[0].texture?.name)
        assertEquals(60, g0.materials[0].r)
        assertNull(g0.materials[1].texture?.name?.takeIf { it.isEmpty() })
        assertEquals(2, g0.binMesh!!.size)
        assertNotNull(g0.normals)
        assertEquals(16, g0.uvSets[0].size)
        assertTrue(doc.issues.isEmpty(), doc.issues.toString())
    }

    @Test fun dffSummaryReadsOnlyHeader() {
        val bytes = RwTestWriter.sampleVehicleDff()
        val s = GtaSaDffParser().summarize(bytes.copyOf(1500)) // truncado a propósito
        assertTrue(s.valid)
        assertTrue("chassis" in s.frameNames)
        assertEquals(10, s.frameCount)
        assertEquals(5, s.geometryCount)
    }

    @Test fun dffRejectsGarbage() {
        assertFailsWith<RwException> { GtaSaDffParser().parse(ByteArray(64) { it.toByte() }) }
        assertFalse(GtaSaDffParser().summarize(ByteArray(10)).valid)
    }

    @Test fun dffTruncatedGeometryReportsIssueInsteadOfCrashing() {
        val bytes = RwTestWriter.sampleVehicleDff()
        val doc = GtaSaDffParser().parse(bytes.copyOf(bytes.size - 400))
        assertTrue(doc.issues.isNotEmpty())
    }

    @Test fun txdDecodesBgraDxt1Dxt5() {
        val textures = listOf(
            RwTestWriter.TestTexture("body", 8, 8, 21, RwTestWriter.solidBgra(8, 8, 10, 20, 30), alpha = false),
            RwTestWriter.TestTexture("dxt1", 8, 8, GtaSaTxdParser.FOURCC_DXT1, RwTestWriter.solidDxt1(8, 8, 248, 0, 0), rasterFormat = 0x0100, depth = 16),
            RwTestWriter.TestTexture("glass", 8, 8, GtaSaTxdParser.FOURCC_DXT5, RwTestWriter.solidDxt5(8, 8, 0, 248, 0, 100), alpha = true),
        )
        val doc = GtaSaTxdParser().parse(RwTestWriter.txd(textures))
        assertEquals(3, doc.textures.size)
        doc.textures.forEach { assertNull(it.error, it.name) }
        val body = doc.find("BODY")!!.image!!
        assertEquals(10, body.rgba[0].toInt() and 0xFF); assertEquals(20, body.rgba[1].toInt() and 0xFF); assertEquals(30, body.rgba[2].toInt() and 0xFF)
        assertFalse(body.hasAlpha)
        val d1 = doc.find("dxt1")!!.image!!
        assertEquals(255, d1.rgba[0].toInt() and 0xFF); assertEquals(0, d1.rgba[1].toInt() and 0xFF)
        assertEquals("DXT1", d1.sourceFormat)
        val d5 = doc.find("glass")!!.image!!
        assertEquals(251, d5.rgba[1].toInt() and 0xFF) // verde 6 bits (62) expandido
        assertEquals(100, d5.rgba[3].toInt() and 0xFF)
        assertTrue(d5.hasAlpha)
    }

    @Test fun txdUnsupportedPlatformIsReported() {
        val t = RwTestWriter.TestTexture("ps2tex", 8, 8, 0, ByteArray(256), platform = 0x00325350)
        val doc = GtaSaTxdParser().parse(RwTestWriter.txd(listOf(t)))
        assertNotNull(doc.textures[0].error)
        assertNull(doc.textures[0].image)
    }

    @Test fun txdFindIgnoresCaseAndImageExtension() {
        val doc = GtaSaTxdParser().parse(RwTestWriter.txd(listOf(RwTestWriter.TestTexture("Body", 4, 4, 21, RwTestWriter.solidBgra(4, 4, 1, 2, 3)))))
        assertNotNull(doc.find("body"))
        assertNotNull(doc.find("body.png"))
        assertNull(doc.find("other"))
    }
}
