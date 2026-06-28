package jv.stellariumcaller.stellariumcaller.ui.navigation

sealed class Screen(val route: String) {
    data object Calls : Screen("calls")
    data object Settings : Screen("settings")
    data object CallDetail : Screen("call_detail/{callId}") {
        fun createRoute(callId: Long) = "call_detail/$callId"
    }
}
