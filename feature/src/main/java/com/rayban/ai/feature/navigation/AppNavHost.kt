package com.rayban.ai.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rayban.ai.feature.home.HomeScreen

private object AppDestination {
    const val HOME = "home"
    const val CAMERA = "camera"
}

@Composable
fun AppNavHost(
    cameraContent: @Composable (onBack: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.HOME,
        modifier = modifier,
    ) {
        composable(AppDestination.HOME) {
            HomeScreen(
                onOpenCamera = { navController.navigate(AppDestination.CAMERA) },
            )
        }
        composable(AppDestination.CAMERA) {
            cameraContent { navController.popBackStack() }
        }
    }
}
