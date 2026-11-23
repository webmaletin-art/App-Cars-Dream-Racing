package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel
import com.webmaletin.gtaviewer.Screen

@Composable
private fun GameCard(title: String, desc: String, stat: String?, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Accent, letterSpacing = 1.sp)
            Text(desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (stat != null) Text(stat, fontSize = 12.sp, color = Good, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Pantalla de inicio: una entrada por juego. Arriba GTA, abajo BeamNG. */
@Composable
fun MenuScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("VISOR DE AUTOS", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Accent, letterSpacing = 1.sp)
        Text("Mira los autos antes de elegir cuáles bajar y expórtalos a GLB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        GameCard("GTA", "GTA San Andreas: importa ZIP con DFF + TXD, 3D, Outliner de piezas, GLB / OBJ / FBX (individual y masivo), daños incluidos.",
            if (vm.items.isNotEmpty()) "${vm.items.size} vehículos en la sesión" else null) { vm.navigate(Screen.HOME) }
        GameCard("BEAMNG CARS", "Mods de BeamNG.drive (.zip con .dae + .jbeam): elige la configuración, mira el coche con sus texturas y expórtalo a GLB con piezas separadas y su física.",
            if (vm.beamVehicles.isNotEmpty()) "${vm.beamVehicles.size} coche(s) cargados" else null) { vm.navigate(Screen.BEAM) }
    }
}
