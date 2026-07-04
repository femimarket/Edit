//
//  MainActivity.kt
//  Demo
//

package studio.femi.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import market.femi.imageedit.ImageEdit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ImageEdit follows the system light/dark theme (iOS systemBackground);
        // default edge-to-edge keeps both bar scrims transparent and lets the
        // system bar icon style adapt with the theme.
        enableEdgeToEdge()
        setContent {
            // The Swift #Preview / ImageEditApp render ContentView() with no
            // arguments — the entry takes only the optional onTrigger signal.
            ImageEdit()
        }
    }
}
