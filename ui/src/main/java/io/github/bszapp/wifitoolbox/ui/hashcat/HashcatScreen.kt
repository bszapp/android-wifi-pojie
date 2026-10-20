package io.github.bszapp.wifitoolbox.ui.hashcat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.SnackbarHostState

@Composable
fun HashcatScreen(snackbarHostState: SnackbarHostState, viewModel: HashcatViewModel = viewModel(), initialHandshake: String? = null) {
    val tasks by viewModel.history.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val dictionaries by viewModel.dictionaries.collectAsStateWithLifecycle()
    val kernels by viewModel.kernels.collectAsStateWithLifecycle()
    LaunchedEffect(initialHandshake) { initialHandshake?.let(viewModel::openIncoming) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::readFile)
    }
    HashcatScreenContent(
        tasks = tasks,
        connected = connected,
        sheet = sheet,
        dictionaries = dictionaries,
        kernels = kernels,
        snackbarHostState = snackbarHostState,
        actions = object : HashcatScreenActions {
            override fun open() = viewModel.open()
            override fun dismiss() = viewModel.dismiss()
            override fun input(value: String) = viewModel.input(value)
            override fun next() = viewModel.next()
            override fun previous() = viewModel.previous()
            override fun select(id: String) = viewModel.select(id)
            override fun run() = viewModel.run()
            override fun compileKernels() = viewModel.compileKernels()
            override fun inspect(id: String) = viewModel.inspect(id)
            override fun reselectDictionaries(id: String) = viewModel.reselectDictionaries(id)
            override fun deleteTask(id: String) = viewModel.deleteTask(id)
            override fun toggleTask(id: String) = viewModel.toggleTask(id)
            override fun pickFile() = pickFile.launch(arrayOf("*/*"))
            override fun speedTest() = viewModel.speedTest()
        },
    )
}
