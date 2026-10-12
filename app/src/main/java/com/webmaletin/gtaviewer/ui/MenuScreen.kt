package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.LabViewModel
import com.webmaletin.gtaviewer.Screen

@Composable
private fun MenuCard(title: String, desc: String, stat: String?, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Accent, letterSpacing = 0.8.sp)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            if (stat != null) Text(stat, fontSize = 12.sp, color = Good, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun MenuScreen(app: AppViewModel, lab: LabViewModel) {
    val (vehicles, parts) = lab.counts()
    val nProjects = lab.projectList().size
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GTA CAR LAB", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Accent, letterSpacing = 1.sp)
        Text("GTA San Andreas · visor, conversor y taller de autos", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("MÓDULO A · VISOR Y CONVERSOR")
        MenuCard("VISOR Y CONVERSOR", "Importa ZIP, empareja DFF/TXD, 3D, Outliner, GLB / OBJ / FBX, exportación masiva.",
            if (app.items.isNotEmpty()) "${app.items.size} vehículos en la sesión actual" else null) { app.navigate(Screen.HOME) }
        SectionTitle("MÓDULO B · TALLER Y GENERADOR")
        MenuCard("GENERADOR DE AUTOS", "Combina carrocerías y piezas del catálogo según tipo, estilo y nivel de modificación; revisa solapes sobre las mallas.",
            if (vehicles == 0) "Necesita vehículos en el catálogo" else "$vehicles carrocerías · $parts piezas disponibles") { app.navigate(Screen.GENERATOR) }
        MenuCard("CATÁLOGO", "Biblioteca permanente de vehículos y piezas: búsqueda, filtros, favoritos, categorías propias, duplicados, copia de seguridad.",
            "$vehicles vehículos · $parts piezas guardadas") { app.navigate(Screen.LIBRARY) }
        MenuCard("EDITOR DE TEXTURAS", "Pinta, quita logos, añade imágenes y texto, capas, UV. Los cambios se guardan en los píxeles de la textura del proyecto.",
            if (lab.hasProject) "Proyecto abierto: ${lab.ws?.state?.name ?: ""}" else "Abra un proyecto para editar sus texturas") {
            if (lab.hasProject) app.navigate(Screen.TEXTURES) else { lab.message = "Abra primero un proyecto"; app.navigate(Screen.PROJECTS) }
        }
        MenuCard("PROYECTOS", "Taller de ensamblaje: base fija, piezas con posición/rotación/escala, materiales, fusión y exportación GLB para Godot.",
            if (nProjects > 0) "$nProjects proyectos guardados" else "Sin proyectos todavía") { app.navigate(Screen.PROJECTS) }
        SectionTitle("SISTEMA")
        MenuCard("CONFIGURACIÓN", "Almacenamiento, limpieza, copia de seguridad, perfil de exportación por defecto, límites conocidos.", null) { app.navigate(Screen.SETTINGS) }
        Spacer(Modifier.height(8.dp))
    }
}
