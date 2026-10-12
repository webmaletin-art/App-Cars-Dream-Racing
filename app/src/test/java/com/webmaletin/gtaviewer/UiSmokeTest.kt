package com.webmaletin.gtaviewer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.webmaletin.gtaviewer.export.ExportFormat
import com.webmaletin.gtaviewer.library.LibraryImporter
import com.webmaletin.gtaviewer.ui.*
import com.webmaletin.gtaviewer.zip.MemorySource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pruebas de humo de la interfaz Compose sobre Robolectric (JVM, sin GPU): comprueban que cada pantalla compone sin fallar con datos
 * reales del motor y que los gestos/acciones llegan al núcleo. NO verifican OpenGL ni el aspecto en un dispositivo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiSmokeTest {
    @get:Rule val rule = createComposeRule()

    private val txd = RwTestWriter.txd(listOf("body", "glass", "tyre", "pzero", "wheel", "brakerotor").mapIndexed { i, n ->
        RwTestWriter.TestTexture(n, 16, 16, 21, RwTestWriter.solidBgra(16, 16, 40 + i * 30, 90, 200 - i * 20)) })

    private fun item(name: String, k: Float, wheelK: Float = k, source: String = "pack1") =
        LibraryImporter.Item(name, source, "$source/$name/$name.dff", MemorySource("$name.dff", RwTestWriter.scaledVehicleDff(k, 1, wheelK)), MemorySource("$name.txd", txd))

    private fun vms(): Pair<AppViewModel, LabViewModel> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return AppViewModel(app) to LabViewModel(app)
    }

    private fun populate(lab: LabViewModel) {
        LibraryImporter(lab.store).importAll(listOf(item("Alpha car", 1f), item("Beta car", 1.15f, 1f, "pack2"), item("Gamma car", 0.9f, 0.9f, "pack2")))
        lab.bump()
    }

    private fun openProject(lab: LabViewModel): String {
        val base = lab.store.vehicles().first { it.name == "Alpha car" }.id
        lab.newProject("Prueba", base) {}
        rule.waitUntil(15_000) { lab.ws != null }
        return base
    }

    @Test fun menuShowsTheSixEntries() {
        val (vm, lab) = vms()
        rule.setContent { GtaTheme { MenuScreen(vm, lab) } }
        for (t in listOf("VISOR Y CONVERSOR", "GENERADOR DE AUTOS", "CATÁLOGO", "EDITOR DE TEXTURAS", "PROYECTOS", "CONFIGURACIÓN")) rule.onNodeWithText(t).assertExists()
    }

    @Test fun libraryComposesWithData() {
        val (vm, lab) = vms(); populate(lab)
        rule.setContent { GtaTheme { LibraryScreen(vm, lab) } }
        rule.onNodeWithText("Alpha car").assertExists(); rule.onNodeWithText("3 vehículos", substring = true).assertExists()
        rule.onNodeWithText("IMPORTAR ZIP").assertExists()
    }

    @Test fun settingsComposes() {
        val (vm, lab) = vms(); populate(lab)
        rule.setContent { GtaTheme { SettingsScreen(vm, lab) } }
        rule.onNodeWithText("EXPORTAR COPIA DE SEGURIDAD").assertExists()
    }

    @Test fun projectsComposes() {
        val (vm, lab) = vms(); populate(lab)
        rule.setContent { GtaTheme { ProjectsScreen(vm, lab) } }
        rule.onNodeWithText("NUEVO PROYECTO").assertExists()
    }

    @Test fun generatorFormComposes() {
        val (vm, lab) = vms(); populate(lab)
        rule.setContent { GtaTheme { GeneratorScreen(vm, lab) } }
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("GENERAR 4 ALTERNATIVAS"))
        rule.onNodeWithText("GENERAR 4 ALTERNATIVAS").assertExists()
    }

    @Test fun generatorMakesRealCardsAndOpensAProject() {
        val (vm, lab) = vms(); populate(lab)
        lab.generate(2)
        rule.waitUntil(30_000) { lab.genCards.isNotEmpty() && !lab.genBusy }
        assertTrue(lab.genCards.isNotEmpty())
        val card = lab.genCards.first()
        assertNotNull(card.preview, "vista previa renderizada")
        rule.setContent { GtaTheme { GeneratorScreen(vm, lab) } }
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("ABRIR EN EL TALLER")); rule.onNodeWithText("ABRIR EN EL TALLER").assertExists()
        lab.openGenerated(card, false) {}
        rule.waitUntil(30_000) { lab.ws != null }
        assertTrue(lab.ws!!.state.parts.any { it.isBase })
        rule.waitUntil(15_000) { lab.report != null }
        assertTrue(lab.report!!.contains("Comprobación"))
    }

    @Test fun everyWorkshopPanelComposesAndOperationsReachTheCore() {
        val (vm, lab) = vms(); populate(lab)
        openProject(lab)
        var panel by androidx.compose.runtime.mutableStateOf(0)
        rule.setContent {
            GtaTheme {
                val s = lab.ws!!
                when (panel) {
                    0 -> PartsPanel(lab, s, null); 1 -> AddPanel(lab, s); 2 -> MovePanel(lab, s); 3 -> MaterialPanel(lab, s); 4 -> FusePanel(lab, s); 5 -> FacesPanel(lab, s); else -> ExportPanel(lab, s)
                }
            }
        }
        rule.onNodeWithText("Mostrar todo").assertExists()
        // ocultar ≠ excluir de la exportación
        val wheel = lab.ws!!.state.parts.first { it.category == "WHEEL" }
        lab.setVisible(listOf(wheel.id), false)
        rule.waitUntil(10_000) { lab.ws!!.state.parts.first { it.id == wheel.id }.visible.not() }
        assertTrue(lab.ws!!.state.parts.first { it.id == wheel.id }.export, "oculta pero se sigue exportando")
        lab.select(setOf(wheel.id))
        for (i in 1..6) { panel = i; rule.waitForIdle() }
        // AÑADIR: candidatos con compatibilidad
        panel = 1; lab.refreshCandidates(); rule.waitUntil(15_000) { lab.candidates.isNotEmpty() }
        val cand = lab.candidates.first { it.meta.category != "WHEEL" }
        lab.addPiece(cand, true)
        rule.waitUntil(15_000) { lab.ws!!.state.parts.any { it.libraryId == cand.meta.id } }
        // MOVER numérico
        val added = lab.ws!!.state.parts.first { it.libraryId == cand.meta.id }
        lab.select(setOf(added.id)); lab.nudge(listOf(added.id), floatArrayOf(0.25f, 0f, 0f))
        rule.waitUntil(10_000) { lab.partOf(added.id)!!.trs.pos[0] == 0.25f }
        // deshacer / rehacer
        lab.undo(); rule.waitUntil(10_000) { lab.partOf(added.id)!!.trs.pos[0] == 0f }
        lab.redo(); rule.waitUntil(10_000) { lab.partOf(added.id)!!.trs.pos[0] == 0.25f }
        // el proyecto se guardó solo y reabre igual
        val id = lab.projectList().first().id
        val before = lab.ws!!.state.parts.size
        lab.closeProject()
        lab.openProject(id) {}
        rule.waitUntil(15_000) { lab.ws != null }
        assertEquals(before, lab.ws!!.state.parts.size)
        assertTrue(lab.ws!!.state.parts.first { it.libraryId == cand.meta.id }.trs.pos[0] == 0.25f)
    }

    @Test fun workshopScreenComposesWithTheGlHostAndPanelTabs() {
        val (vm, lab) = vms(); populate(lab); openProject(lab)
        rule.setContent { GtaTheme { WorkshopScreen(vm, lab) } }
        rule.onNodeWithText("PIEZAS").assertExists()
        for (t in listOf("AÑADIR", "MOVER", "MATERIAL", "AJUSTAR Y FUSIONAR", "CARAS / LOGOS", "EXPORTAR")) { rule.onNodeWithText(t).performClick(); rule.waitForIdle() }
        rule.onNodeWithText("EXPORTAR").assertExists()
    }

    @Test fun exportsAVerifiedGodotGlb() {
        val (vm, lab) = vms(); populate(lab); openProject(lab)
        lab.exportProject(ExportFormat.GLB, LabViewModel.ExportOptions("FULL", com.webmaletin.gtaviewer.export.ExportProfile.GODOT), true)
        rule.waitUntil(30_000) { lab.exported != null }
        val e = lab.exported!!
        assertTrue(e.file.length() > 1000)
        assertNotNull(e.verification)
        assertTrue(!e.verification!!.contains("✕"), e.verification)
    }

    @Test fun textureEditorPaintsWithTouchAndSavesPixels() {
        val (vm, lab) = vms(); populate(lab); openProject(lab)
        rule.setContent { GtaTheme { TextureScreen(vm, lab) } }
        val slot = lab.texSlots().first { it.textureName == "body" }
        lab.openTexture(slot.partId, slot.subIndex)
        rule.waitUntil(15_000) { lab.texDoc != null && lab.texBitmap != null }
        val doc = lab.texDoc!!
        lab.texColor = 0xFF0000; lab.texSize = 6f; lab.texTool = TexTool.BRUSH
        rule.waitForIdle()
        val before = doc.flatten().copyOf()
        rule.onNodeWithTag("texCanvas").performTouchInput { swipe(Offset0(width, height).first, Offset0(width, height).second, 400) }
        rule.waitUntil(10_000) { doc.canUndo }
        assertTrue(!before.contentEquals(doc.flatten()), "el trazo cambió píxeles")
        lab.texCommit()
        rule.waitUntil(10_000) { lab.ws!!.state.textures.singleOrNull()?.let { it.hash != it.originalHash } == true }
        // la textura viva del proyecto (la que ve el visor y exporta el GLB) tiene los píxeles nuevos
        val ref = lab.ws!!.state.textures.single()
        assertTrue(ref.hash != ref.originalHash, "guardada como textura editada")
        lab.texClose()
        rule.waitForIdle()
    }

    private fun Offset0(w: Int, h: Int) = androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.4f) to androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.5f)
}
