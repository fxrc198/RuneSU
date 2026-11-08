package com.fufu.rune

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {

    private var granted by mutableStateOf(false)

    private val requestAll = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshGrant() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshGrant()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (granted) Browser() else PermissionGate { askAllFiles() }
                }
            }
        }
    }

    private fun refreshGrant() {
        granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager() else true
    }

    private fun askAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            i.data = Uri.parse("package:$packageName")
            requestAll.launch(i)
        }
    }

    @Composable
    private fun PermissionGate(onAsk: () -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("需要“所有文件访问”权限")
            Spacer(Modifier.height(8.dp))
            Text("RuneSU 用它来浏览 /sdcard", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAsk) { Text("去授权") }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Browser() {
        var path by remember {
            mutableStateOf(Environment.getExternalStorageDirectory().absolutePath)
        }
        val entries = remember(path) {
            File(path).listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        }

        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = {
                Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis)
            })
            LazyColumn(Modifier.fillMaxSize()) {
                val parent = File(path).parentFile
                if (parent != null) {
                    item {
                        ListItem(
                            headlineContent = { Text("..") },
                            modifier = Modifier.clickable { path = parent.absolutePath }
                        )
                        HorizontalDivider()
                    }
                }
                items(entries) { f ->
                    ListItem(
                        headlineContent = {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.clickable {
                            if (f.isDirectory) path = f.absolutePath
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
