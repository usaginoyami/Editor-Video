package com.usagi.editorvideo

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val vm: VideoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val project by vm.project.collectAsState()
                val message by vm.message.collectAsState()
                val ctx = LocalContext.current
                LaunchedEffect(message) {
                    message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.clearMessage() }
                }
                if (project.clips.isEmpty()) HomeScreen(vm) else EditorScreen(vm)
                ExportDialog(vm)
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFE63946),
            onPrimary = Color.White,
            background = Color(0xFF101014),
            surface = Color(0xFF1A1A20),
            onSurface = Color(0xFFEAEAEA),
            surfaceVariant = Color(0xFF26262E),
            onSurfaceVariant = Color(0xFFB8B8C0)
        ),
        content = content
    )
}
