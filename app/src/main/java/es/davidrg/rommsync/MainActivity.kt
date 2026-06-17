package es.davidrg.rommsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import es.davidrg.rommsync.ui.navigation.RomMSyncApp
import es.davidrg.rommsync.ui.theme.RomMSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RomMSyncTheme {
                RomMSyncApp()
            }
        }
    }
}
