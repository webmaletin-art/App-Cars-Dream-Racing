package com.webmaletin.gtaviewer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webmaletin.gtaviewer.AppViewModel

@Composable
fun DiagnosticsScreen(vm: AppViewModel, onCopy: (String) -> Unit) {
    val text = vm.diagnosticsText()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.back() }) { Text("← Atrás") }
            Text("DIAGNÓSTICO", fontWeight = FontWeight.ExtraBold, color = Accent, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Button(onClick = { onCopy(text) }) { Text("COPIAR LOG") }
        }
        val r = vm.report
        if (r != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("ZIP: ${r.vehicles.size} vehículos detectados", fontWeight = FontWeight.Bold)
                    KeyValue("DFF", "${r.dffCount}"); KeyValue("TXD", "${r.txdCount}")
                    KeyValue("Pares automáticos", "${r.automaticPairs}"); KeyValue("DFF sin TXD", "${r.dffWithoutTxd}")
                    KeyValue("TXD sin DFF", "${r.orphanTxds.size}"); KeyValue("Vehículos con advertencias", "${r.vehiclesWithWarnings}")
                    KeyValue("Errores", "${r.errors}")
                }
            }
        }
        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
