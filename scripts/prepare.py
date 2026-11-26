#!/usr/bin/env python3
"""Materialize a reproducible VPX source tree and apply Touch Pinball's reviewed changes."""
import pathlib, shutil, subprocess, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
SHA = 'd16258d98ce90155d679b7d5ad9de2261e313adc'
dest = pathlib.Path(sys.argv[1] if len(sys.argv)>1 else ROOT / 'engine').resolve()
if not (dest / '.git').exists():
    subprocess.run(['git', 'clone', '--no-checkout', '--filter=blob:none', 'https://github.com/vpinball/vpinball.git', str(dest)], check=True)
subprocess.run(['git','-C',str(dest),'checkout','--detach',SHA],check=True)
assert subprocess.check_output(['git','-C',str(dest),'rev-parse','HEAD'],text=True).strip() == SHA

def edit(path, old, new):
    p = dest / path
    s = p.read_text()
    if new in s: return
    assert s.count(old) == 1, f'Patch drift: {path}: {old[:70]}'
    p.write_text(s.replace(old,new))

app='apps/android/app/'
java=app+'src/main/java/org/vpinball/app/'
edit(app+'build.gradle.kts','applicationId = "org.vpinball.vpinball_bgfx"','applicationId = "com.mundymorning.touchpinball"')
edit(app+'build.gradle.kts','    implementation(libs.androidx.core.ktx)','    testImplementation("junit:junit:4.13.2")\n    implementation(libs.androidx.core.ktx)')
edit(app+'src/main/res/values/strings.xml','<string name="app_name">Visual Pinball</string>','<string name="app_name">Touch Pinball</string>')
edit(java+'di/Modules.kt','    viewModelOf(::LandingScreenViewModel)','    viewModelOf(::LandingScreenViewModel)\n    viewModel { org.vpinball.app.ui.screens.landing.TableDownloaderViewModel(get()) }')
for name in ['ImportTableDropdownMenu.kt','LandingBottomBar.kt']:
    path=java+'ui/screens/landing/'+name
    edit(path,'    onFiles: () -> Unit,','    onArchive: () -> Unit,\n    onFiles: () -> Unit,')
edit(java+'ui/screens/landing/ImportTableDropdownMenu.kt','        AppMenuHeader("Import from...")','        AppMenuItem(label = "Download from Archive.org", iconRes = R.drawable.img_sf_doc, onClick = onArchive)\n        AppMenuDivider()\n        AppMenuHeader("Import from...")')
edit(java+'ui/screens/landing/LandingBottomBar.kt','                    onFiles = {','                    onArchive = {\n                        showImportMenu = false\n                        onArchive()\n                    },\n                    onFiles = {')
edit(java+'ui/screens/landing/LandingScreen.kt','    var showSettingsDialog by remember { mutableStateOf(false) }','    var showSettingsDialog by remember { mutableStateOf(false) }\n    var showArchive by remember { mutableStateOf(false) }')
edit(java+'ui/screens/landing/LandingScreen.kt','            onFiles = { launcher.launch(arrayOf("*/*")) },','            onArchive = { showArchive = true },\n            onFiles = { launcher.launch(arrayOf("*/*")) },')
edit(java+'ui/screens/landing/LandingScreen.kt','        if (showConfirmDialog) {','        if (showArchive) {\n            TableDownloaderScreen(onClose = { showArchive = false })\n        }\n\n        if (showConfirmDialog) {')
edit(java+'ui/screens/landing/LandingBottomBar.kt','''        Image(
            painter = painterResource(id = R.drawable.img_vpinball_logo),
            contentDescription = null,
            modifier = Modifier.align(Alignment.Center).height(36.dp),
        )''','''        Text("Touch Pinball", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Center))''')
method='''    /** Publish to the library only after the table and every companion file has been copied. */
    suspend fun importDownloadedTable(vpxFile: File, extras: Map<String, File>): Table? = withContext(Dispatchers.IO) {
        require(extras.keys.all { org.vpinball.app.download.ArchiveCatalog.safePath(it) })
        val table = importTableFile(vpxFile.absolutePath) ?: return@withContext null
        val tableDir = File(table.path).parent ?: return@withContext null
        try {
            for ((relative, source) in extras) {
                val dest = buildPath("$tableDir/$relative")
                File(relative).parent?.let { fileOps.createDirectory(buildPath("$tableDir/$it")) }
                check(fileOps.copy(source.absolutePath, dest)) { "Failed to install $relative" }
            }
            _tables.value = _tables.value + table
            saveTables()
            withContext(Dispatchers.Main) { LandingScreenViewModel.triggerAddTables(listOf(table)) }
            table
        } catch (e: Exception) {
            _tables.value = _tables.value.filterNot { it.uuid == table.uuid }
            saveTables()
            fileOps.deleteDirectory(buildPath(tableDir))
            throw e
        }
    }

'''
edit(java+'TableManager.kt','    suspend fun deleteTable(table: Table,',method+'    suspend fun deleteTable(table: Table,')
shutil.copytree(ROOT/'touch/overlay',dest,dirs_exist_ok=True)
print(f'Touch Pinball prepared at {dest} (VPX {SHA})')
