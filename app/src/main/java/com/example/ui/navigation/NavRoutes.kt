package com.example.ui.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Home : Screen("home")
    object Discover : Screen("discover")
    object PropertyDetail : Screen("property_detail/{propertyId}") {
        fun createRoute(propertyId: String) = "property_detail/$propertyId"
    }
    object Analyzer : Screen("analyzer/{propertyId}") {
        fun createRoute(propertyId: String) = "analyzer/$propertyId"
    }
    object AiIntelligence : Screen("ai_intelligence/{propertyId}") {
        fun createRoute(propertyId: String = "general") = "ai_intelligence/$propertyId"
    }
    object Offers : Screen("offers")
    object Automation : Screen("automation")
    object Saved : Screen("saved")
    object Settings : Screen("settings")
}
