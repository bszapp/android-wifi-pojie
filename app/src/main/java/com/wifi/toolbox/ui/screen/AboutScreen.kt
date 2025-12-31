package com.wifi.toolbox.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.R
import dev.jeziellago.compose.markdowntext.MarkdownText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MyApplication

    var showDialog by remember { mutableStateOf(false) }
    var changelogText by remember { mutableStateOf("") }

    LaunchedEffect(showDialog) {
        if (showDialog && changelogText.isEmpty()) {
            changelogText = try {
                context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        Image(
            painter = painterResource(id = R.drawable.about_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onMenuClick) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open navigation drawer"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val drawable = remember(R.mipmap.ic_launcher) {
                        context.packageManager.getDrawable(
                            context.packageName,
                            R.mipmap.ic_launcher,
                            context.applicationInfo
                        )
                    }
                    Image(
                        painter = rememberAsyncImagePainter(model = drawable),
                        contentDescription = "App Icon",
                        modifier = Modifier
                            .size(108.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Text(stringResource(R.string.app_name))
                    Button(
                        onClick = { showDialog = true }
                    ) {
                        Text("更新日志")
                    }
                }
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                confirmButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text("确定")
                    }
                },
                title = { Text("更新日志") },
                text = {
                    Box(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownText(
                            modifier = Modifier.padding(8.dp),
                            markdown = changelogText,
                        )
                    }
                }
            )
        }
    }
}