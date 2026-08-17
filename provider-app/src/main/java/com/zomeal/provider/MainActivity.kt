package com.zomeal.provider

import android.os.Bundle
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
import androidx.compose.material.icons.outlined.CheckCircle
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

private enum class Screen { Login, Otp, Registration, Business, Service, Packages, Menu, Operations, Review, Submitted, SubmissionDetails, Dashboard, DailyOrders, Earnings, Profile, PayoutDetails, Notifications }

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
        Screen.Business -> BusinessScreen({ screen = Screen.Registration }) { screen = Screen.Service }
        Screen.Service -> ServiceAreaScreen({ screen = Screen.Business }) { screen = Screen.Packages }
        Screen.Packages -> PackageScreen({ screen = Screen.Service }) { screen = Screen.Menu }
        Screen.Menu -> WeeklyMenuScreen(
            onBack = { screen = Screen.Packages },
            onNext = { screen = Screen.Operations },
            onSaveDay = { _, callback -> repository.saveDraft(ProviderDraft.toJson(), callback) }
        )
        Screen.Operations -> OperationsScreen({ screen = Screen.Menu }) { screen = Screen.Review }
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
        Screen.Dashboard -> ProviderDashboardScreen(repository, onOrders = { screen = Screen.DailyOrders }, onEarnings = { screen = Screen.Earnings }, onProfile = { screen = Screen.Profile }, onNotifications = { screen = Screen.Notifications }) { repository.signOut(); screen = Screen.Login }
        Screen.DailyOrders -> ProviderDailyOrdersScreen(repository, onDashboard = { screen = Screen.Dashboard })
        Screen.Earnings -> ProviderEarningsScreen(repository, onDashboard = { screen = Screen.Dashboard }, onOrders = { screen = Screen.DailyOrders }, onProfile = { screen = Screen.Profile })
        Screen.Profile -> ProviderProfileScreen(repository, onBack = { screen = Screen.Dashboard }, onEarnings = { screen = Screen.Earnings }, onPayoutDetails = { screen = Screen.PayoutDetails }) { repository.signOut(); screen = Screen.Login }
        Screen.PayoutDetails -> ProviderPayoutDetailsScreen(repository, onBack = { screen = Screen.Profile })
        Screen.Notifications -> ProviderNotificationsScreen(repository, onBack = { screen = Screen.Dashboard }) { destination -> screen = when(destination) { "EARNINGS" -> Screen.Earnings; "PAYOUT_DETAILS" -> Screen.PayoutDetails; "PROFILE" -> Screen.Profile; "ORDERS" -> Screen.DailyOrders; else -> Screen.Dashboard } }
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
        Text(if (phone == "9999999999" && BuildConfig.DEVELOPMENT_AUTH) "Development testing: use OTP 123456" else "Enter the verification code sent by SMS.", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 13.dp))
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
    MaterialTheme(colorScheme = lightColorScheme(primary = Brand, surface = Color.White, background = Color(0xFFF8FBF8)), content = content)
}
