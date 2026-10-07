package com.blushdesk.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.blushdesk.app.ui.showroom.ShowroomTabletScreen
import com.blushdesk.app.ui.showroom.ShowroomViewModel
import com.blushdesk.app.ui.theme.ShowroomPinkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ShowroomViewModel by viewModels {
        ShowroomViewModel.factory((application as BlushDeskApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The theme is light-only, so the system bars always use dark icons on a light scrim.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            ShowroomPinkTheme {
                ShowroomTabletScreen(viewModel)
            }
        }
    }
}
