package org.yangtse.hearwrite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Home: start options land in Phase 4; 词库 opens the built-in library browser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartDictation: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("首页") }) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(onClick = onStartDictation, modifier = Modifier.fillMaxWidth()) {
                Text("开始听写")
            }
            OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
                Text("词库")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("设置")
            }
        }
    }
}
