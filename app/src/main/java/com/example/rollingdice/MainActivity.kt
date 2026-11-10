package com.example.rollingdice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.rollingdice.ui.RollingDiceScreen
import com.example.rollingdice.ui.theme.RollingDiceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RollingDiceTheme {
                RollingDiceScreen()
            }
        }
    }
}
