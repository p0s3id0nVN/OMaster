package com.silas.omaster

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.silas.omaster.model.MasterPreset
import com.silas.omaster.ui.components.PillNavBar
import com.silas.omaster.ui.components.WelcomeDialog
import com.silas.omaster.ui.create.PresetSelectionScreen
import com.silas.omaster.ui.create.UniversalCreatePresetScreen
import com.silas.omaster.ui.create.UniversalCreatePresetViewModel
import com.silas.omaster.ui.create.UniversalCreatePresetViewModelFactory
import com.silas.omaster.ui.detail.AboutScreen
import com.silas.omaster.ui.detail.DetailScreen
import com.silas.omaster.ui.detail.OpenSourceLicenseScreen
import com.silas.omaster.ui.detail.PrivacyPolicyScreen
import com.silas.omaster.ui.home.HomeScreen
import com.silas.omaster.ui.service.FloatingWindowController
import com.silas.omaster.ui.theme.OMasterTheme
import com.silas.omaster.util.JsonUtil
import com.silas.omaster.util.LanguageUtil
import com.silas.omaster.util.VersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silas.omaster.data.repository.PresetRepository

import androidx.compose.runtime.collectAsState
import com.silas.omaster.data.config.ConfigCenter
import com.silas.omaster.ui.settings.SettingsScreen


val LocalActivity = compositionLocalOf<Activity> { error("No Activity provided") }

sealed class Screen {
    @Serializable
    data object Home : Screen()

    @Serializable
    data class Detail(val presetId: String) : Screen()

    @Serializable
    data object PresetSelection : Screen()

    @Serializable
    data class CreatePreset(val templateId: String? = null) : Screen()

    @Serializable
    data class EditPreset(val presetId: String) : Screen()

    @Serializable
    data object Settings : Screen()

    @Serializable
    data object About : Screen()

    @Serializable
    data object Subscription : Screen()

    @Serializable
    data object PrivacyPolicy : Screen()

    @Serializable
    data object OpenSourceLicense : Screen()
}

class MainActivity : ComponentActivity() {

    private lateinit var floatingWindowController: FloatingWindowController

    override fun attachBaseContext(newBase: Context?) {
        // Áp dụng cài đặt ngôn ngữ trước khi Activity được tạo
        val context = newBase?.let { LanguageUtil.applyLanguage(it) }
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Khởi tạo và đăng ký bộ điều khiển cửa sổ nổi toàn cục
        floatingWindowController = FloatingWindowController.getInstance(this)
        floatingWindowController.register()

        setContent {
            CompositionLocalProvider(LocalActivity provides this) {
                val config = remember { ConfigCenter.getInstance(applicationContext) }
                val currentTheme by config.themeFlow.collectAsState()

                OMasterTheme(brandTheme = currentTheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = rememberNavController()
                        var showWelcomeFlow by remember { mutableStateOf(!OMasterApplication.getInstance().hasUserAgreed()) }

                        if (showWelcomeFlow) {
                            WelcomeFlow(
                                navController = navController,
                                onAgree = {
                                    OMasterApplication.getInstance().setUserAgreed(true)
                                    OMasterApplication.getInstance().initUMeng()
                                    showWelcomeFlow = false
                                },
                                onDisagree = {
                                    finish()
                                }
                            )
                        } else {
                            MainApp(
                                navController = navController,
                                config = config
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Hủy đăng ký bộ điều khiển cửa sổ nổi
        floatingWindowController.unregister()
    }
}

@Composable
fun WelcomeFlow(
    navController: NavHostController,
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showOpenSourceLicense by remember { mutableStateOf(false) }

    // Xử lý phím quay lại của hệ thống
    androidx.activity.compose.BackHandler(enabled = showPrivacyPolicy || showOpenSourceLicense) {
        showPrivacyPolicy = false
        showOpenSourceLicense = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            showPrivacyPolicy -> {
                PrivacyPolicyScreen(
                    onBack = {
                        showPrivacyPolicy = false
                    }
                )
            }
            showOpenSourceLicense -> {
                OpenSourceLicenseScreen(
                    onBack = {
                        showOpenSourceLicense = false
                    }
                )
            }
            else -> {
                WelcomeDialog(
                    onAgree = onAgree,
                    onDisagree = onDisagree,
                    onViewPrivacyPolicy = {
                        showPrivacyPolicy = true
                    }
                )
            }
        }
    }
}

@Composable
fun MainApp(
    navController: NavHostController,
    config: ConfigCenter
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { PresetRepository.getInstance(context) }
    var showMigrationDialog by remember { mutableStateOf(false) }

    // Kiểm tra xem có cần di chuyển dữ liệu không
    LaunchedEffect(Unit) {
        // Đầu tiên kích hoạt loadPresets để phát hiện chính xác phiên bản
        // Sử dụng luồng IO để tránh chặn UI
        withContext(Dispatchers.IO) {
            JsonUtil.loadPresets(context)
        }
        
        // Bây giờ currentPresetsVersion đã được đặt chính xác
        // Hỗ trợ version 2 và 3, chỉ phiên bản cũ (version 1) mới cần di chuyển
        if (JsonUtil.currentPresetsVersion == 1) {
            showMigrationDialog = true
        }
    }

    if (showMigrationDialog) {
        AlertDialog(
            onDismissRequest = { /* Force user to decide */ },
            title = { Text("Cập nhật cấu trúc dữ liệu") },
            text = { Text("Phát hiện phiên bản dữ liệu preset quá cũ, cần di chuyển dữ liệu để hỗ trợ các chức năng mới.\n\nNhấp vào \"Di chuyển dữ liệu\" sẽ đặt lại các preset tích hợp (các preset tùy chỉnh và mục yêu thích của bạn sẽ không bị mất).") },
            confirmButton = {
                TextButton(
                    onClick = {
                        JsonUtil.deleteRemotePresets(context)
                        repository.reloadDefaultPresets()
                        showMigrationDialog = false
                    }
                ) {
                    Text("Di chuyển dữ liệu")
                }
            },
            dismissButton = {
                // Optional: Allow user to cancel and exit app?
                // Or maybe just hide dialog and let them use potentially broken app?
                // Given the request "check if version field exists and value is 2, otherwise pop up prompt",
                // usually implies mandatory action.
                // But for safety/UX, maybe allow cancel?
                // If cancel, showMigrationDialog = false, but app might crash later if structure mismatch.
                // Let's stick to mandatory for now or just allow dismiss.
                // I'll leave dismissButton empty to force "Migrate" or back button (which onDismissRequest handles if we implemented logic).
                // Actually, onDismissRequest handles back button.
            }
        )
    }

    val showBottomNav = currentRoute?.contains("Home") == true || 
                        currentRoute?.contains("About") == true || 
                        currentRoute?.contains("Subscription") == true

    var isHomeScrollingUp by remember { mutableStateOf(true) }
    
    // Trạng thái dùng để kích hoạt làm mới HomeScreen
    var refreshTrigger by remember { mutableStateOf(0) }
    
    // Đọc cấu hình kết cấu Glass cao cấp
    val usePremiumGlass by config.premiumGlassFlow.collectAsState()

    // Thứ tự trang thanh điều hướng dưới cùng, dùng để xác định hướng hiệu ứng chuyển đổi
    val mainRouteList = remember { listOf("Home", "Subscription", "About") }
    fun getNavIndex(route: String?): Int {
        return mainRouteList.indexOfFirst { route?.contains(it) == true }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                val initialIndex = getNavIndex(initialState.destination.route)
                val targetIndex = getNavIndex(targetState.destination.route)
                
                val direction = if (initialIndex != -1 && targetIndex != -1) {
                    // Chuyển đổi giữa các trang trên thanh điều hướng dưới cùng
                    if (targetIndex > initialIndex) {
                        AnimatedContentTransitionScope.SlideDirection.Left
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.Right
                    }
                } else {
                    // Điều hướng tiến mặc định (ví dụ: Home -> Detail)
                    AnimatedContentTransitionScope.SlideDirection.Left
                }
                
                slideIntoContainer(
                    towards = direction,
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                val initialIndex = getNavIndex(initialState.destination.route)
                val targetIndex = getNavIndex(targetState.destination.route)
                
                val direction = if (initialIndex != -1 && targetIndex != -1) {
                    // Chuyển đổi giữa các trang trên thanh điều hướng dưới cùng
                    if (targetIndex > initialIndex) {
                        AnimatedContentTransitionScope.SlideDirection.Left
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.Right
                    }
                } else {
                    // Điều hướng tiến mặc định (ví dụ: Home -> Detail)
                    AnimatedContentTransitionScope.SlideDirection.Left
                }

                slideOutOfContainer(
                    towards = direction,
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                val initialIndex = getNavIndex(initialState.destination.route)
                val targetIndex = getNavIndex(targetState.destination.route)
                
                val direction = if (initialIndex != -1 && targetIndex != -1) {
                    // Chuyển đổi giữa các trang trên thanh điều hướng dưới cùng (được kích hoạt qua popBackStack, ví dụ: quay lại Home)
                    if (targetIndex > initialIndex) {
                        AnimatedContentTransitionScope.SlideDirection.Left
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.Right
                    }
                } else {
                    // Điều hướng lùi mặc định (ví dụ: Detail -> Home)
                    AnimatedContentTransitionScope.SlideDirection.Right
                }

                slideIntoContainer(
                    towards = direction,
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                val initialIndex = getNavIndex(initialState.destination.route)
                val targetIndex = getNavIndex(targetState.destination.route)
                
                val direction = if (initialIndex != -1 && targetIndex != -1) {
                    // Chuyển đổi giữa các trang trên thanh điều hướng dưới cùng (được kích hoạt qua popBackStack, ví dụ: từ Subscription quay lại Home)
                    if (targetIndex > initialIndex) {
                        AnimatedContentTransitionScope.SlideDirection.Left
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.Right
                    }
                } else {
                    // Điều hướng lùi mặc định (ví dụ: Detail -> Home)
                    AnimatedContentTransitionScope.SlideDirection.Right
                }

                slideOutOfContainer(
                    towards = direction,
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) {
            composable<Screen.Home> {
                HomeScreen(
                    onNavigateToDetail = { preset: MasterPreset ->
                        preset.id?.let { id ->
                            navController.navigate(Screen.Detail(id))
                        }
                    },
                    onNavigateToCreate = {
                        navController.navigate(Screen.PresetSelection)
                    },
                    onScrollStateChanged = { isScrollingUp ->
                        isHomeScrollingUp = isScrollingUp
                    },
                    refreshTrigger = refreshTrigger,
                    usePremiumGlass = usePremiumGlass
                )
            }

            composable<Screen.PresetSelection> {
                PresetSelectionScreen(
                    onPresetSelected = { templateId ->
                        navController.navigate(Screen.CreatePreset(templateId))
                    },
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable<Screen.Detail> { backStackEntry ->
                val detail = backStackEntry.toRoute<Screen.Detail>()
                val localContext = androidx.compose.ui.platform.LocalContext.current
                val repository = PresetRepository.getInstance(localContext)
                DetailScreen(
                    presetId = detail.presetId,
                    onBack = {
                        navController.popBackStack()
                    },
                    onEdit = { presetId ->
                        navController.navigate(Screen.EditPreset(presetId))
                    },
                    refreshTrigger = refreshTrigger
                )
            }

            composable<Screen.CreatePreset> { backStackEntry ->
                val createPreset = backStackEntry.toRoute<Screen.CreatePreset>()
                val localContext = androidx.compose.ui.platform.LocalContext.current
                val repository = PresetRepository.getInstance(localContext)
                
                val viewModel: UniversalCreatePresetViewModel = viewModel(
                    factory = UniversalCreatePresetViewModelFactory(localContext, repository)
                )
                
                // Load template if not already loaded (to avoid reloading on recomposition)
                // However, viewModel survives configuration changes, but if we navigate back and forth, 
                // we might want to ensure we don't overwrite if user is editing.
                // For simplicity, we can load it once. 
                // But since we create a new screen instance on navigation, 
                // the viewModel store owner is the backStackEntry, so it's a new ViewModel instance.
                LaunchedEffect(createPreset.templateId) {
                    viewModel.loadTemplate(createPreset.templateId)
                }

                UniversalCreatePresetScreen(
                    onSave = {
                        refreshTrigger++ // Kích hoạt làm mới
                        // Navigate back to Home, popping the selection screen as well
                        navController.popBackStack(Screen.Home, false)
                    },
                    onBack = {
                        navController.popBackStack()
                    },
                    viewModel = viewModel
                )
            }

            composable<Screen.EditPreset> { backStackEntry ->
                val editPreset = backStackEntry.toRoute<Screen.EditPreset>()
                val localContext = androidx.compose.ui.platform.LocalContext.current
                val repository = PresetRepository.getInstance(localContext)
                
                val viewModel: UniversalCreatePresetViewModel = viewModel(
                    factory = UniversalCreatePresetViewModelFactory(localContext, repository)
                )

                LaunchedEffect(editPreset.presetId) {
                    viewModel.loadPresetForEdit(editPreset.presetId)
                }

                UniversalCreatePresetScreen(
                    onSave = {
                        refreshTrigger++ // Kích hoạt làm mới
                        navController.popBackStack()
                    },
                    onBack = {
                        navController.popBackStack()
                    },
                    viewModel = viewModel
                )
            }

            composable<Screen.Settings> {
                SettingsScreen()
            }

            composable<Screen.About> {
                AboutScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings)
                    },
                    onScrollStateChanged = { isScrollingUp ->
                        isHomeScrollingUp = isScrollingUp
                    },
                    onNavigateToPrivacyPolicy = {
                        navController.navigate(Screen.PrivacyPolicy)
                    },
                    onNavigateToOpenSourceLicense = {
                        navController.navigate(Screen.OpenSourceLicense)
                    },
                    currentVersionCode = VersionInfo.VERSION_CODE,
                    currentVersionName = VersionInfo.VERSION_NAME
                )
            }

            composable<Screen.Subscription> {
                com.silas.omaster.ui.subscription.SubscriptionScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                    onScrollStateChanged = { isScrollingUp ->
                        isHomeScrollingUp = isScrollingUp
                    }
                )
            }

            composable<Screen.PrivacyPolicy> {
                PrivacyPolicyScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable<Screen.OpenSourceLicense> {
                OpenSourceLicenseScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        if (showBottomNav) {
            PillNavBar(
                visible = isHomeScrollingUp,
                currentRoute = when {
                    currentRoute?.contains("Home") == true -> "home"
                    currentRoute?.contains("Subscription") == true -> "subscription"
                    currentRoute?.contains("About") == true -> "about"
                    else -> "home"
                },
                onNavigate = { route ->
                    when (route) {
                        "home" -> {
                            if (currentRoute?.contains("Home") != true) {
                                navController.popBackStack(Screen.Home, false)
                            }
                        }
                        "subscription" -> {
                            if (currentRoute?.contains("Subscription") != true) {
                                navController.navigate(Screen.Subscription) {
                                    popUpTo(Screen.Home) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                        "about" -> {
                            if (currentRoute?.contains("About") != true) {
                                navController.navigate(Screen.About) {
                                    popUpTo(Screen.Home) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
                usePremiumGlass = usePremiumGlass
            )
        }
    }
}
