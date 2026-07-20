package com.magictablet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.magictablet.screens.CardsScreen
import com.magictablet.screens.GameScreen
import com.magictablet.screens.StackScreen

@Composable
fun App() {
    var screen by remember { mutableStateOf(Screen.Game) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TabRow(selectedTabIndex = screen.ordinal) {
                Screen.entries.forEach { entry ->
                    Tab(
                        selected = entry == screen,
                        onClick = { screen = entry },
                        text = { Text(entry.label) },
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                when (screen) {
                    Screen.Game -> GameScreen()
                    Screen.Cards -> CardsScreen()
                    Screen.Stack -> StackScreen()
                }
            }
        }
    }
}
