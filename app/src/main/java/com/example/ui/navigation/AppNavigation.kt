package com.example.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.ui.screens.ai.AiScreen
import com.example.ui.screens.analyzer.AnalyzerScreen
import com.example.ui.screens.automation.AutomationScreen
import com.example.ui.screens.discover.DiscoverScreen
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.offers.OffersScreen
import com.example.ui.screens.property.PropertyDetailScreen
import com.example.ui.screens.saved.SavedScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.splash.SplashScreen
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate900

sealed class BottomNavItem(val route: String, val title: String, val icon: ImageVector) {
    object Home : BottomNavItem(Screen.Home.route, "Home", Icons.Filled.Dashboard)
    object Discover : BottomNavItem(Screen.Discover.route, "Discover", Icons.Filled.Search)
    object Offers : BottomNavItem(Screen.Offers.route, "Offers", Icons.Filled.Description)
    object Automation : BottomNavItem(Screen.Automation.route, "Auto", Icons.Filled.SmartToy)
    object Saved : BottomNavItem(Screen.Saved.route, "Saved", Icons.Filled.Bookmark)
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomBarItems = listOf(
        BottomNavItem.Home,
        BottomNavItem.Discover,
        BottomNavItem.Offers,
        BottomNavItem.Automation,
        BottomNavItem.Saved
    )

    val showBottomBar = currentRoute in bottomBarItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = Slate900,
                    tonalElevation = 8.dp
                ) {
                    bottomBarItems.forEach { item ->
                        val isSelected = currentRoute == item.route
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.title) },
                            label = { Text(item.title) },
                            selected = isSelected,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CyanPrimary,
                                selectedTextColor = CyanPrimary,
                                unselectedIconColor = Slate400,
                                unselectedTextColor = Slate400,
                                indicatorColor = Slate900
                            ),
                            onClick = {
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Splash.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Splash
            composable(Screen.Splash.route) {
                SplashScreen(
                    onSplashFinished = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    }
                )
            }

            // Home
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToDiscover = { navController.navigate(Screen.Discover.route) },
                    onNavigateToPropertyDetail = { propId -> navController.navigate(Screen.PropertyDetail.createRoute(propId)) },
                    onNavigateToAnalyzer = { propId -> navController.navigate(Screen.Analyzer.createRoute(propId)) },
                    onNavigateToAi = { propId -> navController.navigate(Screen.AiIntelligence.createRoute(propId)) },
                    onNavigateToOffers = { navController.navigate(Screen.Offers.route) },
                    onNavigateToAutomation = { navController.navigate(Screen.Automation.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                )
            }

            // Discover
            composable(Screen.Discover.route) {
                DiscoverScreen(
                    onNavigateToDetail = { propId -> navController.navigate(Screen.PropertyDetail.createRoute(propId)) },
                    onNavigateToAnalyzer = { propId -> navController.navigate(Screen.Analyzer.createRoute(propId)) }
                )
            }

            // Property Details
            composable(
                route = Screen.PropertyDetail.route,
                arguments = listOf(navArgument("propertyId") { type = NavType.StringType })
            ) { backStackEntry ->
                val propId = backStackEntry.arguments?.getString("propertyId") ?: ""
                PropertyDetailScreen(
                    propertyId = propId,
                    onBack = { navController.popBackStack() },
                    onNavigateToAnalyzer = { id -> navController.navigate(Screen.Analyzer.createRoute(id)) },
                    onNavigateToAi = { id -> navController.navigate(Screen.AiIntelligence.createRoute(id)) },
                    onNavigateToOffers = { navController.navigate(Screen.Offers.route) }
                )
            }

            // Financial Analyzer
            composable(
                route = Screen.Analyzer.route,
                arguments = listOf(navArgument("propertyId") { type = NavType.StringType })
            ) { backStackEntry ->
                val propId = backStackEntry.arguments?.getString("propertyId") ?: ""
                AnalyzerScreen(
                    propertyId = propId,
                    onBack = { navController.popBackStack() }
                )
            }

            // AI Deal Intelligence
            composable(
                route = Screen.AiIntelligence.route,
                arguments = listOf(navArgument("propertyId") { type = NavType.StringType })
            ) { backStackEntry ->
                val propId = backStackEntry.arguments?.getString("propertyId") ?: "general"
                AiScreen(
                    propertyId = propId,
                    onBack = { navController.popBackStack() }
                )
            }

            // Offers
            composable(Screen.Offers.route) {
                OffersScreen(
                    onNavigateToDetail = { propId -> navController.navigate(Screen.PropertyDetail.createRoute(propId)) }
                )
            }

            // Automation
            composable(Screen.Automation.route) {
                AutomationScreen(
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                )
            }

            // Saved
            composable(Screen.Saved.route) {
                SavedScreen(
                    onNavigateToDetail = { propId -> navController.navigate(Screen.PropertyDetail.createRoute(propId)) },
                    onNavigateToAnalyzer = { propId -> navController.navigate(Screen.Analyzer.createRoute(propId)) }
                )
            }

            // Settings
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
