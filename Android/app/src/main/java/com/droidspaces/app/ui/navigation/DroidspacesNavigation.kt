package com.droidspaces.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.droidspaces.app.util.PreferencesManager
import com.droidspaces.app.util.RootChecker
import com.droidspaces.app.util.RootStatus
import com.droidspaces.app.util.Constants
import com.droidspaces.app.util.AnimationUtils
import com.droidspaces.app.ui.screen.InstallationScreen
import com.droidspaces.app.ui.screen.MainTabScreen
import com.droidspaces.app.ui.screen.TabItem
import com.droidspaces.app.ui.screen.RootCheckScreen
import com.droidspaces.app.ui.screen.SettingsScreen
import com.droidspaces.app.ui.screen.RequirementsScreen
import com.droidspaces.app.ui.screen.AutoBootPriorityScreen
import com.droidspaces.app.ui.screen.TerminalAppearanceScreen
import com.droidspaces.app.ui.screen.WelcomeScreen
import com.droidspaces.app.ui.screen.ContainerNameScreen
import com.droidspaces.app.ui.screen.RecommendedConfigScreen
import com.droidspaces.app.ui.screen.SparseImageConfigScreen
import com.droidspaces.app.ui.screen.ContainerConfigScreen
import com.droidspaces.app.ui.screen.InstallationSummaryScreen
import com.droidspaces.app.ui.screen.InstallationProgressScreen
import com.droidspaces.app.ui.screen.EditContainerScreen
import com.droidspaces.app.ui.screen.ContainerDetailsScreen
import com.droidspaces.app.ui.screen.SystemdScreen
import com.droidspaces.app.ui.screen.UnitDetailScreen
import com.droidspaces.app.ui.screen.OverrideEditorScreen
import com.droidspaces.app.ui.screen.ProcdScreen
import com.droidspaces.app.ui.screen.OpenRCScreen
import com.droidspaces.app.ui.screen.ContainerTerminalScreen
import com.droidspaces.app.ui.screen.SnapshotsScreen
import com.droidspaces.app.ui.screen.DiskUsageScreen
import com.droidspaces.app.ui.screen.AnlandScreen
import com.droidspaces.app.ui.screen.AnlandGuideScreen
import com.droidspaces.app.ui.screen.AdrenoTurnipScreen
import com.droidspaces.app.ui.screen.WirelessScreen
import com.droidspaces.app.ui.screen.ScrcpyScreen
import com.droidspaces.app.ui.screen.FileSharingScreen
import com.droidspaces.app.ui.screen.SshServerScreen
import com.droidspaces.app.ui.screen.DockerScreen
import com.droidspaces.app.ui.screen.DockerContainerLogsScreen
import com.droidspaces.app.ui.screen.TailscaleScreen
import com.droidspaces.app.ui.screen.CaddyScreen
import com.droidspaces.app.ui.screen.NetworkDiscoveryScreen
import com.droidspaces.app.ui.screen.ContainerHtopScreen
import com.droidspaces.app.ui.screen.PackagesScreen
import com.droidspaces.app.ui.screen.ProgramsScreen
import com.droidspaces.app.ui.screen.AliasesScreen
import com.droidspaces.app.ui.viewmodel.ContainerInstallationViewModel
import com.droidspaces.app.ui.viewmodel.ContainerViewModel
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.FilePickerUtils
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.droidspaces.app.ui.util.LoadingIndicator
import com.droidspaces.app.ui.util.LoadingSize
import com.droidspaces.app.ui.util.FullScreenLoading
import com.droidspaces.app.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.ui.viewmodel.AppStateViewModel
import androidx.activity.ComponentActivity
import android.net.Uri
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.droidspaces.app.ui.screen.JournaldScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sealed class representing all navigation destinations.
 * Simplified to remove skipRefresh parameter - state is now managed by ViewModel.
 */
sealed class Screen(val route: String) {
    data object Welcome : Screen("welcome")
    data object RootCheck : Screen("rootcheck")
    data object Installation : Screen("installation")
    data object Home : Screen("home?fromInstallation={fromInstallation}") {
        fun createRoute(fromInstallation: Boolean = false) = "home?fromInstallation=$fromInstallation"
    }
    data object Settings : Screen("settings")
    data object Requirements : Screen("requirements")
    data object AutoBootPriority : Screen("auto_boot_priority")
    data object TerminalAppearance : Screen("terminal_appearance")

    // Container installation wizard screens
    data object ContainerName : Screen("container_name/{tarballUri}") {
        fun createRoute(tarballUri: String) = "container_name/${Uri.encode(tarballUri)}"
    }
    data object SparseImageConfig : Screen("sparse_image_config")
    data object RecommendedConfig : Screen("recommended_config")
    data object ContainerConfig : Screen("container_config")
    data object InstallationSummary : Screen("installation_summary")
    data object InstallationProgress : Screen("installation_progress")

    // Container editing screen
    data object EditContainer : Screen("edit_container/{containerName}") {
        fun createRoute(containerName: String) = "edit_container/${Uri.encode(containerName)}"
    }

    // Container management screens
    data object ContainerDetails : Screen("container_details/{containerName}") {
        fun createRoute(containerName: String) = "container_details/${Uri.encode(containerName)}"
    }
    data object Systemd : Screen("systemd/{containerName}") {
        fun createRoute(containerName: String) = "systemd/${Uri.encode(containerName)}"
    }
    data object JournaldDump : Screen("journald-dump/{containerName}/{unitName}") {
        fun createRoute(containerName: String, unitName: String) =
            "journald-dump/${Uri.encode(containerName)}/${Uri.encode(unitName)}"
    }
    data object UnitDetail : Screen("unit-detail/{containerName}/{unitName}") {
        fun createRoute(containerName: String, unitName: String) =
            "unit-detail/${Uri.encode(containerName)}/${Uri.encode(unitName)}"
    }
    data object OverrideEditor : Screen("unit-override/{containerName}/{unitName}") {
        fun createRoute(containerName: String, unitName: String) =
            "unit-override/${Uri.encode(containerName)}/${Uri.encode(unitName)}"
    }
    data object Procd : Screen("procd/{containerName}") {
        fun createRoute(containerName: String) = "procd/${Uri.encode(containerName)}"
    }
    data object OpenRC : Screen("openrc/{containerName}") {
        fun createRoute(containerName: String) = "openrc/${Uri.encode(containerName)}"
    }

    data object Terminal : Screen("terminal/{containerName}?initialCommand={initialCommand}") {
        fun createRoute(containerName: String, initialCommand: String? = null) =
            if (initialCommand != null) "terminal/${Uri.encode(containerName)}?initialCommand=${Uri.encode(initialCommand)}"
            else "terminal/${Uri.encode(containerName)}"
    }
    data object DiskUsage : Screen("disk_usage/{containerName}") {
        fun createRoute(containerName: String) = "disk_usage/${Uri.encode(containerName)}"
    }
    data object SnapshotsManage : Screen("snapshots_manage/{containerName}") {
        fun createRoute(containerName: String) = "snapshots_manage/${Uri.encode(containerName)}"
    }
    data object AnlandGuide : Screen("anland_guide")
    data object WirelessManage : Screen("wireless_manage/{containerName}") {
        fun createRoute(containerName: String) = "wireless_manage/${Uri.encode(containerName)}"
    }
    data object ScrcpyManage : Screen("scrcpy_manage/{containerName}") {
        fun createRoute(containerName: String) = "scrcpy_manage/${Uri.encode(containerName)}"
    }
    data object AnlandManage : Screen("anland_manage/{containerName}") {
        fun createRoute(containerName: String) = "anland_manage/${Uri.encode(containerName)}"
    }
    data object TurnipManage : Screen("turnip_manage/{containerName}") {
        fun createRoute(containerName: String) = "turnip_manage/${Uri.encode(containerName)}"
    }
    data object NetworkDiscovery : Screen("network_discovery")
    data object FileSharing : Screen("file_sharing/{containerName}") {
        fun createRoute(containerName: String) = "file_sharing/${Uri.encode(containerName)}"
    }
    data object SshServerManage : Screen("ssh_server_manage/{containerName}") {
        fun createRoute(containerName: String) = "ssh_server_manage/${Uri.encode(containerName)}"
    }
    data object DockerManage : Screen("docker_manage/{containerName}") {
        fun createRoute(containerName: String) = "docker_manage/${Uri.encode(containerName)}"
    }
    data object DockerContainerLogs : Screen("docker_container_logs/{containerName}/{dockerContainerId}/{dockerContainerName}") {
        fun createRoute(containerName: String, dockerContainerId: String, dockerContainerName: String) =
            "docker_container_logs/${Uri.encode(containerName)}/${Uri.encode(dockerContainerId)}/${Uri.encode(dockerContainerName)}"
    }
    data object TailscaleManage : Screen("tailscale_manage/{containerName}") {
        fun createRoute(containerName: String) = "tailscale_manage/${Uri.encode(containerName)}"
    }
    data object CaddyManage : Screen("caddy_manage/{containerName}") {
        fun createRoute(containerName: String) = "caddy_manage/${Uri.encode(containerName)}"
    }
    data object Htop : Screen("htop/{containerName}?sort={sort}") {
        fun createRoute(containerName: String, sort: String = "cpu") =
            "htop/${Uri.encode(containerName)}?sort=${Uri.encode(sort)}"
    }
    data object PackagesManage : Screen("packages_manage/{containerName}") {
        fun createRoute(containerName: String) = "packages_manage/${Uri.encode(containerName)}"
    }
    data object ProgramsManage : Screen("programs_manage/{containerName}") {
        fun createRoute(containerName: String) = "programs_manage/${Uri.encode(containerName)}"
    }
    data object AliasesManage : Screen("aliases_manage/{containerName}") {
        fun createRoute(containerName: String) = "aliases_manage/${Uri.encode(containerName)}"
    }
}

/**
 * Resolve the install-wizard [ContainerInstallationViewModel] scoped to the first
 * wizard screen's back-stack entry (so all wizard steps share one instance),
 * falling back to the current entry if that route isn't on the stack.
 */
@Composable
private fun wizardScopedViewModel(
    navController: NavHostController,
    backStackEntry: NavBackStackEntry
): ContainerInstallationViewModel = viewModel(
    remember(backStackEntry) {
        runCatching { navController.getBackStackEntry(Screen.ContainerName.route) }
            .getOrElse { backStackEntry }
    }
)

@Composable
fun DroidspacesNavigation(
    navController: NavHostController = rememberNavController(),
    onContentReady: () -> Unit = {},
    pendingShortcut: String? = null,
    onShortcutHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager.getInstance(context) }

    // Tab requested by a launcher shortcut (Containers / Panel). Fed into
    // MainTabScreen, which scrolls the pager to it and clears the request.
    var requestedTab by remember { mutableStateOf<TabItem?>(null) }

    // Shared ViewModels scoped to Activity to ensure state consistency across screens
    val activity = context as? ComponentActivity
    val sharedContainerViewModel: ContainerViewModel = if (activity != null) {
        viewModel(viewModelStoreOwner = activity)
    } else {
        viewModel()
    }

    val sharedAppStateViewModel: AppStateViewModel = if (activity != null) {
        viewModel(viewModelStoreOwner = activity)
    } else {
        viewModel()
    }

    // Determine initial destination immediately (synchronous read)
    val initialDestination = remember(prefsManager) {
        if (prefsManager.isSetupCompleted) Screen.Home.route else Screen.Welcome.route
    }

    var rootStatus by remember { mutableStateOf<RootStatus?>(null) }

    // Signal content ready and check root in background if needed
    LaunchedEffect(Unit) {
        onContentReady()
        if (prefsManager.isSetupCompleted && prefsManager.rootChecked) {
            launch(Dispatchers.IO) {
                rootStatus = RootChecker.checkRootAccess()
            }
        }
    }

    // Route a launcher long-press shortcut to its destination. Containers/Panel
    // select a tab on the (reused) Home screen; Settings is pushed on top of it.
    // Ignored until onboarding is complete so we never deep-link past setup.
    LaunchedEffect(pendingShortcut) {
        val shortcut = pendingShortcut ?: return@LaunchedEffect
        if (prefsManager.isSetupCompleted) {
            when {
                // Tap on a "download complete" notification, carries the tarball's URI
                shortcut.startsWith("install:") -> navController.navigate(
                    Screen.ContainerName.createRoute(shortcut.removePrefix("install:"))
                ) {
                    popUpTo(Screen.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
                shortcut == "settings" -> navController.navigate(Screen.Settings.route) {
                    popUpTo(Screen.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
                shortcut == "containers" || shortcut == "panel" -> {
                    requestedTab =
                        if (shortcut == "containers") TabItem.Containers else TabItem.ControlPanel
                    navController.navigate(Screen.Home.createRoute()) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
        }
        onShortcutHandled()
    }

    // Define animation specs - consistent 200ms for snappy feel
    val defaultEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(animationSpec = AnimationUtils.fastSpec())
    }
    val defaultExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = AnimationUtils.fastSpec())
    }

    val setupEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(animationSpec = AnimationUtils.mediumSpec())
    }
    val setupExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = AnimationUtils.mediumSpec())
    }

    // Home grows in over a finished installer, backend or container, so landing there
    // reads as arriving somewhere rather than the installer being torn away.
    val installers = setOf(Screen.Installation.route, Screen.InstallationProgress.route)
    val homeEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (initialState.destination.route in installers) {
            scaleIn(initialScale = 0.9f, animationSpec = AnimationUtils.mediumSpec()) +
                fadeIn(animationSpec = AnimationUtils.mediumSpec())
        } else {
            fadeIn(animationSpec = AnimationUtils.fastSpec())
        }
    }

    NavHost(
        navController = navController,
        startDestination = initialDestination
    ) {
        composable(
            route = Screen.Welcome.route,
            enterTransition = setupEnterTransition,
            exitTransition = setupExitTransition
        ) {
            WelcomeScreen(
                onNavigateToRootCheck = {
                    navController.navigate(Screen.RootCheck.route)
                }
            )
        }

        composable(
            route = Screen.RootCheck.route,
            enterTransition = setupEnterTransition,
            exitTransition = setupExitTransition
        ) {
            RootCheckScreen(
                rootStatus = rootStatus,
                onRootCheck = { status ->
                    rootStatus = status
                    prefsManager.rootChecked = true
                },
                onNavigateToInstallation = {
                    navController.navigate(Screen.Installation.route)
                },
                onSkip = {
                    prefsManager.rootSkipped = true
                    prefsManager.isSetupCompleted = true
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Installation.route,
            enterTransition = setupEnterTransition,
            // Home scales in over this screen, so the result hero has to be gone before
            // the new screen has settled or the two cross-paint; the fast spec does that.
            exitTransition = { fadeOut(animationSpec = AnimationUtils.fastSpec()) }
        ) {
            InstallationScreen(
                appStateViewModel = sharedAppStateViewModel,
                onInstallationComplete = {
                    prefsManager.isSetupCompleted = true

                    // Navigate to home with fromInstallation=true to trigger refresh
                    val isFromSetup = navController.previousBackStackEntry?.destination?.route == Screen.RootCheck.route
                    navController.navigate(Screen.Home.createRoute(fromInstallation = true)) {
                        if (isFromSetup) {
                            popUpTo(Screen.Welcome.route) { inclusive = true }
                        } else {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(
            route = Screen.Home.route,
            arguments = listOf(
                navArgument("fromInstallation") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            ),
            enterTransition = homeEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = homeEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val fromInstallation = backStackEntry.arguments?.getBoolean("fromInstallation") ?: false

            MainTabScreen(
                appStateViewModel = sharedAppStateViewModel,
                containerViewModel = sharedContainerViewModel,
                // skipInitialRefresh=false when fromInstallation=true (invert the logic)
                skipInitialRefresh = !fromInstallation,
                requestedTab = requestedTab,
                onRequestedTabConsumed = { requestedTab = null },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToInstallation = {
                    navController.navigate(Screen.Installation.route)
                },
                onNavigateToContainerInstallation = { uri ->
                    navController.navigate(Screen.ContainerName.createRoute(uri.toString()))
                },
                onNavigateToEditContainer = { containerName ->
                    navController.navigate(Screen.EditContainer.createRoute(containerName))
                },
                onNavigateToContainerDetails = { containerName ->
                    navController.navigate(Screen.ContainerDetails.createRoute(containerName))
                },
                onNavigateToTerminal = { containerName ->
                    navController.navigate(Screen.Terminal.createRoute(containerName))
                },
                onNavigateToSnapshots = { containerName ->
                    navController.navigate(Screen.SnapshotsManage.createRoute(containerName))
                }
            )
        }

        // Container installation wizard screens
        composable(
            route = Screen.ContainerName.route,
            arguments = listOf(
                navArgument("tarballUri") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val viewModel: ContainerInstallationViewModel = viewModel(backStackEntry)
            val tarballUriString = backStackEntry.arguments?.getString("tarballUri") ?: ""
            val tarballUri = Uri.parse(tarballUriString)

            LaunchedEffect(tarballUri) {
                viewModel.setTarball(context, tarballUri)
            }

            // ContainerNameScreen only reads its initial values once, so hold it until
            // the archive has been checked for settings to prefill.
            if (viewModel.readingRecommendation) {
                FullScreenLoading(context.getString(R.string.rootfs_config_loading))
            } else {
                ContainerNameScreen(
                    initialName = viewModel.containerName,
                    initialHostname = viewModel.hostname,
                    recommendationNotice = viewModel.recommendationNotice,
                    existingContainerNames = sharedContainerViewModel.containerList.map { it.name },
                    onNext = { name, hostname ->
                        viewModel.setName(name, hostname)
                        navController.navigate(
                            if (viewModel.recommendationLoaded) Screen.RecommendedConfig.route
                            else Screen.ContainerConfig.route
                        )
                    },
                    onClose = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = Screen.RecommendedConfig.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) {
            RecommendedConfigScreen(
                onNext = { navController.navigate(Screen.ContainerConfig.route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ContainerConfig.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val viewModel = wizardScopedViewModel(navController, backStackEntry)

            ContainerConfigScreen(
                initialState = viewModel.configState,
                containerName = viewModel.containerName,
                installedContainers = sharedContainerViewModel.containerList,
                onNext = { state ->
                    viewModel.setConfig(state)
                    navController.navigate(Screen.SparseImageConfig.route)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.SparseImageConfig.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val viewModel = wizardScopedViewModel(navController, backStackEntry)

            SparseImageConfigScreen(
                initialUseSparseImage = viewModel.useSparseImage,
                initialSizeGB = viewModel.sparseImageSizeGB,
                initialStorageDir = viewModel.storageDir,
                onNext = { useSparseImage, sizeGB, storageDir ->
                    viewModel.setSparseImageConfig(useSparseImage, sizeGB, storageDir)
                    navController.navigate(Screen.InstallationSummary.route)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.InstallationSummary.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val viewModel = wizardScopedViewModel(navController, backStackEntry)
            val config = viewModel.buildConfig()
            val tarballUri = viewModel.tarballUri
            val ctx = LocalContext.current

            if (config != null && tarballUri != null) {
                // Extract filename from URI using FilePickerUtils (handles recent files properly)
                var tarballName by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(tarballUri) {
                    tarballName = FilePickerUtils.getFileName(ctx, tarballUri) ?: "container.tar.gz"
                }

                // Show loading while extracting filename
                if (tarballName == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(size = com.droidspaces.app.ui.util.LoadingSize.Large)
                    }
                } else {
                    InstallationSummaryScreen(
                        config = config,
                        tarballName = tarballName!!,
                        onInstall = {
                            navController.navigate(Screen.InstallationProgress.route)
                        },
                        onBack = {
                            navController.popBackStack()
                        }
                    )
                }
            } else {
                // Error state - navigate back
                LaunchedEffect(Unit) {
                    navController.popBackStack(Screen.Home.route, false)
                }
            }
        }

        composable(
            route = Screen.InstallationProgress.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val viewModel = wizardScopedViewModel(navController, backStackEntry)
            val config = viewModel.buildConfig()
            val tarballUri = viewModel.tarballUri

            if (config != null && tarballUri != null) {
                InstallationProgressScreen(
                    tarballUri = tarballUri,
                    config = config,
                    onSuccess = {
                        viewModel.reset()
                        // Trigger container list refresh before navigating back
                        sharedContainerViewModel.fetchContainerList()
                        navController.popBackStack(Screen.Home.route, false)
                    },
                    onError = {
                        navController.popBackStack(Screen.Home.route, false)
                    }
                )
            } else {
                // Error state - navigate back
                LaunchedEffect(Unit) {
                    navController.popBackStack(Screen.Home.route, false)
                }
            }
        }

        composable(
            route = Screen.EditContainer.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            // Use shared ViewModel to ensure updates are reflected in other screens
            val containerViewModel: ContainerViewModel = sharedContainerViewModel

            var containerInfo by remember { mutableStateOf<com.droidspaces.app.util.ContainerInfo?>(null) }
            var isLoading by remember { mutableStateOf(true) }

            LaunchedEffect(containerName) {
                isLoading = true
                containerInfo = withContext(Dispatchers.IO) {
                    ContainerManager.getContainerInfo(containerName)
                }
                isLoading = false
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(size = LoadingSize.Medium)
                }
            } else {
                containerInfo?.let { container ->
                    EditContainerScreen(
                        container = container,
                        containerViewModel = containerViewModel,
                        onBack = {
                            navController.popBackStack()
                        }
                    )
                } ?: LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }

        composable(
            route = Screen.Settings.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) {
            SettingsScreen(
                appStateViewModel = sharedAppStateViewModel,
                onBack = {
                    // Simply pop back - no refresh needed (ViewModel preserves state)
                    navController.popBackStack()
                },
                onNavigateToInstallation = {
                    navController.navigate(Screen.Installation.route)
                },
                onNavigateToRequirements = {
                    navController.navigate(Screen.Requirements.route)
                },
                onNavigateToAutoBootPriority = {
                    navController.navigate(Screen.AutoBootPriority.route)
                },
                onNavigateToTerminalAppearance = {
                    navController.navigate(Screen.TerminalAppearance.route)
                },
                onNavigateToNetworkDiscovery = {
                    navController.navigate(Screen.NetworkDiscovery.route)
                },
                onNavigateToAnlandGuide = {
                    navController.navigate(Screen.AnlandGuide.route)
                }
            )
        }

        composable(
            route = Screen.TerminalAppearance.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) {
            TerminalAppearanceScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.AutoBootPriority.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) {
            AutoBootPriorityScreen(
                containerViewModel = sharedContainerViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.Requirements.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) {
            RequirementsScreen(
                appStateViewModel = sharedAppStateViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.ContainerDetails.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            // Use shared ViewModel to ensure we see updated details
            val containerViewModel: ContainerViewModel = sharedContainerViewModel
            val container = containerViewModel.containerList.find { it.name == containerName }

            container?.let {
                ContainerDetailsScreen(
                    container = it,
                    onNavigateBack = {
                        navController.popBackStack()
                        sharedContainerViewModel.refresh()
                    },
                    onNavigateToServices = { initSystem ->
                        when (initSystem) {
                            com.droidspaces.app.ui.screen.InitSystem.SYSTEMD ->
                                navController.navigate(Screen.Systemd.createRoute(containerName))
                            com.droidspaces.app.ui.screen.InitSystem.PROCD ->
                                navController.navigate(Screen.Procd.createRoute(containerName))
                            com.droidspaces.app.ui.screen.InitSystem.OPENRC ->
                                navController.navigate(Screen.OpenRC.createRoute(containerName))
                        }
                    },
                    onNavigateToTerminal = {
                        navController.navigate(Screen.Terminal.createRoute(containerName))
                    },
                    onNavigateToDiskUsage = {
                        navController.navigate(Screen.DiskUsage.createRoute(containerName))
                    },
                    onNavigateToSnapshots = {
                        navController.navigate(Screen.SnapshotsManage.createRoute(containerName))
                    },
                    onNavigateToWireless = {
                        navController.navigate(Screen.WirelessManage.createRoute(containerName))
                    },
                    onNavigateToScrcpy = {
                        navController.navigate(Screen.ScrcpyManage.createRoute(containerName))
                    },
                    onNavigateToAnland = {
                        navController.navigate(Screen.AnlandManage.createRoute(containerName))
                    },
                    onNavigateToTurnip = {
                        navController.navigate(Screen.TurnipManage.createRoute(containerName))
                    },
                    onNavigateToAnlandGuide = {
                        navController.navigate(Screen.AnlandGuide.route)
                    },
                    onNavigateToFileSharing = {
                        navController.navigate(Screen.FileSharing.createRoute(containerName))
                    },
                    onNavigateToNetworkDiscovery = {
                        navController.navigate(Screen.NetworkDiscovery.route)
                    },
                    onNavigateToSshServer = {
                        navController.navigate(Screen.SshServerManage.createRoute(containerName))
                    },
                    onNavigateToDocker = {
                        navController.navigate(Screen.DockerManage.createRoute(containerName))
                    },
                    onNavigateToTailscale = {
                        navController.navigate(Screen.TailscaleManage.createRoute(containerName))
                    },
                    onNavigateToCaddy = {
                        navController.navigate(Screen.CaddyManage.createRoute(containerName))
                    },
                    onNavigateToHtop = { sort ->
                        navController.navigate(Screen.Htop.createRoute(containerName, sort))
                    },
                    onNavigateToPackages = {
                        navController.navigate(Screen.PackagesManage.createRoute(containerName))
                    },
                    onNavigateToPrograms = {
                        navController.navigate(Screen.ProgramsManage.createRoute(containerName))
                    },
                    onNavigateToAliases = {
                        navController.navigate(Screen.AliasesManage.createRoute(containerName))
                    },
                    onNavigateToTerminalWithCommand = { cmd: String ->
                        navController.navigate(Screen.Terminal.createRoute(containerName, cmd))
                    }
                )
            } ?: LaunchedEffect(Unit) {
                navController.popBackStack()
            }
        }

        composable(
            route = Screen.Systemd.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            SystemdScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() },
                onInspectUnit = { unitName -> navController.navigate(Screen.UnitDetail.createRoute(containerName, unitName)) },
                onEditOverride = { unitName -> navController.navigate(Screen.OverrideEditor.createRoute(containerName, unitName)) },
                onViewLogs = { unitName -> navController.navigate(Screen.JournaldDump.createRoute(containerName, unitName)) }
            )
        }

        composable(
            route = Screen.JournaldDump.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType },
                navArgument("unitName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            val unitName = backStackEntry.arguments?.getString("unitName") ?: ""
            JournaldScreen(
                containerName = containerName,
                unitName = unitName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.UnitDetail.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType },
                navArgument("unitName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            val unitName = backStackEntry.arguments?.getString("unitName") ?: ""
            UnitDetailScreen(
                containerName = containerName,
                unitName = unitName,
                onNavigateBack = { navController.popBackStack() },
                onEditOverride = { navController.navigate(Screen.OverrideEditor.createRoute(containerName, unitName)) },
                onViewLogs = { navController.navigate(Screen.JournaldDump.createRoute(containerName, unitName)) }
            )
        }

        composable(
            route = Screen.OverrideEditor.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType },
                navArgument("unitName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            val unitName = backStackEntry.arguments?.getString("unitName") ?: ""
            OverrideEditorScreen(
                containerName = containerName,
                unitName = unitName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Procd.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            ProcdScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.OpenRC.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            OpenRCScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Terminal.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType },
                navArgument("initialCommand") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            val initialCommand = backStackEntry.arguments?.getString("initialCommand")
            ContainerTerminalScreen(
                containerName = containerName,
                initialCommand = initialCommand,
                onNavigateBack = {
                    navController.popBackStack()
                    sharedContainerViewModel.refresh()
                }
            )
        }

        composable(
            route = Screen.SnapshotsManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            SnapshotsScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.DiskUsage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            DiskUsageScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AnlandGuide.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) {
            AnlandGuideScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.WirelessManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            WirelessScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ScrcpyManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            ScrcpyScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTerminalWithCommand = { cmd ->
                    navController.navigate(Screen.Terminal.createRoute(containerName, cmd))
                }
            )
        }

        composable(
            route = Screen.AnlandManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            AnlandScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToGuide = { navController.navigate(Screen.AnlandGuide.route) },
                onStartDesktopTerminal = { cmd ->
                    navController.navigate(Screen.Terminal.createRoute(containerName, cmd))
                }
            )
        }

        composable(
            route = Screen.TurnipManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            AdrenoTurnipScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.NetworkDiscovery.route,
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) {
            NetworkDiscoveryScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.FileSharing.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            FileSharingScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.SshServerManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            SshServerScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.DockerManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            DockerScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() },
                onViewDockerLogs = { dockerId, dockerName ->
                    navController.navigate(Screen.DockerContainerLogs.createRoute(containerName, dockerId, dockerName))
                }
            )
        }

        composable(
            route = Screen.DockerContainerLogs.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType },
                navArgument("dockerContainerId") { type = NavType.StringType },
                navArgument("dockerContainerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            val dockerContainerId = backStackEntry.arguments?.getString("dockerContainerId") ?: ""
            val dockerContainerName = backStackEntry.arguments?.getString("dockerContainerName") ?: ""
            DockerContainerLogsScreen(
                containerName = containerName,
                dockerContainerId = dockerContainerId,
                dockerContainerName = dockerContainerName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.TailscaleManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            TailscaleScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTerminalWithCommand = { name, cmd ->
                    navController.navigate(Screen.Terminal.createRoute(name, cmd))
                }
            )
        }

        composable(
            route = Screen.CaddyManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            CaddyScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTerminalWithCommand = { name, cmd ->
                    navController.navigate(Screen.Terminal.createRoute(name, cmd))
                }
            )
        }

        composable(
            route = Screen.Htop.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType },
                navArgument("sort") {
                    type = NavType.StringType
                    defaultValue = "cpu"
                }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            val sort = backStackEntry.arguments?.getString("sort") ?: "cpu"
            ContainerHtopScreen(
                containerName = containerName,
                initialSort = sort,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.PackagesManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            PackagesScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTerminalWithCommand = { name, cmd ->
                    navController.navigate(Screen.Terminal.createRoute(name, cmd))
                }
            )
        }

        composable(
            route = Screen.ProgramsManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            ProgramsScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTerminalWithCommand = { name, cmd ->
                    navController.navigate(Screen.Terminal.createRoute(name, cmd))
                }
            )
        }

        composable(
            route = Screen.AliasesManage.route,
            arguments = listOf(
                navArgument("containerName") { type = NavType.StringType }
            ),
            enterTransition = defaultEnterTransition,
            exitTransition = defaultExitTransition,
            popEnterTransition = defaultEnterTransition,
            popExitTransition = defaultExitTransition
        ) { backStackEntry ->
            val containerName = backStackEntry.arguments?.getString("containerName") ?: ""
            AliasesScreen(
                containerName = containerName,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTerminalWithCommand = { name, cmd ->
                    navController.navigate(Screen.Terminal.createRoute(name, cmd))
                }
            )
        }
    }
}
