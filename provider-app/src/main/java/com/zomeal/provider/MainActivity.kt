package com.zomeal.provider

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay

private val Brand = Color(0xFF087F43)
private val Ink = Color(0xFF14221B)
private val Muted = Color(0xFF68736D)
private val Mist = Color(0xFFF1F7F2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProviderTheme { ProviderApp() } }
    }
}

private enum class Screen { Login, Otp, Registration, Business, Service, Packages, Menu, Operations, Review, Submitted, SubmissionDetails, Dashboard, ManageBusiness, ChangesSubmitted, DailyOrders, Earnings, Profile, UploadedData, PayoutDetails, Notifications }

private fun draftResumeScreen(): Screen = when {
    ProviderDraft.businessName.isBlank() || ProviderDraft.contactName.isBlank() || ProviderDraft.address.isBlank() -> Screen.Business
    ProviderDraft.servicePincodes.none { it.verified } -> Screen.Service
    !(ProviderDraft.lunchEnabled || ProviderDraft.dinnerEnabled || ProviderDraft.bothEnabled) ||
        (ProviderDraft.lunchEnabled && ProviderDraft.lunchPrice.isBlank()) ||
        (ProviderDraft.dinnerEnabled && ProviderDraft.dinnerPrice.isBlank()) ||
        (ProviderDraft.bothEnabled && (ProviderDraft.bothPrice.isBlank() || ProviderDraft.bothLunchDailyPrice.isBlank())) -> Screen.Packages
    ProviderDraft.savedMenuDays.any { !it } -> Screen.Menu
    ProviderDraft.deliveryPhone.length != 10 -> Screen.Operations
    else -> Screen.Review
}

@Composable
private fun ProviderApp() {
    val context = LocalContext.current
    val repository = remember { SupabaseProviderRepository(context) }
    var screen by rememberSaveable { mutableStateOf(if (repository.isAuthenticated) Screen.Registration else Screen.Login) }
    var phone by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveStatus by remember { mutableStateOf("Draft saved") }
    var managingActiveProvider by rememberSaveable { mutableStateOf(false) }
    var manageMessage by remember { mutableStateOf<String?>(null) }
    var dashboardMessage by remember { mutableStateOf<String?>(null) }
    fun openActiveEditor(destination: Screen) {
        loading = true; manageMessage = null
        repository.loadSubmittedApplication(markPhotoBaseline = !managingActiveProvider) { payload ->
            if (payload == null) {
                loading = false
                manageMessage = "Your current business details could not be loaded. Please refresh and try again."
            } else repository.loadLatestBusinessChange { change ->
                loading = false
                if (change?.optString("status") == "REJECTED") {
                    val reason = change.optString("review_note").ifBlank { "Please review the requested corrections and submit again." }
                    manageMessage = "Changes need correction: $reason Your approved listing is still live."
                } else if (change?.optString("status") == "PENDING") {
                    manageMessage = "Your latest changes are waiting for Zomeal review. Your approved listing remains live."
                }
                managingActiveProvider = true
                screen = destination
            }
        }
    }
    fun submitActiveChanges(onDone: () -> Unit) {
        loading = true; manageMessage = null
        repository.submitActiveBusinessUpdate(ProviderDraft.toJson()) { result ->
            loading = false
            if (result.success) { manageMessage = result.message ?: "Changes submitted for Zomeal approval."; onDone() }
            else {
                // Always surface the database/photo error. Previously the editor
                // stayed open without rendering manageMessage, which made the
                // button look unresponsive even though Supabase returned an error.
                manageMessage = "Could not submit changes: ${result.message ?: "Please try again."}"
                screen = Screen.ManageBusiness
            }
        }
    }
    fun saveActiveDraft(section: String, onDone: () -> Unit = { screen = Screen.ManageBusiness }) {
        loading = true
        repository.saveDraft(ProviderDraft.toJson()) { result ->
            loading = false
            manageMessage = if (result.success) "$section saved in your change draft. Continue editing other sections, then submit everything together."
            else "Could not save draft: ${result.message ?: "Please try again."}"
            if (result.success) onDone() else screen = Screen.ManageBusiness
        }
    }
    fun routeAuthenticatedProvider() {
        loading = true
        repository.loadApplicationStatus { status ->
            if (!status?.optString("provider_id").isNullOrBlank()) {
                if (status?.optString("status") == "ACTIVE") {
                    loading = false; screen = Screen.Dashboard
                } else repository.loadSubmittedApplication { loading = false; screen = Screen.Submitted }
            } else {
                repository.loadDraft { draft ->
                    loading = false
                    if (draft != null) {
                        ProviderDraft.restore(draft)
                        screen = draftResumeScreen()
                    } else screen = Screen.Registration
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (repository.isAuthenticated) routeAuthenticatedProvider()
    }
    ProviderDraftAutoSave(
        repository = repository,
        enabled = repository.isAuthenticated && screen in setOf(Screen.Business, Screen.Service, Screen.Packages, Screen.Menu, Screen.Operations, Screen.Review),
        onStatus = { saveStatus = it }
    )
    BackHandler(enabled = screen != Screen.Login) {
        screen = when(screen) {
            Screen.Otp -> Screen.Login
            Screen.Registration -> if(repository.isAuthenticated) Screen.Dashboard else Screen.Login
            Screen.Business -> if(managingActiveProvider) Screen.ManageBusiness else Screen.Registration
            Screen.Service -> Screen.Business
            Screen.Packages -> if(managingActiveProvider) Screen.ManageBusiness else Screen.Service
            Screen.Menu -> if(managingActiveProvider) Screen.ManageBusiness else Screen.Packages
            Screen.Operations -> if(managingActiveProvider) Screen.ManageBusiness else Screen.Menu
            Screen.Review -> Screen.Operations
            Screen.SubmissionDetails -> Screen.Submitted
            Screen.ManageBusiness, Screen.ChangesSubmitted, Screen.DailyOrders, Screen.Earnings, Screen.Profile, Screen.Notifications -> Screen.Dashboard
            Screen.UploadedData -> Screen.Profile
            Screen.PayoutDetails -> Screen.Profile
            Screen.Submitted -> Screen.Submitted
            Screen.Dashboard -> Screen.Dashboard
            Screen.Login -> Screen.Login
        }
    }
    when (screen) {
        Screen.Login -> LoginScreen(phone, { phone = it.filter(Char::isDigit).take(10); error = null }, loading, error) {
            loading = true; error = null
            repository.beginAuthentication(phone) { result ->
                loading = false
                if (result.success) screen = Screen.Otp else error = result.message
            }
        }
        Screen.Otp -> OtpScreen(phone, loading, error, { error = null; screen = Screen.Login }) { otp ->
            loading = true; error = null
            repository.completeAuthentication(phone, otp) { result ->
                if (result.success) {
                    ProviderDraft.ownerPhone = phone
                    routeAuthenticatedProvider()
                } else {
                    loading = false
                    error = result.message
                }
            }
        }
        Screen.Registration -> RegistrationStart(saveStatus) { screen = Screen.Business }
        Screen.Business -> BusinessScreen(
            { screen = if (managingActiveProvider) Screen.ManageBusiness else Screen.Registration },
            { if (managingActiveProvider) saveActiveDraft("Profile changes") else screen = Screen.Service },
            activeEdit = managingActiveProvider,
            saving = loading
        )
        Screen.Service -> ServiceAreaScreen({ screen = Screen.Business }) { screen = Screen.Packages }
        Screen.Packages -> PackageScreen(
            { screen = if (managingActiveProvider) Screen.ManageBusiness else Screen.Service },
            { if (managingActiveProvider) saveActiveDraft("Package changes") else screen = Screen.Menu },
            activeEdit = managingActiveProvider,
            saving = loading
        )
        Screen.Menu -> WeeklyMenuScreen(
            onBack = { screen = if (managingActiveProvider) Screen.ManageBusiness else Screen.Packages },
            onNext = { if (managingActiveProvider) saveActiveDraft("Seven-day menu changes") else screen = Screen.Operations },
            activeEdit = managingActiveProvider,
            submittingUpdate = loading,
            onSaveDay = { _, callback -> repository.saveDraft(ProviderDraft.toJson(), callback) }
        )
        Screen.Operations -> OperationsScreen(
            { screen = if (managingActiveProvider) Screen.ManageBusiness else Screen.Menu },
            {
                if (managingActiveProvider) {
                    loading = true
                    repository.savePrimaryDeliveryContact(ProviderDraft.deliveryName, ProviderDraft.deliveryPhone) { contactResult ->
                        loading = false
                        if (contactResult.success) saveActiveDraft("Photo and delivery changes")
                        else { manageMessage = "Could not save delivery contact: ${contactResult.message ?: "Please check the number."}"; screen = Screen.ManageBusiness }
                    }
                } else screen = Screen.Review
            },
            activeEdit = managingActiveProvider,
            saving = loading
        )
        Screen.Review -> ReviewScreen({ screen = Screen.Operations }, loading, error) {
            loading = true; error = null
            repository.submitApplication(ProviderDraft.toJson()) { result ->
                loading = false
                if (result.success) screen = Screen.Submitted else error = result.message
            }
        }
        Screen.Submitted -> SubmittedScreen(
            repository,
            onReview = { repository.loadSubmittedApplication { screen = Screen.SubmissionDetails } },
            onEdit = { screen = Screen.Registration },
            onDashboard = { screen = Screen.Dashboard }
        ) { repository.signOut(); screen = Screen.Login }
        Screen.SubmissionDetails -> SubmittedApplicationDetailsScreen(
            repository = repository,
            onBack = { screen = Screen.Submitted },
            onEdit = { screen = Screen.Registration }
        )
        Screen.Dashboard -> ProviderDashboardScreen(repository, bannerMessage = dashboardMessage, onDismissBanner = { dashboardMessage = null }, onOrders = { screen = Screen.DailyOrders }, onEarnings = { screen = Screen.Earnings }, onProfile = { screen = Screen.Profile }, onManageBusiness = { openActiveEditor(Screen.ManageBusiness) }, onNotifications = { screen = Screen.Notifications }) { repository.signOut(); screen = Screen.Login }
        Screen.ManageBusiness -> ManageBusinessScreen(
            message = manageMessage,
            loading = loading,
            onBack = { managingActiveProvider = false; screen = Screen.Dashboard },
            onEditProfile = { openActiveEditor(Screen.Business) },
            onEditPackages = { openActiveEditor(Screen.Packages) },
            onEditMenus = { openActiveEditor(Screen.Menu) },
            onEditPhotos = { openActiveEditor(Screen.Operations) },
            onSubmitAll = { submitActiveChanges {
                managingActiveProvider = false
                screen = Screen.ChangesSubmitted
            } }
        )
        Screen.ChangesSubmitted -> ChangesSubmittedScreen {
            dashboardMessage = "All changes were submitted together for Zomeal review. Your approved listing remains live until a decision is made."
            screen = Screen.Dashboard
        }
        Screen.DailyOrders -> ProviderDailyOrdersScreen(repository, onDashboard = { screen = Screen.Dashboard })
        Screen.Earnings -> ProviderEarningsScreen(repository, onDashboard = { screen = Screen.Dashboard }, onOrders = { screen = Screen.DailyOrders }, onProfile = { screen = Screen.Profile })
        Screen.Profile -> ProviderProfileScreen(repository, onBack = { screen = Screen.Dashboard }, onUploadedData = { screen = Screen.UploadedData }, onManageBusiness = { openActiveEditor(Screen.ManageBusiness) }, onEarnings = { screen = Screen.Earnings }, onPayoutDetails = { screen = Screen.PayoutDetails }) { repository.signOut(); screen = Screen.Login }
        Screen.UploadedData -> ProviderUploadedDataScreen(repository, onBack = { screen = Screen.Profile })
        Screen.PayoutDetails -> ProviderPayoutDetailsScreen(repository, onBack = { screen = Screen.Profile })
        Screen.Notifications -> ProviderNotificationsScreen(repository, onBack = { screen = Screen.Dashboard }) { destination ->
            when(destination) {
                "MANAGE_BUSINESS" -> openActiveEditor(Screen.ManageBusiness)
                "EARNINGS" -> screen = Screen.Earnings
                "PAYOUT_DETAILS" -> screen = Screen.PayoutDetails
                "PROFILE" -> screen = Screen.Profile
                "ORDERS" -> screen = Screen.DailyOrders
                else -> screen = Screen.Dashboard
            }
        }
    }
}

@Composable
private fun ChangesSubmittedScreen(onDashboard: () -> Unit) {
    LaunchedEffect(Unit) { delay(3200); onDashboard() }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF5FBF6), Color.White, Color(0xFFEAF5DF))))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(.8f))
        Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(138.dp).clip(CircleShape).background(Color(0xFFDFF3E5)))
            Box(Modifier.size(104.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.CheckCircle, null, tint = Brand, modifier = Modifier.size(70.dp))
            }
            Box(Modifier.align(Alignment.TopEnd).size(38.dp).clip(CircleShape).background(Color(0xFFFFF2CC)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AccessTime, null, tint = Color(0xFF9A6A00), modifier = Modifier.size(21.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("Changes submitted!", color = Ink, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("Your complete request is now waiting for Zomeal review.", color = Brand, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text("Your currently approved profile, packages and menus will remain live until the new changes are confirmed. We’ll notify you after approval or if anything needs correction.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(25.dp))
        Surface(color = Mist, shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Notifications, null, tint = Brand, modifier = Modifier.size(23.dp))
                Spacer(Modifier.width(11.dp)); Column { Text("What happens next?", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text("Zomeal reviews one combined request and sends the decision in Notifications.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp) }
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onDashboard, Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Brand), shape = RoundedCornerShape(15.dp)) {
            Icon(Icons.Outlined.Home, null); Spacer(Modifier.width(8.dp)); Text("Go to dashboard", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text("Redirecting automatically…", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun LoginScreen(phone: String, onPhoneChange: (String) -> Unit, loading: Boolean, error: String?, onContinue: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFF9FCF9), Color.White, Color(0xFFEFF7DF))))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Restaurant, null, tint = Color.White, modifier = Modifier.size(27.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("zomeal", color = Brand, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold)
                    Text("PARTNER", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            }
            Spacer(Modifier.height(42.dp))
            Text("Grow your meal\nbusiness with Zomeal", color = Ink, fontSize = 33.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Manage menus, daily orders, delivery capacity and earnings from one simple partner app.", color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(25.dp))
            Benefits()
            Spacer(Modifier.height(27.dp))
            Text("Mobile number", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = onPhoneChange,
                modifier = Modifier.fillMaxWidth(),
                prefix = { Text("🇮🇳  +91  ", fontWeight = FontWeight.SemiBold) },
                placeholder = { Text("Enter your 10-digit number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Brand, cursorColor = Brand)
            )
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.clickable { accepted = !accepted }) {
                Checkbox(accepted, { accepted = it }, colors = CheckboxDefaults.colors(checkedColor = Brand))
                Text("I agree to the Partner Terms and confirm I am authorized to register this business.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onContinue,
                enabled = phone.length == 10 && accepted && !loading,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand, disabledContainerColor = Color(0xFFB9C9BE))
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Continue securely", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(9.dp)); Icon(Icons.Outlined.ArrowForward, null)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
            Text("Already registered? Use the same number to continue your saved application.", color = Muted, textAlign = TextAlign.Center, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 17.dp))
        }
    }
}

@Composable
private fun Benefits() {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Mist).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Benefit("7-day", "Menu setup")
        Benefit("Live", "Order counts")
        Benefit("48 hrs", "Earnings ready")
    }
}

@Composable
private fun Benefit(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Brand, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun OtpScreen(phone: String, loading: Boolean, error: String?, onBack: () -> Unit, onVerified: (String) -> Unit) {
    var otp by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        Text("‹  Back", color = Brand, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 12.dp))
        Spacer(Modifier.height(45.dp))
        Icon(Icons.Outlined.CheckCircle, null, tint = Brand, modifier = Modifier.size(58.dp).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(20.dp))
        Text("Verify your number", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text("Enter the OTP sent to +91 ${phone.take(2)}XXXXXX${phone.takeLast(2)}", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Spacer(Modifier.height(30.dp))
        OutlinedTextField(
            value = otp,
            onValueChange = { otp = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("6-digit OTP") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(Modifier.height(18.dp))
        Button({ onVerified(otp) }, enabled = otp.length == 6 && !loading, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Brand)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text("Verify & continue", fontWeight = FontWeight.Bold)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) }
        Text(if (BuildConfig.DEVELOPMENT_AUTH && phone in setOf("9999999999","7000000001","7000000002","7000000003","7000000004","7000000005")) "Development testing: use OTP 123456" else "Enter the verification code sent by SMS.", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 13.dp))
    }
}

@Composable
private fun RegistrationStart(saveStatus: String, onStart: () -> Unit) {
    val steps = listOf("Business information", "Service areas & capacity", "Packages & prices", "Monday–Sunday menus", "Photos & delivery contacts", "Review and submit")
    Column(Modifier.fillMaxSize().background(Color(0xFFF7FAF7)).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        Text("zomeal partner", color = Brand, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(25.dp))
        Text("Let’s set up your business", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Progress saves automatically. Bank or UPI details will only be requested after activation.", color = Muted, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
        Text(saveStatus, color = Brand, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        steps.forEachIndexed { index, title ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(33.dp).clip(CircleShape).background(if (index == 0) Brand else Mist), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", color = if (index == 0) Color.White else Brand, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp)); Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onStart, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Brand)) {
            Icon(Icons.Outlined.Storefront, null); Spacer(Modifier.width(8.dp)); Text("Start business details", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProviderTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * 1.10f)) {
        MaterialTheme(colorScheme = lightColorScheme(primary = Brand, surface = Color.White, background = Color(0xFFF8FBF8)), content = content)
    }
}
