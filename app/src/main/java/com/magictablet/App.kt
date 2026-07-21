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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magictablet.game.GameStore
import com.magictablet.game.GameViewModel
import com.magictablet.game.GameViewModelFactory
import com.magictablet.screens.CardsScreen
import com.magictablet.screens.GameScreen
import com.magictablet.screens.StackScreen
import java.io.File

@Composable
fun App() {
    val context = LocalContext.current
    val gameViewModel: GameViewModel = viewModel(
        factory = remember { GameViewModelFactory(GameStore(File(context.filesDir, "game.json"))) },
    )
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
                    Screen.Game -> GameScreen(viewModel = gameViewModel)
                    Screen.Cards -> CardsScreen()
                    Screen.Stack -> StackScreen(gameViewModel = gameViewModel)
                }
            }
        }
    }
}
