package com.webmaletin.gtaviewer.project

import com.webmaletin.gtaviewer.library.Anchor
import com.webmaletin.gtaviewer.export.ExportProfile
import kotlinx.serialization.Serializable

@Serializable data class Trs(val pos: List<Float> = listOf(0f, 0f, 0f), val rot: List<Float> = listOf(0f, 0f, 0f), val scale: List<Float> = listOf(1f, 1f, 1f)) {
    val isIdentity get() = pos.all { it == 0f } && rot.all { it == 0f } && scale.all { it == 1f }
}

/** Material propio del proyecto (PBR). [texMode]: ORIGINAL = conserva la textura del material de origen, NONE = sin textura, REF = textura del proyecto [texRef]. */
@Serializable data class MatDef(
    val id: String, val name: String, val r: Int, val g: Int, val b: Int, val a: Int = 255,
    val metallic: Float = 0f, val roughness: Float = 0.75f, val glass: Boolean = false,
    val texMode: String = "ORIGINAL", val texRef: String? = null, val paintSlot: Int = 0,
)

@Serializable data class LayerRef(val name: String, val hash: String, val visible: Boolean = true, val opacity: Float = 1f)

/** Textura editable del proyecto. [originalHash] es la textura importada (nunca se modifica); [hash] la versión editada guardada. */
@Serializable data class TexRef(val id: String, val name: String, val hash: String, val originalHash: String, val width: Int, val height: Int, val layers: List<LayerRef> = emptyList())

@Serializable data class PaintVariant(val name: String, val primary: Int, val textures: Map<String, String> = emptyMap(), val layers: Map<String, List<LayerRef>> = emptyMap())

@Serializable data class PPart(
    val id: Int,
    val name: String,
    val category: String,
    /** "base" | "library" | "edited" | "merged" | "copy". */
    val origin: String,
    val libraryId: String? = null,
    val blob: String,
    /** Geometría original antes de editarla (para restaurar). */
    val origBlob: String? = null,
    val baseTransform: List<Float>,
    val trs: Trs = Trs(),
    /** Centro de la pieza en espacio GTA (pivote de rotación/escala). */
    val pivot: List<Float> = listOf(0f, 0f, 0f),
    /** Visible en la vista 3D. */
    val visible: Boolean = true,
    /** Incluida en la exportación (independiente de [visible]). */
    val export: Boolean = true,
    val locked: Boolean = false,
    /** Eliminada del proyecto (recuperable con deshacer o "Restaurar"). */
    val deleted: Boolean = false,
    val isBase: Boolean = false,
    val wheelSlot: String? = null,
    /** índice de sub-mesh → id de MatDef (asignación explícita). */
    val subMaterials: Map<String, String> = emptyMap(),
    val note: String? = null,
    val mergedFrom: List<Int> = emptyList(),
)

@Serializable data class FusionConfig(val weldTolerance: Float = 0.0005f, val stitchTolerance: Float = 0.02f, val uvTolerance: Float = 0.001f)

@Serializable data class ProjectState(
    val name: String,
    val parts: List<PPart> = emptyList(),
    val materials: List<MatDef> = emptyList(),
    /** clave del material de origen (nombre|color#hashTextura) → id de MatDef que lo sustituye. */
    val overrides: Map<String, String> = emptyMap(),
    val textures: List<TexRef> = emptyList(),
    val nextPartId: Int = 1,
    val nextMatId: Int = 1,
    val nextTexId: Int = 1,
    val baseVehicleId: String? = null,
    val anchors: List<Anchor> = emptyList(),
    val variants: List<PaintVariant> = emptyList(),
    val paintPrimary: Int = 0xC9CDD2,
    val paintSecondary: Int = 0x2B2F36,
    val fusion: FusionConfig = FusionConfig(),
    val exportProfile: ExportProfile = ExportProfile.GODOT,
    val notes: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val modified: Long = 0,
    val formatVersion: Int = 1,
)

class ExportSummary(val included: List<String>, val omitted: List<Pair<String, String>>, val hiddenButIncluded: List<String>)
