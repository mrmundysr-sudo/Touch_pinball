// license:GPLv3+
package org.vpinball.app.ui.screens.landing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koin.androidx.compose.koinViewModel
import org.vpinball.app.download.ArchiveTable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableDownloaderScreen(onClose: () -> Unit, viewModel: TableDownloaderViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<ArchiveTable?>(null) }
    val filtered = remember(state.entries, search) { state.entries.filter { it.title.contains(search.trim(), true) } }
    LaunchedEffect(Unit) { viewModel.loadIndex() }
    // Closing the catalog is allowed while downloading: the activity-scoped model keeps the job alive.
    Dialog(onDismissRequest = { if (!state.busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        BackHandler { if (state.busy) viewModel.cancel() else onClose() }
        Scaffold(topBar = { TopAppBar(title = { Text("Download tables") }, navigationIcon = {
            TextButton(onClick = onClose, enabled = !state.busy) { Text("Library") }
        }, actions = { TextButton(onClick = { viewModel.loadIndex(true) }, enabled = !state.busy && !state.loading) { Text("Refresh") } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Text("Visual Pinball Collection • Archive.org", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search tables") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Text(state.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                if (state.loading || state.busy) {
                    val progress = state.progress
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.busy) TextButton(onClick = viewModel::cancel, enabled = !state.importing) { Text(if (state.importing) "Finishing installation…" else "Cancel download") }
                if (!state.loading && filtered.isEmpty()) Text("No matching tables. Try another search or Refresh.", modifier = Modifier.padding(16.dp))
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filtered, key = { it.table.path }) { entry ->
                        val installed = entry.table.path in state.installed
                        Card(onClick = { selected = entry }, enabled = !state.busy && !installed,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                Text(if (installed) "Installed — available in Library" else "Download • ${entry.bytes / 1048576} MB" +
                                    if (entry.companions.any { it.name.endsWith(".directb2s", true) }) " • backglass included" else "",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        selected?.let { entry ->
            AlertDialog(onDismissRequest = { selected = null }, title = { Text(entry.title) }, text = {
                Text("Download the table and its available companion files? The app will check for a required ROM and install matching files. ROM downloads may add to the ${entry.bytes / 1048576} MB shown. Some tables need manual mobile compatibility fixes. Download only files you have permission to use.")
            }, confirmButton = { TextButton(onClick = { selected = null; viewModel.install(entry) }) { Text("Download & install") } },
                dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } })
        }
    }
}
