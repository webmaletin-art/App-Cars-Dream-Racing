package com.webmaletin.gtaviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.webmaletin.gtaviewer.ui.*

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GtaTheme { AppRoot(vm) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importZips(uris) }
    val dffLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.setManualDffFromUri(u) }
    val txdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.setManualTxdFromUri(u) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u -> if (u != null) vm.saveExportTo(u) else vm.pendingSave = null }
    val saveBin = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> if (u != null) vm.saveExportTo(u) else vm.pendingSave = null }

    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    // Tras exportar se ofrece guardar con el selector de archivos del sistema
    LaunchedEffect(vm.pendingSave) {
        vm.pendingSave?.let { if (it.suggestedName.endsWith(".zip")) saveZip.launch(it.suggestedName) else saveBin.launch(it.suggestedName) }
    }

    BackHandler(enabled = vm.screen != Screen.HOME) { vm.back() }

    val copy: (String) -> Unit = { text ->
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("diagnóstico", text))
        vm.message = "Copiado al portapapeles"
    }
    val share: (ExportResultUi) -> Unit = { res ->
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", res.file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = res.output.mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, "Compartir ${res.suggestedName}"))
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = Bg, contentWindowInsets = WindowInsets(0)) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (vm.screen) {
                Screen.HOME -> HomeScreen(vm, onImport = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) })
                Screen.MANUAL -> ManualScreen(vm,
                    onImport = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) },
                    onPickDff = { dffLauncher.launch(arrayOf("*/*")) }, onPickTxd = { txdLauncher.launch(arrayOf("*/*")) })
                Screen.VIEWER -> ViewerScreen(vm)
                Screen.DIAGNOSTICS -> DiagnosticsScreen(vm, copy)
            }
            vm.busyText?.let { text ->
                Surface(color = Color(0xCC000000), modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(14.dp))
                        Text(text)
                    }
                }
            }
            vm.exportResult?.let { res -> ExportResultDialog(res, onSave = { vm.pendingSave = res }, onShare = { share(res) }, onDismiss = { vm.exportResult = null }) }
        }
    }
}
