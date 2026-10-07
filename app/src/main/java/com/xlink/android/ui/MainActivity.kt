//
// 修复重点：
//   1. 注入 NodeViewModelFactory，彻底解决无参反射导致的启动闪退 Crash。
//   2. 规范 VPN prepare 授权流与 NavHost 单顶启动。

package com.xlink.android.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xlink.android.R
import com.xlink.android.data.store.NodeStore
import com.xlink.android.data.sub.SubRepository
import com.xlink.android.ui.screen.HomeScreen
import com.xlink.android.ui.screen.LogScreen
import com.xlink.android.ui.screen.NodeEditScreen
import com.xlink.android.ui.screen.SettingsScreen
import com.xlink.android.ui.theme.XlinkTheme
import com.xlink.android.viewmodel.NodeViewModel
import com.xlink.android.viewmodel.NodeViewModelFactory

object Routes {
    const val HOME = "home"
    const val NODE_EDIT = "node_edit"
    const val LOG = "log"
    const val SETTINGS = "settings"
}

private data class BottomNavItem(val route: String, val labelId: Int, val icon: ImageVector)

private val BOTTOM_NAV_ITEMS = listOf(
    BottomNavItem(Routes.HOME, R.string.nav_home, Icons.Filled.List),
    BottomNavItem(Routes.LOG, R.string.nav_log, Icons.Filled.Notes),
    BottomNavItem(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {

    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private var pendingVpnAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) pendingVpnAction?.invoke()
            pendingVpnAction = null
        }

        setContent {
            XlinkTheme {
                XlinkApp(onRequestVpnPermission = ::requestVpnPermission)
            }
        }
    }

    fun requestVpnPermission(onGranted: () -> Unit) {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            onGranted()
        } else {
            pendingVpnAction = onGranted
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XlinkApp(onRequestVpnPermission: (() -> Unit) -> Unit) {
    val context = LocalContext.current.applicationContext
    val nodeViewModel: NodeViewModel = viewModel(
        factory = NodeViewModelFactory(NodeStore(context), SubRepository())
    )

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination
    val showBottomBar = currentDest?.route != Routes.NODE_EDIT

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BOTTOM_NAV_ITEMS.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, null) },
                            label = { Text(stringResource(item.labelId)) },
                            selected = currentDest?.hierarchy?.any { it.route == item.route } == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(navController = navController, startDestination = Routes.HOME, modifier = Modifier.padding(paddingValues)) {
            composable(Routes.HOME) {
                HomeScreen(
                    nodeViewModel = nodeViewModel,
                    onRequestVpnPermission = onRequestVpnPermission,
                    onNavigateToEdit = { navController.navigate(Routes.NODE_EDIT) }
                )
            }
            composable(Routes.NODE_EDIT) {
                NodeEditScreen(nodeViewModel = nodeViewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.LOG) { LogScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(onRequestVpnPermission = onRequestVpnPermission) }
        }
    }
}
