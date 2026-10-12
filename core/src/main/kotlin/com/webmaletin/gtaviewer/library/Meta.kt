package com.webmaletin.gtaviewer.library

import kotlinx.serialization.Serializable

@Serializable data class Dims(val w: Float, val h: Float, val l: Float) {
    val volume: Float get() = w * h * l
    fun maxSide() = maxOf(w, h, l)
}

@Serializable data class TypeLabel(val label: String, val confidence: Float, val source: String = "reglas")

@Serializable data class Anchor(val name: String, val pos: List<Float>, val dir: List<Float> = listOf(0f, 1f, 0f), val manual: Boolean = false)

/** Metadatos de una pieza del catálogo (la geometría vive en blobs deduplicados; esto es lo único que se carga al abrir la app). */
@Serializable data class PieceMeta(
    val id: String,
    val name: String,
    /** "VEHICLE" o "COMPONENT". */
    val kind: String,
    /** Nombre de PartCategory o de una categoría personalizada. */
    val category: String,
    val parentId: String? = null,
    val source: String,
    val origin: String,
    val dffHash: String,
    val txdHash: String? = null,
    val dims: Dims,
    /** Centro de la caja en el espacio de visor del vehículo de origen (Y arriba, frente +Z). */
    val center: List<Float>,
    val triangles: Int,
    val vertices: Int,
    val materials: List<String> = emptyList(),
    val textures: List<String> = emptyList(),
    val favorite: Boolean = false,
    val createdAt: Long = 0,
    /** Hash de la geometría (para detectar duplicados exactos al reimportar). */
    val geoHash: String = "",
    /** Hashes de blobs de geometría que usa (los componentes: 1; los vehículos: el manifiesto los lista). */
    val blobs: List<String> = emptyList(),
    val types: List<TypeLabel> = emptyList(),
    val typesCorrected: Boolean = false,
    val wheelCount: Int = 0,
    val anchors: List<Anchor> = emptyList(),
    /** "L", "R" o null; "FRONT", "REAR" o null (deducidos del nombre y la posición). */
    val side: String? = null,
    val longitudinal: String? = null,
    val tags: List<String> = emptyList(),
    val notes: String = "",
    /** Pieza equivalente ya existente (misma categoría, dimensiones y triángulos casi idénticos). */
    val similarTo: String? = null,
    /** Otras procedencias donde apareció exactamente la misma geometría (sin duplicarla). */
    val alsoFoundIn: List<String> = emptyList(),
    /** Transformación (en espacio GTA del vehículo de origen) de la pieza, 16 floats column-major. */
    val transform: List<Float> = emptyList(),
    val styleTags: List<String> = emptyList(),
    /** Vector de características (vehículos) para aprender correcciones del usuario. */
    val featureVec: List<Float> = emptyList(),
    val nameKey: String = "",
)

/** Parte guardada en el manifiesto de un vehículo. */
@Serializable data class StoredPart(
    val name: String,
    val category: String,
    val wheelSlot: String? = null,
    val frameName: String? = null,
    val transform: List<Float>,
    val blob: String,
    val defaultVisible: Boolean,
    val variantNote: String? = null,
    val recognized: Boolean = true,
    val extraSource: String? = null,
    /** Caja en espacio de visor: minX,minY,minZ,maxX,maxY,maxZ (vacía en bibliotecas antiguas). */
    val bounds: List<Float> = emptyList(),
)

/** Ajuste confirmado por el usuario (se reutiliza para sugerir colocaciones parecidas). */
@Serializable data class LearnedFit(
    val baseId: String, val pieceId: String, val category: String,
    val scale: Float, val deltaView: List<Float>, val rotDeg: List<Float> = listOf(0f, 0f, 0f), val count: Int = 1,
)

@Serializable data class LearnedFits(val items: List<LearnedFit> = emptyList())

@Serializable data class VehicleManifest(
    val id: String,
    val name: String,
    val parts: List<StoredPart>,
    val warnings: List<String> = emptyList(),
    val dffFileName: String = "",
    val txdFileName: String? = null,
)

@Serializable data class CustomCategories(val names: List<String> = emptyList())

@Serializable data class Correction(val features: List<Float>, val labels: List<String>, val name: String)
@Serializable data class Corrections(val items: List<Correction> = emptyList())

@Serializable data class LibraryIndex(val version: Int = 1, val pieces: List<PieceMeta> = emptyList())
