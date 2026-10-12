package com.webmaletin.gtaviewer.project

import com.webmaletin.gtaviewer.library.Hashing
import com.webmaletin.gtaviewer.library.LibraryStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable data class ProjectFile(val id: String, val state: ProjectState)

class ProjectInfo(val id: String, val name: String, val modified: Long, val parts: Int, val baseId: String?)

/** Guarda/abre proyectos editables (JSON) en la carpeta de la biblioteca; sobreviven al cierre de la app. */
class ProjectStore(private val store: LibraryStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir get() = store.projectsDir

    fun create(name: String): CarProject {
        val id = "p_" + Hashing.sha1("$name${System.nanoTime()}").take(10)
        return CarProject(store, id, ProjectState(name = name.ifBlank { "Proyecto" }, modified = System.currentTimeMillis()))
    }

    fun save(p: CarProject) {
        val f = File(dir, "${p.id}.json")
        val tmp = File(dir, "${p.id}.json.tmp")
        tmp.writeText(json.encodeToString(ProjectFile(p.id, p.state)))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun list(): List<ProjectInfo> = (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.mapNotNull { f ->
        runCatching { val pf = json.decodeFromString<ProjectFile>(f.readText()); ProjectInfo(pf.id, pf.state.name, pf.state.modified, pf.state.parts.count { !it.deleted }, pf.state.baseVehicleId) }.getOrNull()
    }.sortedByDescending { it.modified }

    /** Abre un proyecto y comprueba qué recursos faltan (blobs/texturas): devuelve el proyecto y la lista de faltantes. */
    fun open(id: String): Pair<CarProject, List<String>> {
        val pf = json.decodeFromString<ProjectFile>(File(dir, "$id.json").readText())
        val missing = ArrayList<String>()
        pf.state.parts.forEach { if (!store.hasBlob(it.blob)) missing += "geometría de '${it.name}' (${it.blob.take(8)}…)" }
        pf.state.textures.forEach { if (store.texture(it.hash) == null) missing += "textura '${it.name}' (${it.hash.take(8)}…)" }
        // los proyectos con recursos ausentes se abren sin esas piezas para no impedir la edición del resto
        val usable = pf.state.copy(parts = pf.state.parts.filter { store.hasBlob(it.blob) }, textures = pf.state.textures.filter { store.texture(it.hash) != null })
        return CarProject(store, pf.id, usable) to missing
    }

    fun delete(id: String) { File(dir, "$id.json").delete() }

    fun duplicate(id: String, newName: String): String {
        val pf = json.decodeFromString<ProjectFile>(File(dir, "$id.json").readText())
        val nid = "p_" + Hashing.sha1("$newName${System.nanoTime()}").take(10)
        File(dir, "$nid.json").writeText(json.encodeToString(ProjectFile(nid, pf.state.copy(name = newName, modified = System.currentTimeMillis()))))
        return nid
    }
}
