package com.zomeal.app

import android.os.Bundle
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener

private val Brand = Color(0xFF078A45)
private val BrandDark = Color(0xFF006B38)
private val Lime = Color(0xFFB7DA45)
private val Ink = Color(0xFF10231B)
private val Muted = Color(0xFF66716B)
private val Mist = Color(0xFFF2F8F4)
private val Border = Color(0xFFDCE8E0)

private object CustomerProfileStore {
    var house by mutableStateOf("")
    var street by mutableStateOf("")
    var locality by mutableStateOf("Khandagiri, Bhubaneswar")
    var landmark by mutableStateOf("")
    var pincode by mutableStateOf("751030")
    var addressSaved by mutableStateOf(false)

    val completeAddress: String
        get() = listOf(house, street, locality, landmark.takeIf { it.isNotBlank() }, "Odisha - $pincode")
            .filterNotNull().filter { it.isNotBlank() }.joinToString(", ")
}

private data class SavedCustomerMeal(val mainCourse: String, val carb: String)

private object CustomerMenuStore {
    val lunches = mutableStateMapOf<Int, SavedCustomerMeal>()
    val dinners = mutableStateMapOf<Int, SavedCustomerMeal>()
    var packageKind by mutableStateOf("LUNCH_AND_DINNER")
}

private object CustomerSubscriptionStore {
    var current by mutableStateOf<PersistedSubscription?>(null)
}

private sealed interface RazorpayAppResult {
    data class Success(val paymentId:String,val orderId:String,val signature:String):RazorpayAppResult
    data class Failure(val code:Int,val message:String):RazorpayAppResult
}

private object RazorpayCoordinator {
    var pendingOrder by mutableStateOf<RazorpayCheckoutOrder?>(null)
    var result by mutableStateOf<RazorpayAppResult?>(null)
}

class MainActivity : ComponentActivity(), PaymentResultWithDataListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Checkout.preload(applicationContext)
        setContent { ZomealTheme { ZomealApp() } }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        val paymentId=razorpayPaymentId.orEmpty().ifBlank{paymentData?.paymentId.orEmpty()}
        RazorpayCoordinator.result=RazorpayAppResult.Success(paymentId,paymentData?.orderId.orEmpty(),paymentData?.signature.orEmpty())
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        RazorpayCoordinator.result=RazorpayAppResult.Failure(code,response?:"Payment was cancelled or failed")
    }
}

@Composable
private fun ZomealApp() {
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(2200)
        showSplash = false
    }

    if (showSplash) SplashScreen() else ProviderListScreen()
}

@Composable
private fun SplashScreen() {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFCFEFA))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val compact = maxHeight < 700.dp
        val logoHeight = if (compact) 178.dp else 220.dp
        val plateSize = if (compact) 235.dp else 315.dp

        SplashBackground(Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) 18.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(if (compact) 0.32f else 0.42f))

            Box(
                modifier = Modifier
                    .fillMaxWidth(if (compact) 0.78f else 0.86f)
                    .height(logoHeight)
                    .clip(RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.zomeal_logo),
                    contentDescription = "Zomeal — Monthly meals, made simple",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().scale(1.18f)
                )
            }

            Spacer(Modifier.height(if (compact) 8.dp else 18.dp))
            SplashBenefits(compact)
            Spacer(Modifier.weight(if (compact) 0.22f else 0.32f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(plateSize * 0.82f),
                contentAlignment = Alignment.BottomCenter
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawOval(
                        brush = Brush.radialGradient(
                            listOf(Color(0x334FAE35), Color.Transparent),
                            center = center,
                            radius = size.minDimension * 0.62f
                        ),
                        topLeft = Offset(size.width * 0.08f, size.height * 0.08f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.92f)
                    )
                }
                Image(
                    painter = painterResource(R.drawable.lunch_thali),
                    contentDescription = "Indian home-style lunch thali",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(plateSize)
                )
            }

            Row(
                modifier = Modifier.padding(top = if (compact) 5.dp else 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(if (compact) 16.dp else 19.dp),
                    color = Brand,
                    trackColor = Color(0xFFDCECCB),
                    strokeWidth = 2.5.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Preparing your best meal experience…",
                    color = Ink,
                    fontSize = if (compact) 12.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SplashBackground(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(Color(0x0FAAD84F), radius = size.width * 0.42f, center = Offset(size.width, 0f))
        drawCircle(Color(0x0D078A45), radius = size.width * 0.30f, center = Offset(0f, size.height * 0.30f))

        val rearWave = Path().apply {
            moveTo(0f, size.height * 0.77f)
            cubicTo(size.width * 0.25f, size.height * 0.87f, size.width * 0.55f, size.height * 0.66f, size.width, size.height * 0.73f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(rearWave, Color(0x1FB7DA45))

        val frontWave = Path().apply {
            moveTo(0f, size.height * 0.88f)
            cubicTo(size.width * 0.25f, size.height * 0.76f, size.width * 0.70f, size.height * 0.98f, size.width, size.height * 0.83f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(frontWave, Color(0x29A8D94B))

        listOf(
            Offset(size.width * 0.12f, size.height * 0.17f),
            Offset(size.width * 0.88f, size.height * 0.23f),
            Offset(size.width * 0.18f, size.height * 0.61f),
            Offset(size.width * 0.80f, size.height * 0.58f)
        ).forEachIndexed { index, point ->
            drawOval(
                color = if (index % 2 == 0) Color(0x2F76B82A) else Color(0x257BBF35),
                topLeft = point,
                size = androidx.compose.ui.geometry.Size(size.width * 0.045f, size.width * 0.018f)
            )
        }
    }
}

@Composable
private fun SplashBenefits(compact: Boolean) {
    val benefits = listOf(
        Triple(Icons.Outlined.RamenDining, "Homely", "Meals"),
        Triple(Icons.Outlined.CalendarMonth, "Monthly", "Plans"),
        Triple(Icons.Outlined.DeliveryDining, "On-time", "Delivery"),
        Triple(Icons.Outlined.VerifiedUser, "Safe &", "Hygienic")
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top
    ) {
        benefits.forEachIndexed { index, benefit ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(if (compact) 43.dp else 52.dp)
                        .background(Color(0xFFF0F8E9), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        benefit.first,
                        contentDescription = null,
                        tint = Color(0xFF4AA51F),
                        modifier = Modifier.size(if (compact) 23.dp else 28.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    benefit.second,
                    color = Ink,
                    fontSize = if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    benefit.third,
                    color = Ink,
                    fontSize = if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            if (index < benefits.lastIndex) {
                VerticalDivider(
                    modifier = Modifier.height(if (compact) 58.dp else 70.dp).padding(top = 4.dp),
                    color = Border
                )
            }
        }
    }
}

@Composable
private fun ZomealTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * 1.08f)) {
        MaterialTheme(
            colorScheme = lightColorScheme(primary = Brand, background = Color.White, surface = Color.White),
            typography = Typography(
                headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
                titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            ),
            content = content
        )
    }
}

private enum class DietFilter(val label: String, val emoji: String) {
    ALL("All", ""), VEG("Veg", "●"), NON_VEG("Non-Veg", "●"), BOTH("Both", "●"), TOP("Top Rated", "★")
}

private data class Provider(
    val name: String,
    val locality: String,
    val diet: String,
    val category: DietFilter,
    val rating: Double,
    val reviews: Int,
    val price: Int,
    val tint: Color,
    val accent: Color,
    val id: String = "",
    val packageId: String? = null,
    val packageKind: String? = null,
    val packages: List<MarketplacePackage> = emptyList(),
    val weeklyMenu: String = "[]",
    val isLive: Boolean = false,
    val description: String = "",
    val primaryPhotoPath: String = "",
    val kitchenPhotoPath: String = "",
    val mealPhotoPath: String = ""
)

private val providers = emptyList<Provider>()

private fun marketplaceProviderToUi(index:Int,record:MarketplaceProvider):Provider{
    val normalizedDiet=record.dietaryType.trim().uppercase()
    val category=when(normalizedDiet){"VEG","PURE_VEG","VEGETARIAN","VEGAN"->DietFilter.VEG;"NON_VEG","NON_VEGETARIAN"->DietFilter.NON_VEG;else->DietFilter.BOTH}
    val palette=listOf(Color(0xFFFFE0A7) to Color(0xFFD37B19),Color(0xFFE8D2A4) to Color(0xFFB65D22),Color(0xFFD5E9D1) to Color(0xFF4E944C),Color(0xFFF3C4A5) to Color(0xFFA44021))[index%4]
    val firstPackage=record.packages.minByOrNull{it.pricePaise}
    return Provider(name=record.name,locality=record.locality,diet=when(category){DietFilter.VEG->"Pure Veg";DietFilter.NON_VEG->"Non-Veg";else->"Veg & Non-Veg"},category=category,rating=0.0,reviews=0,price=((firstPackage?.pricePaise?:0)/100).toInt(),tint=palette.first,accent=palette.second,id=record.id,packageId=firstPackage?.id,packageKind=firstPackage?.kind,packages=record.packages,weeklyMenu=record.menu.toString(),isLive=true,description=record.description,primaryPhotoPath=record.primaryPhotoPath,kitchenPhotoPath=record.kitchenPhotoPath,mealPhotoPath=record.mealPhotoPath)
}

private fun persistedSubscriptionToProvider(subscription:PersistedSubscription):Provider{
    val amountPaise=subscription.payment?.optLong("amount_paise")?:0L
    val packageRecord=MarketplacePackage(subscription.packageId,subscription.packageName,subscription.packageKind,amountPaise)
    return Provider(
        name=subscription.providerName,locality=subscription.address?.optString("locality","Bhubaneswar")?:"Bhubaneswar",
        diet="Subscribed plan",category=DietFilter.BOTH,rating=0.0,reviews=0,price=(amountPaise/100).toInt(),
        tint=Color(0xFFD5E9D1),accent=Color(0xFF4E944C),id=subscription.providerId,
        packageId=subscription.packageId,packageKind=subscription.packageKind,packages=listOf(packageRecord),
        weeklyMenu=subscription.weeklyMenu.toString(),isLive=true,description="Your active Zomeal meal provider"
    )
}

private fun pendingCheckoutProvider(draft:PendingCheckout):Provider{
    val value=draft.payload.optJSONObject("provider")?:JSONObject()
    val packageValue=draft.payload.optJSONObject("package")?:JSONObject()
    val kind=packageValue.optString("kind","LUNCH_AND_DINNER")
    val price=packageValue.optString("price","₹0")
    val packageRecord=MarketplacePackage(draft.packageId,packageValue.optString("title","Monthly plan"),kind,price.filter(Char::isDigit).toLongOrNull()?.times(100)?:0)
    val category=runCatching{DietFilter.valueOf(value.optString("dietary_type","BOTH"))}.getOrDefault(DietFilter.BOTH)
    return Provider(value.optString("name","Your selected provider"),value.optString("locality","Bhubaneswar"),value.optString("diet","Meal plan"),category,0.0,0,(packageRecord.pricePaise/100).toInt(),Color(0xFFD5E9D1),Color(0xFF4E944C),draft.providerId,draft.packageId,kind,listOf(packageRecord),(value.optJSONArray("weekly_menu")?:JSONArray()).toString(),true,value.optString("description"),value.optString("photo_path"))
}

private fun pendingCheckoutPlan(draft:PendingCheckout):MealPackage{
    val value=draft.payload.optJSONObject("package")?:JSONObject()
    val kind=value.optString("kind","LUNCH_AND_DINNER")
    return MealPackage(draft.packageId,kind,value.optString("title","Monthly plan"),value.optString("meals",if(kind=="LUNCH_AND_DINNER")"2 meals / day" else "1 meal / day"),value.optString("price","₹0"),if(kind=="LUNCH_ONLY")Icons.Outlined.LightMode else if(kind=="DINNER_ONLY")Icons.Outlined.DarkMode else Icons.Outlined.Restaurant,true)
}

private fun jsonSelectionMap(value:JSONObject?):Map<Int,String>{
    if(value==null)return emptyMap()
    return buildMap{value.keys().forEach{key->key.toIntOrNull()?.let{put(it,value.optString(key))}}}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderListScreen() {
    val appContext=LocalContext.current.applicationContext
    val marketplaceRepository = remember(appContext) { SupabaseCustomerRepository(appContext) }
    var signupComplete by rememberSaveable { mutableStateOf(false) }
    var awaitingOtp by rememberSaveable { mutableStateOf(false) }
    var pendingFullName by rememberSaveable { mutableStateOf("") }
    var pendingMobile by rememberSaveable { mutableStateOf("") }
    var pendingPincode by rememberSaveable { mutableStateOf("") }
    var pendingReferralCode by rememberSaveable { mutableStateOf("") }
    var serviceUnavailable by rememberSaveable { mutableStateOf(false) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var pendingIsLogin by rememberSaveable { mutableStateOf(false) }
    var showNoSubscriptionHome by rememberSaveable { mutableStateOf(false) }
    var browseMode by rememberSaveable { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DietFilter.ALL) }
    var sortByRating by remember { mutableStateOf(false) }
    var showDiscoveryProfile by rememberSaveable { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf<Provider?>(null) }
    var activeProvider by remember { mutableStateOf<Provider?>(null) }
    var changingProvider by rememberSaveable { mutableStateOf(false) }
    var liveProviders by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var marketplaceLoading by remember { mutableStateOf(false) }
    var marketplaceError by remember { mutableStateOf<String?>(null) }
    var restoringSession by remember { mutableStateOf(true) }
    var pendingCheckoutState by remember { mutableStateOf<PendingCheckout?>(null) }
    var resumePendingPayment by remember { mutableStateOf(false) }
    val requestedPincode = pendingPincode.ifBlank { "751030" }
    val availableProviders = liveProviders

    fun refreshMarketplace() {
        marketplaceLoading = true; marketplaceError = null
        marketplaceRepository.marketplace(requestedPincode) { records, error ->
            marketplaceLoading = false
            if(error?.contains("JWT expired",ignoreCase=true)==true||error?.contains("session expired",ignoreCase=true)==true){
                marketplaceRepository.signOut();liveProviders=emptyList();marketplaceError=null;signupComplete=false;showLogin=true
                return@marketplace
            }
            marketplaceError = error
            val refreshedProviders = records.mapIndexed(::marketplaceProviderToUi)
            liveProviders = refreshedProviders

            // Provider changes become customer-visible immediately after admin
            // approval. Replace an already-open details model as well; otherwise
            // that screen would keep rendering the object captured when it opened.
            selectedProvider?.let { current ->
                val refreshed = refreshedProviders.firstOrNull { it.id == current.id }
                if (refreshed == null) {
                    selectedProvider = null
                    marketplaceError = "This provider is temporarily unavailable while Zomeal verifies its complete weekly menu."
                } else {
                    val selectedPackage = current.packageId?.let { packageId ->
                        refreshed.packages.firstOrNull { it.id == packageId }
                    }
                    selectedProvider = if (selectedPackage == null) refreshed else refreshed.copy(
                        packageId = selectedPackage.id,
                        packageKind = selectedPackage.kind,
                        price = (selectedPackage.pricePaise / 100).toInt()
                    )
                }
            }
            // A restored subscription contains the provider/package identity and
            // saved selections, but not the latest approved catalogue media.
            // Enrich it with the live marketplace row so Home receives approved
            // menu-item photos, provider profile, kitchen and meal images.
            activeProvider?.let { current ->
                refreshedProviders.firstOrNull { it.id == current.id }?.let { refreshed ->
                    val activePackage = current.packageId?.let { packageId -> refreshed.packages.firstOrNull { it.id == packageId } }
                    activeProvider = refreshed.copy(
                        packageId = activePackage?.id ?: current.packageId,
                        packageKind = activePackage?.kind ?: current.packageKind,
                        price = ((activePackage?.pricePaise ?: current.price.toLong() * 100L) / 100L).toInt()
                    )
                }
            }
        }
    }

    fun restoreSubscription(subscription:PersistedSubscription){
        CustomerSubscriptionStore.current=subscription
        subscription.address?.let{address->
            CustomerProfileStore.house=address.optString("house")
            CustomerProfileStore.street=address.optString("street")
            CustomerProfileStore.locality=address.optString("locality","Bhubaneswar")
            CustomerProfileStore.landmark=address.optString("landmark")
            CustomerProfileStore.pincode=address.optString("pincode",marketplaceRepository.savedPincode)
            CustomerProfileStore.addressSaved=true
        }
        pendingPincode=subscription.address?.optString("pincode",marketplaceRepository.savedPincode)?:marketplaceRepository.savedPincode
        activeProvider=persistedSubscriptionToProvider(subscription)
        browseMode=false;signupComplete=true;showNoSubscriptionHome=false
        marketplaceRepository.marketplace(pendingPincode.ifBlank{"751030"}){records,_->
            records.firstOrNull{it.id==subscription.providerId}?.let{record->
                val refreshed=marketplaceProviderToUi(0,record)
                val selected=refreshed.packages.firstOrNull{it.id==subscription.packageId}
                activeProvider=refreshed.copy(packageId=subscription.packageId,packageKind=selected?.kind?:subscription.packageKind,price=((selected?.pricePaise?:0)/100).toInt())
            }
        }
    }

    LaunchedEffect(Unit){
        marketplaceRepository.restoreSession{authenticated,error->
            if(!authenticated){marketplaceError=error;restoringSession=false}
            else marketplaceRepository.activeSubscription{subscription,subscriptionError->
                if(subscription!=null)restoreSubscription(subscription)
                else{
                    pendingPincode=marketplaceRepository.savedPincode
                    signupComplete=true
                    marketplaceError=subscriptionError
                    marketplaceRepository.pendingCheckout{draft,draftError->pendingCheckoutState=draft;if(draftError!=null)marketplaceError=draftError}
                }
                restoringSession=false
            }
        }
    }

    LaunchedEffect(signupComplete, requestedPincode) {
        if (signupComplete && marketplaceRepository.configured) refreshMarketplace()
    }

    // Re-query Supabase whenever the customer returns to Zomeal. Admin-approved
    // provider edits therefore appear without clearing app data or signing in again.
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity, signupComplete, requestedPincode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && signupComplete && marketplaceRepository.configured) {
                refreshMarketplace()
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    if(restoringSession){
        Box(Modifier.fillMaxSize().background(Color(0xFFFBFDF9)).statusBarsPadding().navigationBarsPadding(),contentAlignment=Alignment.Center){
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                Text("zomeal",color=Brand,fontSize=30.sp,fontWeight=FontWeight.Black)
                Spacer(Modifier.height(18.dp));CircularProgressIndicator(color=Brand,strokeWidth=3.dp)
                Spacer(Modifier.height(10.dp));Text("Restoring your meal plan…",color=Muted,fontSize=11.sp)
            }
        }
        return
    }

    if (!signupComplete) {
        if (serviceUnavailable) {
            ServiceUnavailableScreen(
                pincode = pendingPincode,
                onExplore = {
                    browseMode = true
                    signupComplete = true
                },
                onTryAnotherPincode = {
                    serviceUnavailable = false
                    awaitingOtp = false
                }
            )
        } else if (awaitingOtp) {
            OtpVerificationScreen(
                mobile = pendingMobile,
                onVerified = { otp,complete ->
                    marketplaceRepository.completeAuthentication(pendingMobile,otp){auth->
                        if(!auth.success){complete(auth.message?:"OTP verification failed");return@completeAuthentication}
                        marketplaceRepository.saveRegistrationProfile(pendingFullName,pendingMobile){profileError->
                            if(profileError!=null){complete(profileError);return@saveRegistrationProfile}
                            fun continueRegistration(){
                                marketplaceRepository.savePincode(pendingPincode.ifBlank{"751030"})
                                if (pendingIsLogin) {
                                    marketplaceRepository.activeSubscription{subscription,error->
                                        if(error!=null){complete(error);return@activeSubscription}
                                        browseMode=false
                                        if(subscription!=null)restoreSubscription(subscription)
                                        else{signupComplete=true;activeProvider=null;showNoSubscriptionHome=false}
                                        complete(null)
                                    }
                                } else if(marketplaceRepository.configured){
                                    marketplaceLoading=true
                                    marketplaceRepository.marketplace(pendingPincode){records,error->
                                        marketplaceLoading=false
                                        if(error!=null){marketplaceError=error;complete("We couldn't check providers right now. Check your internet and try again.")}
                                        else if(records.isEmpty()){serviceUnavailable=true;complete(null)}
                                        else{liveProviders=records.mapIndexed(::marketplaceProviderToUi);browseMode=false;signupComplete=true;complete(null)}
                                    }
                                } else {marketplaceError="Supabase is not configured in local.properties";complete("The app is not connected to Zomeal services. Please install the latest build.")}
                            }
                            if(!pendingIsLogin&&pendingReferralCode.isNotBlank()) marketplaceRepository.applyReferral(pendingReferralCode){_,referralError->if(referralError!=null)complete(referralError) else continueRegistration()}
                            else continueRegistration()
                        }
                    }
                },
                onBack = { awaitingOtp = false }
            )
        } else {
            if (showLogin) LoginScreen(
                onContinue = { mobile ->
                    pendingFullName = ""
                    pendingMobile = mobile
                    pendingPincode = "751030"
                    pendingIsLogin = true
                    awaitingOtp = true
                },
                onCreateAccount = { showLogin = false }
            ) else SignupScreen(onContinue = { fullName, mobile, pincode, referralCode ->
                pendingFullName = fullName
                pendingMobile = mobile
                pendingPincode = pincode
                pendingReferralCode = referralCode
                pendingIsLogin = false
                awaitingOtp = true
            }, onLogin = { showLogin = true })
        }
        return
    }

    activeProvider?.takeUnless { changingProvider }?.let { provider ->
        ActiveSubscriberHome(provider, onBrowseProviders = {
            changingProvider = true
            selectedProvider = null
        }, onLogout = {
            marketplaceRepository.signOut()
            activeProvider = null
            selectedProvider = null
            signupComplete = false
            awaitingOtp = false
            serviceUnavailable = false
            pendingIsLogin = false
            showLogin = true
        })
        return
    }

    pendingCheckoutState?.let { draft ->
        val draftProvider=remember(draft){pendingCheckoutProvider(draft)}
        val draftPlan=remember(draft){pendingCheckoutPlan(draft)}
        val payload=draft.payload
        payload.optJSONObject("delivery_address")?.let{address->CustomerProfileStore.house=address.optString("house");CustomerProfileStore.street=address.optString("street");CustomerProfileStore.locality=address.optString("locality");CustomerProfileStore.landmark=address.optString("landmark");CustomerProfileStore.pincode=address.optString("pincode");CustomerProfileStore.addressSaved=true}
        val draftMenu=payload.optJSONObject("weekly_menu")?:JSONObject()
        if(resumePendingPayment){
            PaymentScreen(draftProvider,draftPlan,jsonSelectionMap(draftMenu.optJSONObject("lunch")),jsonSelectionMap(draftMenu.optJSONObject("dinner")),payload.optInt("base_price"),payload.optInt("platform_fee"),payload.optInt("delivery_fee"),payload.optInt("discount"),payload.optInt("total"),onBack={resumePendingPayment=false},onGoHome={marketplaceRepository.activeSubscription{subscription,_->if(subscription!=null)restoreSubscription(subscription) else{pendingCheckoutState=null;showNoSubscriptionHome=true}}})
        }else PendingCheckoutResumeScreen(draftProvider,draftPlan,onContinue={resumePendingPayment=true},onSkip={showNoSubscriptionHome=true;pendingCheckoutState=null},onDiscard={marketplaceRepository.clearCheckoutDraft{error->if(error==null)pendingCheckoutState=null else marketplaceError=error}})
        return
    }

    if (showNoSubscriptionHome) {
        NoSubscriptionHomeScreen(
            onFindPlan = { showNoSubscriptionHome = false },
            onLogout = { marketplaceRepository.signOut();showNoSubscriptionHome = false; signupComplete = false; awaitingOtp = false; showLogin = true }
        )
        return
    }

    BackHandler(enabled = changingProvider && selectedProvider == null) { changingProvider = false }

    BackHandler(enabled = selectedProvider != null) { selectedProvider = null }
    selectedProvider?.let { provider ->
        ProviderDetailsScreen(
            provider = provider,
            onBack = { selectedProvider = null },
            onActivated = {
                if (changingProvider) {
                    activeProvider = provider
                    changingProvider = false
                    selectedProvider = null
                } else {
                    activeProvider = provider
                    selectedProvider = null
                }
            },
            changeProviderMode = changingProvider
        )
        return
    }

    // The live providers arrive asynchronously. Include them in the keys so the
    // filtered list is rebuilt as soon as Supabase returns the marketplace rows.
    val visibleProviders = remember(availableProviders, query, filter, sortByRating) {
        availableProviders.filter {
            (query.isBlank() || it.name.contains(query, true) || it.locality.contains(query, true) || it.diet.contains(query, true)) &&
                when (filter) {
                    DietFilter.ALL -> true
                    DietFilter.TOP -> it.rating >= 4.6
                    else -> it.category == filter || it.category == DietFilter.BOTH
                }
        }.let { list -> if (sortByRating) list.sortedByDescending { it.rating } else list }
    }

    if (showDiscoveryProfile) {
        DiscoveryAccountDialog(
            pincode = pendingPincode.ifBlank { "751030" },
            onDismiss = { showDiscoveryProfile = false },
            onLogout = {
                marketplaceRepository.signOut()
                showDiscoveryProfile = false
                signupComplete = false
                awaitingOtp = false
                selectedProvider = null
                liveProviders = emptyList()
                showLogin = true
            }
        )
    }

    Scaffold(containerColor = Color.White) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                ServiceProviderHeader(
                    query = query,
                    pincode = pendingPincode.ifBlank { "751030" },
                    providerCount = availableProviders.size,
                    browseMode = browseMode,
                    onQueryChange = { query = it },
                    onProfile = { showDiscoveryProfile = true },
                    onBack = if (changingProvider) ({ changingProvider = false }) else null
                )
            }
            if (marketplaceLoading) item { MarketplaceStatusCard("Finding approved kitchens near you…", false, null) }
            marketplaceError?.let { problem -> item { MarketplaceStatusCard("Providers could not be loaded.", true, problem) { refreshMarketplace() } } }
            if(marketplaceError==null)item { AvailabilityBanner(pendingPincode.ifBlank { "751030" }, availableProviders.size, browseMode) }
            if(marketplaceError==null)item {
                ProviderSectionHeader(
                    count = visibleProviders.size,
                    pincode = pendingPincode.ifBlank { "751030" },
                    browseMode = browseMode
                )
            }
            if(marketplaceError==null)item {
                ProviderFilterPanel(filter, sortByRating, onFilter = { filter = it }, onSort = { sortByRating = !sortByRating })
            }
            if (marketplaceError==null && !marketplaceLoading && visibleProviders.isEmpty()) {
                item { EmptyProviders(onClear = { query = ""; filter = DietFilter.ALL }) }
            } else if (!marketplaceLoading) {
                items(
                    visibleProviders,
                    key = { provider -> provider.id.ifBlank { "${provider.name}-${provider.locality}-${provider.packageId.orEmpty()}" } }
                ) { provider ->
                    ProviderCard(provider, onClick = { selectedProvider = provider })
                }
            }
        }
    }
}

@Composable
private fun PendingCheckoutResumeScreen(provider:Provider,plan:MealPackage,onContinue:()->Unit,onSkip:()->Unit,onDiscard:()->Unit){
    Scaffold(containerColor=Color(0xFFFAFCFA),bottomBar={Surface(modifier=Modifier.navigationBarsPadding(),color=Color.White,shadowElevation=8.dp){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=onContinue,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(15.dp),colors=ButtonDefaults.buttonColors(containerColor=Brand)){Text("Continue to Payment",fontSize=12.sp,fontWeight=FontWeight.ExtraBold)};OutlinedButton(onClick=onSkip,modifier=Modifier.fillMaxWidth().height(44.dp),shape=RoundedCornerShape(14.dp)){Text("Skip for now and open Home",color=BrandDark,fontSize=10.sp,fontWeight=FontWeight.Bold)}}}}){padding->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            Surface(color=Mist,shape=CircleShape){Icon(Icons.Outlined.Payment,null,tint=Brand,modifier=Modifier.padding(18.dp).size(34.dp))}
            Spacer(Modifier.height(18.dp));Text("Your plan is ready",color=Ink,fontSize=23.sp,fontWeight=FontWeight.Black);Text("Continue where you left off. You will not be charged until Razorpay confirms the payment.",color=Muted,fontSize=10.sp,lineHeight=15.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(22.dp));Surface(Modifier.fillMaxWidth(),color=Color.White,shape=RoundedCornerShape(20.dp),shadowElevation=2.dp){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(provider.tint)){ApprovedProviderImage(provider,Modifier.fillMaxSize())};Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text(provider.name,color=Ink,fontSize=15.sp,fontWeight=FontWeight.ExtraBold);Text(plan.title,color=BrandDark,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(plan.price+" / month",color=Muted,fontSize=9.sp)}}}
            TextButton(onClick=onDiscard,modifier=Modifier.padding(top=8.dp)){Text("Discard this checkout",color=Color(0xFFD64545),fontSize=9.sp)}
        }
    }
}

@Composable
private fun DiscoveryHeader(query: String, onQueryChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(1100f, 700f)),
            RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
        ).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 28.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("zomeal", color = Color.White, fontSize = 35.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Surface(
                    color = Color.White.copy(alpha = .14f),
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .38f))
                ) {
                    Icon(Icons.Filled.LocationOn, "Change location", tint = Color.White, modifier = Modifier.padding(14.dp).size(24.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Delivering to", color = Color.White.copy(alpha = .9f), fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocationOn, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("751030 · Khandagiri, Bhubaneswar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(19.dp))
                }
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().height(62.dp),
                placeholder = { Text("Search monthly meal providers…", color = Muted) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Muted) },
                trailingIcon = { Icon(Icons.Filled.Tune, "Filters", tint = Ink) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun MarketplaceStatusCard(message:String,isError:Boolean,detail:String?,onRetry:(()->Unit)?=null){
    Surface(
        modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp),
        color=if(isError)Color(0xFFFFF6E5) else Mist,
        shape=RoundedCornerShape(14.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,if(isError)Color(0xFFF1D596) else Border)
    ){
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
            if(isError)Icon(Icons.Outlined.CloudOff,null,tint=Color(0xFF9A6B00),modifier=Modifier.size(19.dp))
            else CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=Brand)
            Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(message,color=Ink,fontSize=11.sp,fontWeight=FontWeight.Bold);detail?.takeIf{it.isNotBlank()}?.let{Text(it,color=Muted,fontSize=8.sp,maxLines=2)}}
            onRetry?.let{TextButton(onClick=it){Text("Retry",color=Brand,fontSize=10.sp,fontWeight=FontWeight.Bold)}}
        }
    }
}

@Composable
private fun DiscoveryAccountDialog(pincode: String, onDismiss: () -> Unit, onLogout: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color.White,
        icon = {
            Surface(color = Mist, shape = CircleShape) {
                Icon(
                    Icons.Outlined.AccountCircle,
                    null,
                    tint = Brand,
                    modifier = Modifier.padding(12.dp).size(28.dp)
                )
            }
        },
        title = { Text("Your profile", color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your verified Zomeal account", color = Muted, fontSize = 12.sp)
                Surface(color = Mist, shape = RoundedCornerShape(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.LocationOn, null, tint = Brand, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Delivery pincode", color = Muted, fontSize = 10.sp)
                            Text(pincode, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Continue browsing", color = Brand, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onLogout) { Text("Sign out", color = Color(0xFFB42318)) }
        }
    )
}

@Composable
private fun ServiceProviderHeader(
    query: String,
    pincode: String,
    providerCount: Int,
    browseMode: Boolean,
    onQueryChange: (String) -> Unit,
    onProfile: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(1000f, 650f)),
                RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
            )
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { back ->
                    IconButton(onClick = back, modifier = Modifier.size(42.dp).background(Color.White.copy(alpha = .14f), CircleShape)) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(9.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("zomeal", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Black)
                    Text("Service Provider List", color = Color.White.copy(alpha = .84f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(
                    onClick = onProfile,
                    modifier = Modifier.background(Color.White.copy(alpha = .14f), CircleShape).size(42.dp)
                ) {
                    Icon(Icons.Outlined.AccountCircle, "Profile", tint = Color.White, modifier = Modifier.size(21.dp))
                }
            }
            Surface(color = Color.White.copy(alpha = .13f), shape = RoundedCornerShape(15.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (browseMode) Icons.Outlined.TravelExplore else Icons.Filled.LocationOn, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (browseMode) "Browsing providers" else "Delivering to", color = Color.White.copy(alpha = .76f), fontSize = 8.sp)
                        Text(if (browseMode) "Popular kitchens near Bhubaneswar" else "$pincode · Khandagiri, Bhubaneswar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Text("Change", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                placeholder = { Text("Search kitchens, dishes or locality", color = Muted, fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(19.dp)) },
                trailingIcon = {
                    Surface(color = Mist, shape = CircleShape) {
                        Icon(Icons.Filled.Tune, "Filters", tint = Brand, modifier = Modifier.padding(8.dp).size(17.dp))
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun AvailabilityBanner(pincode: String, providerCount: Int, browseMode: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        color = if (browseMode) Color(0xFFFFF8E7) else Color(0xFFEDF8E8),
        shape = RoundedCornerShape(17.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (browseMode) Color(0xFFF0DCA6) else Color(0xFFCDE7C3))
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color.White, shape = CircleShape) {
                Icon(
                    if (browseMode) Icons.Outlined.Visibility else Icons.Outlined.CheckCircle,
                    null,
                    tint = if (browseMode) Color(0xFFB67600) else Brand,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (browseMode) "You're exploring Zomeal" else if(providerCount>0) "Great news! $providerCount providers deliver here" else "Service is not available here yet",
                    color = if (browseMode) Color(0xFF735318) else BrandDark,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    if (browseMode) "Browse and save menus. Add a serviceable address to subscribe." else if(providerCount>0) "Verified meal providers are available for pincode $pincode." else "We’ll notify you when verified providers begin serving pincode $pincode.",
                    color = Muted,
                    fontSize = 8.sp,
                    lineHeight = 11.sp
                )
            }
        }
    }
}

@Composable
private fun PromoBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(148.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFF7FCF3),
        shadowElevation = 5.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)
    ) {
        Row {
            Column(
                modifier = Modifier.weight(1.2f).padding(start = 20.dp, top = 18.dp, bottom = 15.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("♨", color = Brand, fontSize = 25.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Monthly meals,", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text("made simple!", color = Brand, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text("Healthy, tasty & affordable\nmeal plans at your doorstep.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            FoodPlateArt(Modifier.weight(.85f).fillMaxHeight())
        }
    }
}

@Composable
private fun FoodPlateArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * .62f, size.height * .55f)
        drawCircle(Color(0xFFC99B63), size.minDimension * .51f, center)
        drawCircle(Color(0xFFF9F0DF), size.minDimension * .43f, center)
        drawCircle(Color.White, size.minDimension * .23f, Offset(center.x - 17, center.y - 11))
        drawCircle(Color(0xFFDA7A2C), size.minDimension * .17f, Offset(center.x + 28, center.y - 20))
        drawCircle(Color(0xFF6FA64B), size.minDimension * .14f, Offset(center.x + 18, center.y + 31))
        drawCircle(Color(0xFFFFB13B), size.minDimension * .08f, Offset(center.x - 27, center.y + 29))
        drawArc(Color.White.copy(alpha = .8f), 190f, 115f, false, Offset(center.x - 45, center.y - 47), androidx.compose.ui.geometry.Size(90f, 86f), style = Stroke(3.dp.toPx()))
    }
}

@Composable
private fun ProviderSectionHeader(count: Int, pincode: String, browseMode: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Service providers", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
            Text(
                if (count == 0) "No providers match your filters" else if (browseMode) "$count kitchens available to explore" else "$count providers deliver to $pincode",
                color = Muted,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun ProviderFilterPanel(
    selected: DietFilter,
    sortByRating: Boolean,
    onFilter: (DietFilter) -> Unit,
    onSort: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                DietFilter.entries.take(3).forEach { option ->
                    DietChip(option, option == selected, Modifier.weight(1f)) { onFilter(option) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                DietFilter.entries.drop(3).forEach { option ->
                    DietChip(option, option == selected, Modifier.weight(1f)) { onFilter(option) }
                }
                Surface(
                    modifier = Modifier.weight(1f).clickable(onClick = onSort),
                    color = if (sortByRating) Color(0xFFE5F5E7) else Mist,
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (sortByRating) Brand.copy(alpha = .45f) else Border)
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(Icons.Filled.SwapVert, null, tint = Brand, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (sortByRating) "Top first" else "Sort", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun DietChip(option: DietFilter, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) Brand else Mist,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (option.emoji.isNotEmpty()) {
                Text(option.emoji, color = if (option == DietFilter.NON_VEG) Color(0xFFC94C2D) else if (option == DietFilter.TOP) Color(0xFFFFB400) else Color(0xFF55B627), fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(option.label, color = if (selected) Color.White else Ink, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ProviderCard(provider: Provider, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = Color.White,
        shadowElevation = 5.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0F3F1))
    ) {
        Row(Modifier.height(168.dp).padding(10.dp)) {
            Box(Modifier.width(124.dp).fillMaxHeight().clip(RoundedCornerShape(17.dp)).background(provider.tint)) {
                ApprovedProviderImage(provider, Modifier.fillMaxSize())
                Surface(Modifier.padding(8.dp), color = Color.White.copy(alpha = .92f), shape = RoundedCornerShape(14.dp)) {
                    Text(provider.category.label, color = BrandDark, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 13.dp, top = 3.dp, bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocationOn, null, tint = Muted, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(provider.locality, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    RatingPill(provider.rating, provider.reviews)
                }
                Spacer(Modifier.height(9.dp))
                Surface(color = Mist, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (provider.category == DietFilter.NON_VEG) "●" else "●", color = if (provider.category == DietFilter.NON_VEG) Color(0xFFC94C2D) else Color(0xFF55B627), fontSize = 10.sp)
                        Spacer(Modifier.width(5.dp))
                        Text(provider.diet, color = BrandDark, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Restaurant, null, tint = Muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when (provider.packageKind?.uppercase()) {
                                "BOTH", "LUNCH_DINNER" -> "Lunch + Dinner"
                                "DINNER" -> "Dinner plan"
                                else -> "Lunch plan"
                            },
                            color = Muted,
                            fontSize = 10.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("₹${"%,d".format(provider.price)}", color = BrandDark, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Text("/ month", color = Muted, fontSize = 9.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = Muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("30 Days Plan", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Button(
                        onClick = onClick,
                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp),
                        modifier = Modifier.height(31.dp),
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand)
                    ) { Text("View Details", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun ProviderFoodArt(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width * .52f, size.height * .58f)
        drawCircle(Color(0xFFB88A54), size.minDimension * .47f, c)
        drawCircle(Color(0xFFF5E7CF), size.minDimension * .4f, c)
        val bowls = listOf(
            Offset(c.x - 28, c.y - 29) to Color(0xFFF2D89A),
            Offset(c.x + 27, c.y - 26) to accent,
            Offset(c.x - 31, c.y + 27) to Color(0xFF69A04B),
            Offset(c.x + 29, c.y + 28) to Color(0xFFD9A52B)
        )
        bowls.forEach { (point, color) ->
            drawCircle(Color(0xFF614A36), size.minDimension * .15f, point)
            drawCircle(color, size.minDimension * .12f, point)
        }
        drawCircle(Color.White, size.minDimension * .15f, c)
    }
}

@Composable
private fun ApprovedProviderImage(provider: Provider, modifier: Modifier = Modifier) {
    ApprovedMediaImage(provider.primaryPhotoPath, provider.name, modifier) { ProviderFoodArt(provider.accent, Modifier.fillMaxSize()) }
}

@Composable
private fun ApprovedMediaImage(path:String,description:String,modifier:Modifier=Modifier,fallback:@Composable BoxScope.()->Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { SupabaseCustomerRepository(context) }
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) { repository.approvedMedia(path) { bitmap = it } }
    val approved = bitmap
    Box(modifier) {
        if (approved != null) Image(approved.asImageBitmap(), description, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else fallback()
    }
}

@Composable
private fun RatingPill(rating: Double, reviews: Int) {
    Surface(color = Mist, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (reviews > 0) {
                Icon(Icons.Filled.Star, null, tint = Color(0xFFFFB400), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(rating.toString(), color = BrandDark, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(" ($reviews)", color = Muted, fontSize = 8.sp)
            } else {
                Text("New", color = BrandDark, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun EmptyProviders(onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = Mist, shape = CircleShape) { Icon(Icons.Outlined.Storefront, null, tint = Brand, modifier = Modifier.padding(20.dp).size(34.dp)) }
        Spacer(Modifier.height(14.dp))
        Text("No providers found", color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Try another search or dietary filter.", color = Muted, fontSize = 13.sp)
        TextButton(onClick = onClear) { Text("Clear filters", color = Brand, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ZomealBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple("Home", Icons.Filled.Home, Icons.Outlined.Home),
        Triple("My Plans", Icons.Filled.TakeoutDining, Icons.Outlined.TakeoutDining),
        Triple("Orders", Icons.Filled.ReceiptLong, Icons.Outlined.ReceiptLong),
        Triple("Profile", Icons.Filled.Person, Icons.Outlined.Person)
    )
    Box(Modifier.fillMaxWidth().navigationBarsPadding().background(Color.White)) {
        NavigationBar(
            modifier = Modifier.fillMaxWidth().height(66.dp),
            containerColor = Color.White,
            tonalElevation = 8.dp,
            windowInsets = WindowInsets(0, 0, 0, 0)
        ) {
            items.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    icon = { Icon(if (selected == index) item.second else item.third, item.first, modifier = Modifier.size(20.dp)) },
                    label = { Text(item.first, fontSize = 8.sp, maxLines = 1) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Brand, selectedTextColor = Brand,
                        indicatorColor = Mist, unselectedIconColor = Muted, unselectedTextColor = Muted
                    )
                )
            }
        }
    }
}

private data class MealPackage(
    val id: String,
    val kind: String,
    val title: String,
    val meals: String,
    val price: String,
    val icon: ImageVector,
    val popular: Boolean = false
)

@Composable
private fun ProviderDetailsScreen(provider: Provider, onBack: () -> Unit, onActivated: () -> Unit, subscriptionView:Boolean=false, changeProviderMode:Boolean=false) {
    val packages = remember(provider) {
        if(provider.isLive) provider.packages.map { packageRecord -> MealPackage(
            packageRecord.id,
            packageRecord.kind,
            packageRecord.name.ifBlank { when(packageRecord.kind){"LUNCH_ONLY"->"Lunch Only";"DINNER_ONLY"->"Dinner Only";else->"Lunch & Dinner"} },
            if(packageRecord.kind=="LUNCH_AND_DINNER")"2 meals / day" else "1 meal / day",
            "₹${"%,d".format(packageRecord.pricePaise/100)}",
            when(packageRecord.kind){"DINNER_ONLY"->Icons.Outlined.DarkMode;"LUNCH_AND_DINNER"->Icons.Outlined.WbTwilight;else->Icons.Outlined.LightMode},
            packageRecord.kind=="LUNCH_AND_DINNER"
        ) } else listOf(
            MealPackage("lunch", "LUNCH_ONLY", "Lunch Only", "1 meal / day", "₹3,499", Icons.Outlined.LightMode),
            MealPackage("both", "LUNCH_AND_DINNER", "Lunch & Dinner", "2 meals / day", "₹6,499", Icons.Outlined.WbTwilight, true),
            MealPackage("dinner", "DINNER_ONLY", "Dinner Only", "1 meal / day", "₹3,299", Icons.Outlined.DarkMode)
        )
    }
    var selectedPackage by remember(provider) { mutableIntStateOf(packages.indexOfFirst { it.kind=="LUNCH_AND_DINNER" }.takeIf { it>=0 } ?: 0) }
    var menuPackage by remember { mutableStateOf<MealPackage?>(null) }

    BackHandler(enabled = menuPackage != null) { menuPackage = null }
    menuPackage?.let { plan ->
        WeeklyMenuScreen(provider = provider, plan = plan, onBack = { menuPackage = null }, onGoHome = onActivated, changeProviderMode = changeProviderMode)
        return
    }

    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            if(!subscriptionView)
            Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 10.dp) {
                Button(
                    onClick = { menuPackage = packages[selectedPackage] },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand)
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Set Your Weekly Menu", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Customize your meals for the week", fontSize = 10.sp, color = Color.White.copy(alpha = .82f))
                    }
                    Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item { ProviderDetailsTopBar(onBack) }
            item { ProviderIdentity(provider) }
            item { TrustSummary(provider) }
            if(subscriptionView){
                item { CurrentPlanStaticCard(packages.firstOrNull{it.id==provider.packageId}?:packages.getOrNull(selectedPackage)) }
            }else{
                item { PackageHeader() }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        packages.forEachIndexed { index, mealPackage ->
                            PackageCard(
                                mealPackage = mealPackage,
                                selected = selectedPackage == index,
                                onSelect = { selectedPackage = index },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            item { BenefitsStrip() }
            if(provider.isLive) item { LiveWeeklyMenuPreview(provider,if(subscriptionView)provider.packageKind else null) }
            item { AboutProvider(provider) }
            if(provider.kitchenPhotoPath.isNotBlank()) item { ProviderKitchenCard(provider) }
            item { QualityBadges() }
            item { DeliveryCard(provider) }
        }
    }
}

@Composable private fun CurrentPlanStaticCard(plan:MealPackage?){
    Surface(Modifier.fillMaxWidth().padding(horizontal=18.dp),color=Mist,shape=RoundedCornerShape(20.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color.White),contentAlignment=Alignment.Center){Icon(plan?.icon?:Icons.Outlined.Restaurant,null,tint=Brand,modifier=Modifier.size(23.dp))}
            Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("Your current package",color=BrandDark,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(plan?.title?:"Active meal plan",color=Ink,fontSize=16.sp,fontWeight=FontWeight.ExtraBold);Text("${plan?.meals?:"Monthly meals"} · ${plan?.price.orEmpty()} / month",color=Muted,fontSize=10.sp)}
            Surface(color=Color(0xFFE0F2E4),shape=RoundedCornerShape(12.dp)){Text("Active",Modifier.padding(horizontal=10.dp,vertical=6.dp),color=BrandDark,fontSize=9.sp,fontWeight=FontWeight.Bold)}
        }
    }
}

@Composable
private fun ProviderDetailsTopBar(onBack: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(74.dp).background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(950f, 300f))
        )
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(start = 10.dp).size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).align(Alignment.CenterStart)
        ) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Text("zomeal", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center))
        Row(Modifier.padding(end = 10.dp).align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallHeaderAction(Icons.Outlined.Share, "Share")
            SmallHeaderAction(Icons.Outlined.FavoriteBorder, "Favorite")
        }
    }
}

@Composable
private fun SmallHeaderAction(icon: ImageVector, label: String) {
    IconButton(onClick = { }, modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f))) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun ProviderIdentity(provider: Provider) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(provider.name, color = Ink, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                Text("Verified home-style meal provider", color = Muted, fontSize = 9.sp)
            }
            RatingPill(provider.rating, provider.reviews)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Mist, shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Eco, null, tint = Brand, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(provider.diet, color = BrandDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(1.dp).height(22.dp).background(Border))
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.LocationOn, null, tint = Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(provider.locality, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TrustSummary(provider: Provider) {
    val approvedMenuRows = remember(provider.weeklyMenu) { runCatching { JSONArray(provider.weeklyMenu).length() }.getOrDefault(0) }
    val stats = listOf(
        Triple(Icons.Outlined.Inventory2, provider.packages.size.toString(), "Active packages"),
        Triple(Icons.Outlined.RestaurantMenu, approvedMenuRows.toString(), "Weekly meal slots"),
        Triple(Icons.Outlined.Eco, provider.diet, "Food category"),
        Triple(Icons.Outlined.VerifiedUser, "Verified", "By Zomeal")
    )
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            stats.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    pair.forEach { item ->
                        Surface(Modifier.weight(1f), color = Color.White.copy(alpha = .72f), shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(item.first, null, tint = Brand, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(item.second, color = BrandDark, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.third, color = Muted, fontSize = 8.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackageHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Choose Your Package", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("Select the meal plan that suits you best", color = Muted, fontSize = 12.sp)
        }
        Surface(color = Mist, shape = RoundedCornerShape(18.dp)) {
            Text("Customizable menu", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun PackageCard(mealPackage: MealPackage, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(196.dp).clickable(onClick = onSelect),
        color = if (selected) Color(0xFFF6FBF7) else Color.White,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Brand else Border),
        shadowElevation = if (selected) 4.dp else 1.dp
    ) {
        Box {
            if (mealPackage.popular) {
                Surface(color = BrandDark, shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp), modifier = Modifier.align(Alignment.TopCenter)) {
                    Text("Most Popular", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(if (mealPackage.popular) 20.dp else 7.dp))
                Icon(mealPackage.icon, null, tint = if (mealPackage.title.contains("Dinner")) Color(0xFF34547A) else Color(0xFFFFB300), modifier = Modifier.size(23.dp))
                Spacer(Modifier.height(7.dp))
                Text(mealPackage.title, color = if (selected) BrandDark else Ink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(mealPackage.meals, color = Muted, fontSize = 8.sp)
                Spacer(Modifier.height(9.dp))
                Text(mealPackage.price, color = BrandDark, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text("/ month", color = Muted, fontSize = 8.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onSelect,
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Brand else Color.Transparent, contentColor = if (selected) Color.White else BrandDark),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Brand)
                ) { Text(if (selected) "Selected" else "Select", fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun LiveWeeklyMenuPreview(provider:Provider,packageKind:String?){
    val slots=remember(provider.weeklyMenu,packageKind){
        buildList<Pair<String,ProviderMealSlot>>{for(day in 0..6){if(packageKind!="DINNER_ONLY")add("${listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")[day]} · Lunch" to providerMealSlot(provider.weeklyMenu,day,"LUNCH"));if(packageKind!="LUNCH_ONLY")add("${listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")[day]} · Dinner" to providerMealSlot(provider.weeklyMenu,day,"DINNER"))}}.filter{it.second.mainCourses.isNotEmpty()||it.second.carbs.isNotEmpty()||it.second.included.isNotEmpty()}
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal=18.dp),color=Color.White,shape=RoundedCornerShape(18.dp),border=androidx.compose.foundation.BorderStroke(1.dp,Border)){
        Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Seven-day main courses",color=Ink,fontSize=16.sp,fontWeight=FontWeight.ExtraBold);Text("Approved lunch and dinner choices available in this package",color=Muted,fontSize=9.sp);if(slots.isEmpty())Text("Menu details are being updated.",color=Muted,fontSize=10.sp) else slots.forEach{(label,menu)->Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Mist.copy(alpha=.55f)).padding(10.dp)){Text(label,color=BrandDark,fontSize=11.sp,fontWeight=FontWeight.ExtraBold);Spacer(Modifier.height(7.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){menu.mainCourses.take(2).forEach{course->Column(Modifier.weight(1f)){Box(Modifier.fillMaxWidth().height(82.dp).clip(RoundedCornerShape(11.dp))){ApprovedDishImage(if(course.photoPath.isBlank())course.copy(photoPath=provider.mealPhotoPath) else course,Modifier.fillMaxSize())};Text(course.name,color=Ink,fontSize=10.sp,fontWeight=FontWeight.Bold,maxLines=2,modifier=Modifier.padding(top=5.dp));Text(course.dietaryType.replace('_',' '),color=Muted,fontSize=7.sp)}}};val sides=(menu.carbs+menu.included).joinToString(" · ");if(sides.isNotBlank())Text("Included: $sides",color=Muted,fontSize=8.sp,modifier=Modifier.padding(top=6.dp))}}}
    }
}

@Composable
private fun BenefitsStrip() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Benefit(Icons.Outlined.LocalShipping, "Free delivery")
            Benefit(Icons.Outlined.AccountBalanceWallet, "No joining fee")
            Benefit(Icons.Outlined.PauseCircle, "Pause anytime")
        }
    }
}

@Composable
private fun RowScope.Benefit(icon: ImageVector, label: String) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = Brand, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun AboutProvider(provider: Provider) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.25f)) {
            Text("About ${provider.name}", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text(provider.description.takeUnless{it.isBlank()||it.equals("null",true)}?:"This provider has not added a public description yet.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(.75f).height(104.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFDCEAD8))) {
            ApprovedMediaImage(provider.primaryPhotoPath, provider.name, Modifier.fillMaxSize()) { ProviderPortrait(Modifier.fillMaxSize()) }
        }
    }
}

@Composable
private fun ProviderKitchenCard(provider:Provider){
    Surface(Modifier.fillMaxWidth().padding(horizontal=18.dp),color=Mist,shape=RoundedCornerShape(18.dp)){
        Row(Modifier.height(112.dp),verticalAlignment=Alignment.CenterVertically){
            ApprovedMediaImage(provider.kitchenPhotoPath,"${provider.name} kitchen",Modifier.width(150.dp).fillMaxHeight().clip(RoundedCornerShape(topStart=18.dp,bottomStart=18.dp))){
                Box(Modifier.fillMaxSize().background(provider.tint),contentAlignment=Alignment.Center){Icon(Icons.Outlined.SoupKitchen,null,tint=Brand,modifier=Modifier.size(38.dp))}
            }
            Column(Modifier.weight(1f).padding(14.dp)){Text("Inside the kitchen",color=Ink,fontSize=15.sp,fontWeight=FontWeight.ExtraBold);Spacer(Modifier.height(5.dp));Text("An approved photo shared by this provider and verified by Zomeal.",color=Muted,fontSize=10.sp,lineHeight=15.sp)}
        }
    }
}

@Composable
private fun ProviderPortrait(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(Color(0xFFFFC9A5), size.minDimension * .2f, Offset(size.width * .52f, size.height * .33f))
        drawCircle(Color(0xFF1F2D23), size.minDimension * .23f, Offset(size.width * .52f, size.height * .27f))
        drawCircle(Color(0xFFFFC9A5), size.minDimension * .19f, Offset(size.width * .52f, size.height * .34f))
        drawArc(Color(0xFF176A44), 190f, 160f, true, Offset(size.width * .2f, size.height * .48f), androidx.compose.ui.geometry.Size(size.width * .65f, size.height * .75f))
        drawCircle(Color(0xFFB82C2C), 2.5.dp.toPx(), Offset(size.width * .52f, size.height * .29f))
    }
}

@Composable
private fun QualityBadges() {
    val badges = listOf(
        Triple(Icons.Outlined.HealthAndSafety, "Hygienic", "Home kitchen"),
        Triple(Icons.Outlined.Eco, "Fresh", "Ingredients"),
        Triple(Icons.Outlined.Science, "No preservatives", "No ajinomoto"),
        Triple(Icons.Outlined.SoupKitchen, "Daily", "Variety")
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        badges.chunked(2).forEach { rowBadges ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowBadges.forEach { badge ->
                    Surface(modifier = Modifier.weight(1f), color = Mist, shape = RoundedCornerShape(15.dp)) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(badge.first, null, tint = Brand, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(badge.second, color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(badge.third, color = Ink, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryCard(provider:Provider) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.height(112.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.2f)) {
                Text("Meal Delivery", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(7.dp))
                Text("Meals are delivered daily in stainless-steel tiffins for freshness and hygiene.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
            Box(Modifier.weight(.8f).fillMaxHeight().clip(RoundedCornerShape(topEnd=18.dp,bottomEnd=18.dp))){
                ApprovedMediaImage(provider.mealPhotoPath,"${provider.name} complete meal",Modifier.fillMaxSize()){TiffinArt(Modifier.fillMaxSize())}
            }
        }
    }
}

@Composable
private fun TiffinArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val silver = Color(0xFFB8C1BD)
        val dark = Color(0xFF68716D)
        repeat(3) { index ->
            val top = size.height * (.2f + index * .2f)
            drawRoundRect(silver, Offset(size.width * .2f, top), androidx.compose.ui.geometry.Size(size.width * .58f, size.height * .22f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f))
            drawLine(dark, Offset(size.width * .22f, top + 5), Offset(size.width * .76f, top + 5), 2.dp.toPx())
        }
        drawArc(dark, 185f, 170f, false, Offset(size.width * .28f, size.height * .02f), androidx.compose.ui.geometry.Size(size.width * .42f, size.height * .35f), style = Stroke(3.dp.toPx()))
    }
}

private data class MenuChoice(
    val name: String,
    val base: Color,
    val garnish: Color,
    val dietaryType: String = "",
    val isDefault: Boolean = false,
    val photoPath: String = "",
    val description: String = "",
    val id: String = ""
)

private data class ProviderMealSlot(
    val mainCourses: List<MenuChoice>,
    val carbs: List<String>,
    val included: List<String>
)

private fun providerMealSlot(rawMenu: String, dayIndex: Int, slot: String): ProviderMealSlot {
    return runCatching {
        val rows = JSONArray(rawMenu)
        var matchingItems = JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.optInt("day_of_week") == dayIndex + 1 && row.optString("meal_slot").equals(slot, true)) {
                matchingItems = row.optJSONArray("items") ?: JSONArray()
                break
            }
        }
        val mains = mutableListOf<MenuChoice>()
        val carbs = mutableListOf<String>()
        val included = mutableListOf<String>()
        val colors = listOf(
            Color(0xFFD96A2B) to Color(0xFFF4D184), Color(0xFFA84B2E) to Color(0xFFE8B94D),
            Color(0xFFB75B35) to Color(0xFF4E9B51), Color(0xFFC85B35) to Color(0xFFF3C36A)
        )
        for (itemIndex in 0 until matchingItems.length()) {
            val item = matchingItems.getJSONObject(itemIndex)
            val name = item.optString("name").trim()
            if (name.isBlank()) continue
            when (item.optString("category").uppercase()) {
                "MAIN_COURSE" -> {
                    val palette = colors[mains.size % colors.size]
                    mains += MenuChoice(
                        name,
                        palette.first,
                        palette.second,
                        item.optString("dietary_type").trim().uppercase().replace('-', '_'),
                        item.optBoolean("is_default"),
                        item.optString("photo_path").trim().takeUnless { it.equals("null", true) || it.equals("undefined", true) }.orEmpty(),
                        item.optString("description"),
                        item.optString("id")
                    )
                }
                "CARB" -> carbs += name
                else -> included += name
            }
        }
        ProviderMealSlot(mains.distinctBy { it.name }, carbs.distinct(), included.distinct())
    }.getOrDefault(ProviderMealSlot(emptyList(), emptyList(), emptyList()))
}

private fun ProviderMealSlot.preferredMainCourse(preference: String): MenuChoice? {
    val normalizedPreference = preference.trim().uppercase().replace('-', '_')
    if (normalizedPreference == "DEFAULT") {
        return mainCourses.firstOrNull { it.isDefault } ?: mainCourses.firstOrNull()
    }
    val acceptedTypes = when (normalizedPreference) {
        "NON_VEG" -> setOf("NON_VEG", "NON_VEGETARIAN")
        "VEGAN" -> setOf("VEGAN")
        else -> setOf("VEG", "VEGETARIAN", "PURE_VEG", "VEGAN")
    }
    val matching = mainCourses.filter { it.dietaryType in acceptedTypes }
    return matching.firstOrNull { it.isDefault }
        ?: matching.firstOrNull()
        ?: mainCourses.firstOrNull { it.isDefault }
        ?: mainCourses.firstOrNull()
}

private val lunchChoices = listOf(
    MenuChoice("Paneer Butter Masala", Color(0xFFD96A2B), Color(0xFFF4D184)),
    MenuChoice("Dal Tadka", Color(0xFFE4AD2E), Color(0xFF7A9B3A)),
    MenuChoice("Chicken Curry", Color(0xFFB94C32), Color(0xFFF0A34E)),
    MenuChoice("Fish Masala", Color(0xFFC85B35), Color(0xFFF3C36A))
)

private val dinnerChoices = listOf(
    MenuChoice("Seasonal Mix Veg", Color(0xFFB75B35), Color(0xFF4E9B51)),
    MenuChoice("Chana Masala", Color(0xFFA84B2E), Color(0xFFE8B94D)),
    MenuChoice("Egg Tadka", Color(0xFFC94E2F), Color(0xFFFFD56A)),
    MenuChoice("Chicken Masala", Color(0xFF9F3F2D), Color(0xFFF09A44))
)

@Composable
private fun WeeklyMenuScreen(provider: Provider, plan: MealPackage, onBack: () -> Unit, onGoHome: () -> Unit, changeProviderMode:Boolean=false) {
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val dates = listOf("21", "22", "23", "24", "25", "26", "27")
    var selectedDay by remember { mutableIntStateOf(0) }
    val providerLunchMenus = remember(provider.weeklyMenu) { days.indices.associateWith { providerMealSlot(provider.weeklyMenu, it, "LUNCH") } }
    val providerDinnerMenus = remember(provider.weeklyMenu) { days.indices.associateWith { providerMealSlot(provider.weeklyMenu, it, "DINNER") } }
    val lunchSelections = remember(provider.weeklyMenu) { mutableStateMapOf<Int, String>().apply { days.indices.forEach { day -> put(day, providerLunchMenus[day]?.preferredMainCourse("DEFAULT")?.name.orEmpty()) } } }
    val dinnerSelections = remember(provider.weeklyMenu) { mutableStateMapOf<Int, String>().apply { days.indices.forEach { day -> put(day, providerDinnerMenus[day]?.preferredMainCourse("DEFAULT")?.name.orEmpty()) } } }
    val lunchCarbs = remember(provider.weeklyMenu) { mutableStateMapOf<Int, String>().apply { days.indices.forEach { day -> put(day, providerLunchMenus[day]?.carbs?.firstOrNull().orEmpty()) } } }
    val dinnerCarbs = remember(provider.weeklyMenu) { mutableStateMapOf<Int, String>().apply { days.indices.forEach { day -> put(day, providerDinnerMenus[day]?.carbs?.firstOrNull().orEmpty()) } } }
    val submittedDays = remember { mutableStateMapOf<Int, Boolean>().apply { days.indices.forEach { put(it, false) } } }
    var quickPreference by remember { mutableStateOf<String?>(null) }
    val showLunch = plan.kind != "DINNER_ONLY"
    val showDinner = plan.kind != "LUNCH_ONLY"
    var showReview by remember { mutableStateOf(false) }

    LaunchedEffect(plan.kind) { CustomerMenuStore.packageKind = plan.kind }

    BackHandler(enabled = showReview) { showReview = false }
    if (showReview) {
        ReviewPlanScreen(
            provider = provider,
            plan = plan,
            lunchSelections = lunchSelections,
            dinnerSelections = dinnerSelections,
            onBack = { showReview = false },
            onGoHome = onGoHome,
            changeProviderMode = changeProviderMode
        )
        return
    }

    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 10.dp) {
                Button(
                    onClick = { showReview = true },
                    enabled = days.indices.all { submittedDays[it] == true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandDark,
                        disabledContainerColor = Color(0xFFDDE7DD),
                        disabledContentColor = Color(0xFF789078)
                    )
                ) {
                    Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (days.indices.all { submittedDays[it] == true }) "Review 7-Day Menu" else "Submit all 7 days to continue",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { WeeklyMenuHeader(onBack, submittedDays.values.count { it }) }
            item {
                QuickMenuSetup(
                    selected = quickPreference,
                    onVeg = {
                        quickPreference = "Veg"
                        days.indices.forEach { index ->
                            providerLunchMenus[index]?.let { menu -> lunchSelections[index] = menu.preferredMainCourse("VEG")?.name.orEmpty(); lunchCarbs[index] = menu.carbs.firstOrNull().orEmpty() }
                            providerDinnerMenus[index]?.let { menu -> dinnerSelections[index] = menu.preferredMainCourse("VEG")?.name.orEmpty(); dinnerCarbs[index] = menu.carbs.firstOrNull().orEmpty() }
                            if (showLunch) CustomerMenuStore.lunches[index] = SavedCustomerMeal(lunchSelections[index].orEmpty(), lunchCarbs[index].orEmpty())
                            if (showDinner) CustomerMenuStore.dinners[index] = SavedCustomerMeal(dinnerSelections[index].orEmpty(), dinnerCarbs[index].orEmpty())
                            submittedDays[index] = true
                        }
                    },
                    onNonVeg = {
                        quickPreference = "Non-Veg"
                        days.indices.forEach { index ->
                            providerLunchMenus[index]?.let { menu -> lunchSelections[index] = menu.preferredMainCourse("NON_VEG")?.name.orEmpty(); lunchCarbs[index] = menu.carbs.firstOrNull().orEmpty() }
                            providerDinnerMenus[index]?.let { menu -> dinnerSelections[index] = menu.preferredMainCourse("NON_VEG")?.name.orEmpty(); dinnerCarbs[index] = menu.carbs.firstOrNull().orEmpty() }
                            if (showLunch) CustomerMenuStore.lunches[index] = SavedCustomerMeal(lunchSelections[index].orEmpty(), lunchCarbs[index].orEmpty())
                            if (showDinner) CustomerMenuStore.dinners[index] = SavedCustomerMeal(dinnerSelections[index].orEmpty(), dinnerCarbs[index].orEmpty())
                            submittedDays[index] = true
                        }
                    }
                )
            }
            item {
                DaySelectorGrid(days, dates, selectedDay, submittedDays) { selectedDay = it }
            }
            if (showLunch) {
                item {
                    val actualMenu = providerLunchMenus[selectedDay] ?: ProviderMealSlot(emptyList(), emptyList(), emptyList())
                    MealSlotEditor(
                        title = "Lunch",
                        icon = Icons.Outlined.LightMode,
                        accent = Color(0xFF5B873E),
                        choices = actualMenu.mainCourses,
                        selectedChoice = lunchSelections[selectedDay].orEmpty(),
                        onChoice = { lunchSelections[selectedDay] = it },
                        carbOptions = actualMenu.carbs,
                        selectedCarb = lunchCarbs[selectedDay].orEmpty(),
                        onCarb = { lunchCarbs[selectedDay] = it },
                        included = actualMenu.included
                    )
                }
            }
            if (showDinner) {
                item {
                    val actualMenu = providerDinnerMenus[selectedDay] ?: ProviderMealSlot(emptyList(), emptyList(), emptyList())
                    MealSlotEditor(
                        title = "Dinner",
                        icon = Icons.Outlined.DarkMode,
                        accent = Color(0xFF8050B7),
                        choices = actualMenu.mainCourses,
                        selectedChoice = dinnerSelections[selectedDay].orEmpty(),
                        onChoice = { dinnerSelections[selectedDay] = it },
                        carbOptions = actualMenu.carbs,
                        selectedCarb = dinnerCarbs[selectedDay].orEmpty(),
                        onCarb = { dinnerCarbs[selectedDay] = it },
                        included = actualMenu.included
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        if (showLunch) CustomerMenuStore.lunches[selectedDay] = SavedCustomerMeal(lunchSelections[selectedDay].orEmpty(), lunchCarbs[selectedDay].orEmpty())
                        if (showDinner) CustomerMenuStore.dinners[selectedDay] = SavedCustomerMeal(dinnerSelections[selectedDay].orEmpty(), dinnerCarbs[selectedDay].orEmpty())
                        submittedDays[selectedDay] = true
                        quickPreference = null
                        if (selectedDay < days.lastIndex) selectedDay += 1
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark)
                ) {
                    Icon(
                        if (submittedDays[selectedDay] == true) Icons.Filled.CheckCircle else Icons.Outlined.Save,
                        null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (submittedDays[selectedDay] == true) "Update ${days[selectedDay]}'s Menu" else "Submit ${days[selectedDay]}'s Menu",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklyMenuHeader(onBack: () -> Unit, submittedCount: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Ink, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Set Your Weekly Menu", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Text("$submittedCount of 7 days submitted", color = Muted, fontSize = 11.sp)
        }
        Icon(Icons.Outlined.CalendarMonth, null, tint = BrandDark, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DayCard(day: String, date: String, selected: Boolean, submitted: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(58.dp).clickable(onClick = onClick),
        color = if (selected) Color(0xFF668F48) else Color.White,
        contentColor = if (selected) Color.White else Muted,
        shape = RoundedCornerShape(16.dp),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Border),
        shadowElevation = if (selected) 3.dp else 0.dp
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(day, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                if (submitted) {
                    Spacer(Modifier.width(3.dp))
                    Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(10.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(date, fontSize = 10.sp)
        }
    }
}

@Composable
private fun QuickMenuSetup(selected: String?, onVeg: () -> Unit, onNonVeg: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = Color(0xFFF4F8F2),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDCE8D8))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Set all 7 days instantly", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text("Choose a preference and Zomeal will create a balanced weekly menu. You can edit any day later.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(
                    onClick = onVeg,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(if (selected == "Veg") 2.dp else 1.dp, BrandDark),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected == "Veg") BrandDark else Color.White, contentColor = if (selected == "Veg") Color.White else BrandDark)
                ) {
                    Icon(Icons.Outlined.Eco, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Set All Veg", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onNonVeg,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(if (selected == "Non-Veg") 2.dp else 1.dp, Color(0xFFB85A38)),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected == "Non-Veg") Color(0xFFB85A38) else Color.White, contentColor = if (selected == "Non-Veg") Color.White else Color(0xFF9A472E))
                ) {
                    Icon(Icons.Outlined.Restaurant, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Set All Non-Veg", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (selected != null) {
                Text("All seven days are set to $selected. Review or customize below.", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DaySelectorGrid(
    days: List<String>,
    dates: List<String>,
    selectedDay: Int,
    submittedDays: Map<Int, Boolean>,
    onSelect: (Int) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose a day", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..3).forEach { index ->
                DayCard(days[index], dates[index], selectedDay == index, submittedDays[index] == true, Modifier.weight(1f)) { onSelect(index) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (4..6).forEach { index ->
                DayCard(days[index], dates[index], selectedDay == index, submittedDays[index] == true, Modifier.weight(1f)) { onSelect(index) }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MealSlotEditor(
    title: String,
    icon: ImageVector,
    accent: Color,
    choices: List<MenuChoice>,
    selectedChoice: String,
    onChoice: (String) -> Unit,
    carbOptions: List<String>,
    selectedCarb: String,
    onCarb: (String) -> Unit,
    included: List<String>
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .18f)),
        shadowElevation = 2.dp
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(accent.copy(alpha = .08f)).padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = accent, shape = CircleShape) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.padding(8.dp).size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(title, color = accent, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text("Customizable", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Outlined.Edit, null, tint = accent, modifier = Modifier.size(15.dp))
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("Main course  ·  Choose 1", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (choices.isEmpty()) {
                    Text("This provider has not approved a main-course choice for this meal yet.", color = Muted, fontSize = 10.sp)
                }
                choices.chunked(2).forEach { rowChoices ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowChoices.forEach { choice ->
                            MenuChoiceCard(
                                choice = choice,
                                selected = selectedChoice == choice.name,
                                accent = accent,
                                onClick = { onChoice(choice.name) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowChoices.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text("Carb  ·  Choose 1", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (carbOptions.isEmpty()) Text("No separate carb choice", color = Muted, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    carbOptions.forEach { carb ->
                        FilterChip(
                            selected = selectedCarb == carb,
                            onClick = { onCarb(carb) },
                            label = { Text(carb, fontSize = 11.sp) },
                            leadingIcon = if (selectedCarb == carb) ({ Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(14.dp)) }) else null,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = .12f), selectedLabelColor = accent),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selectedCarb == carb, borderColor = Border, selectedBorderColor = accent)
                        )
                    }
                }
                Text("Included · Non-changeable", color = accent, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                if (included.isEmpty()) Text("No additional fixed items listed", color = Muted, fontSize = 10.sp)
                included.chunked(2).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        rowItems.forEach { item -> IncludedSide(item, accent, Modifier.weight(1f)) }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuChoiceCard(choice: MenuChoice, selected: Boolean, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(132.dp).clickable(onClick = onClick),
        color = if (selected) accent.copy(alpha = .05f) else Color.White,
        shape = RoundedCornerShape(15.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else Border)
    ) {
        Box {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                ApprovedDishImage(choice, Modifier.fillMaxWidth().weight(1f))
                Text(choice.name, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (choice.dietaryType.isNotBlank()) {
                val label = when (choice.dietaryType) {
                    "NON_VEG", "NON_VEGETARIAN" -> "Non-Veg"
                    "VEGAN" -> "Vegan"
                    else -> "Veg"
                }
                val typeColor = if (label == "Non-Veg") Color(0xFFB64A31) else BrandDark
                Surface(color = Color.White.copy(alpha = .94f), shape = RoundedCornerShape(7.dp), modifier = Modifier.padding(7.dp).align(Alignment.TopStart)) {
                    Text(label, color = typeColor, fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
            if (selected) {
                Surface(color = accent, shape = CircleShape, modifier = Modifier.padding(7.dp).align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.padding(4.dp).size(12.dp))
                }
            }
        }
    }
}

@Composable
private fun DishArt(choice: MenuChoice, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * .5f, size.height * .55f)
        drawOval(Color(0xFFD8D7D2), Offset(size.width * .16f, size.height * .14f), androidx.compose.ui.geometry.Size(size.width * .68f, size.height * .7f))
        drawOval(choice.base, Offset(size.width * .2f, size.height * .19f), androidx.compose.ui.geometry.Size(size.width * .6f, size.height * .55f))
        repeat(8) { index ->
            val angle = index * .78
            val x = center.x + (kotlin.math.cos(angle) * size.width * .19f).toFloat()
            val y = center.y + (kotlin.math.sin(angle) * size.height * .17f).toFloat()
            drawCircle(choice.garnish, 5.dp.toPx(), Offset(x, y))
        }
        drawCircle(Color(0xFF4B883E), 4.dp.toPx(), center)
    }
}

@Composable
private fun ApprovedDishImage(choice: MenuChoice, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { SupabaseCustomerRepository(context) }
    var bitmap by remember(choice.photoPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(choice.photoPath) { repository.approvedMedia(choice.photoPath) { bitmap = it } }
    val approved = bitmap
    if (approved != null) Image(approved.asImageBitmap(), choice.name, modifier = modifier, contentScale = ContentScale.Crop)
    else DishArt(choice, modifier)
}

@Composable
private fun IncludedSide(label: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Color.White, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = accent.copy(alpha = .12f), shape = CircleShape) {
                Icon(Icons.Outlined.Restaurant, null, tint = accent, modifier = Modifier.padding(5.dp).size(13.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(label, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun ReviewPlanScreen(
    provider: Provider,
    plan: MealPackage,
    lunchSelections: Map<Int, String>,
    dinnerSelections: Map<Int, String>,
    onBack: () -> Unit,
    onGoHome: () -> Unit,
    changeProviderMode:Boolean=false
) {
    val basePrice = plan.price.filter { it.isDigit() }.toIntOrNull() ?: 0
    val platformFee = kotlin.math.round(basePrice * .015).toInt()
    val deliveryFee = 99
    val discount = 0
    val total = basePrice + platformFee + deliveryFee - discount
    val showLunch = plan.kind != "DINNER_ONLY"
    val showDinner = plan.kind != "LUNCH_ONLY"
    var showPayment by remember { mutableStateOf(false) }
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { SupabaseCustomerRepository(context) }
    var changeMessage by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = showPayment) { showPayment = false }
    if (showPayment) {
        PaymentScreen(
            provider = provider,
            plan = plan,
            lunchSelections = lunchSelections,
            dinnerSelections = dinnerSelections,
            basePrice = basePrice,
            platformFee = platformFee,
            deliveryFee = deliveryFee,
            discount = discount,
            total = total,
            onBack = { showPayment = false },
            onGoHome = onGoHome
        )
        return
    }

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 10.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(112.dp)) {
                        Text("Total amount", color = Muted, fontSize = 10.sp)
                        Text(formatRupees(total), color = BrandDark, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text("Inclusive of taxes", color = Muted, fontSize = 9.sp)
                    }
                    Button(
                        onClick = {
                            if (!changeProviderMode) showPayment = true
                            else {
                                val subscriptionId = CustomerSubscriptionStore.current?.id.orEmpty()
                                if (subscriptionId.isBlank()) changeMessage = "Your active subscription could not be found. Please sign in again."
                                else repository.switchProviderNow(
                                    subscriptionId = subscriptionId,
                                    providerId = provider.id,
                                    packageId = plan.id,
                                    weeklyMenu = JSONObject().apply {
                                        put("lunch", JSONObject().apply { lunchSelections.forEach { (day, name) -> put(day.toString(), name) } })
                                        put("dinner", JSONObject().apply { dinnerSelections.forEach { (day, name) -> put(day.toString(), name) } })
                                    }
                                ) { result, error ->
                                    if (result != null && error == null) {
                                        CustomerMenuStore.packageKind = plan.kind
                                        repository.activeSubscription { subscription, refreshError ->
                                            if (subscription != null) CustomerSubscriptionStore.current = subscription
                                            changeMessage = refreshError
                                            onGoHome()
                                        }
                                    }
                                    else changeMessage = error ?: "Could not submit the provider change request."
                                }
                            }
                        },
                        enabled = CustomerProfileStore.addressSaved,
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(17.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand)
                    ) {
                        Text(
                            if (!CustomerProfileStore.addressSaved) "Save Address First"
                            else if (changeProviderMode) "Confirm Provider Change"
                            else "Proceed to Payment",
                            fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            changeMessage?.let { item { WalletMessageBanner(it) { changeMessage = null } } }
            item { ReviewHeader(onBack) }
            item { ReviewProviderCard(provider) }
            item { ReviewSectionTitle("Your Selected Plan") }
            item { SelectedPlanCard(plan) }
            item {
                WeeklyPreviewCard(
                    showLunch = showLunch,
                    showDinner = showDinner,
                    lunchSelections = lunchSelections,
                    dinnerSelections = dinnerSelections
                )
            }
            item { AddressReviewCard() }
            item { DeliveryInformationCard(provider.name, showLunch, showDinner) }
            item { PriceDetailsCard(plan, basePrice, platformFee, deliveryFee, discount, total) }
            item { PoliciesCard() }
        }
    }
}

private fun formatRupees(value: Int) = "₹${"%,d".format(value)}"

private fun subscriptionDateRange(startOffsetDays: Int = 0): Pair<String, String> {
    val formatter = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, startOffsetDays)
    val start = formatter.format(calendar.time)
    calendar.add(Calendar.DAY_OF_YEAR, 29)
    return start to formatter.format(calendar.time)
}

private fun subscriptionStartIso(startOffsetDays: Int = 0): String =
    Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1 + startOffsetDays) }
        .let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it.time) }

@Composable
private fun ReviewHeader(onBack: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(116.dp).background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(950f, 380f)),
            RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
        )
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(start = 12.dp).size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).align(Alignment.CenterStart)
        ) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(19.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("zomeal", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("Review Your Plan", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text("Check everything before payment", color = Color.White.copy(alpha = .88f), fontSize = 10.sp)
        }
        Column(Modifier.padding(end = 18.dp, top = 26.dp).align(Alignment.CenterEnd), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.AccountBalanceWallet, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Text("₹1,250", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ReviewProviderCard(provider: Provider) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(106.dp).clip(RoundedCornerShape(18.dp)).background(provider.tint)) {
            ApprovedProviderImage(provider, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(provider.name, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                RatingPill(provider.rating, provider.reviews)
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocationOn, null, tint = Muted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(provider.locality, color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(9.dp))
            Surface(color = Mist, shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Eco, null, tint = Brand, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(provider.diet, color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ReviewSectionTitle(title: String) {
    Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
private fun SelectedPlanCard(plan: MealPackage) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Mist, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            ReviewPlanFact(Icons.Outlined.CalendarMonth, "Monthly package", "30 days plan", Modifier.weight(1f))
            ReviewDivider()
            ReviewPlanFact(plan.icon, plan.title, plan.meals, Modifier.weight(1f))
            ReviewDivider()
            ReviewPlanFact(Icons.Outlined.EventAvailable, "Start: 24 Aug", "End: 22 Sep 2026", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReviewPlanFact(icon: ImageVector, primary: String, secondary: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Brand, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(primary, color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(secondary, color = Muted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun ReviewDivider() { Box(Modifier.width(1.dp).height(52.dp).background(Border)) }

@Composable
private fun WeeklyPreviewCard(
    showLunch: Boolean,
    showDinner: Boolean,
    lunchSelections: Map<Int, String>,
    dinnerSelections: Map<Int, String>
) {
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your Weekly Menu Preview", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Surface(color = Mist, shape = RoundedCornerShape(12.dp)) {
                Text("7 days", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Spacer(Modifier.height(9.dp))
        Surface(color = Mist, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                days.indices.chunked(2).forEach { rowDays ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        rowDays.forEach { index ->
                            WeeklyPreviewDay(
                                day = days[index],
                                lunch = lunchSelections[index],
                                dinner = dinnerSelections[index],
                                showLunch = showLunch,
                                showDinner = showDinner,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowDays.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyPreviewDay(
    day: String,
    lunch: String?,
    dinner: String?,
    showLunch: Boolean,
    showDinner: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier, color = Color.White, shape = RoundedCornerShape(13.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(day, color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            if (showLunch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LightMode, null, tint = Color(0xFFFFB300), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(lunch.orEmpty(), color = Ink, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showDinner) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DarkMode, null, tint = Color(0xFF5E4A9E), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(dinner.orEmpty(), color = Ink, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AddressReviewCard() {
    var editing by remember { mutableStateOf(!CustomerProfileStore.addressSaved) }
    var house by remember { mutableStateOf(CustomerProfileStore.house) }
    var street by remember { mutableStateOf(CustomerProfileStore.street) }
    var locality by remember { mutableStateOf(CustomerProfileStore.locality) }
    var landmark by remember { mutableStateOf(CustomerProfileStore.landmark) }
    var pincode by remember { mutableStateOf(CustomerProfileStore.pincode) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(Icons.Outlined.LocationOn)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Complete Delivery Address", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Required once · saved securely to your profile", color = Muted, fontSize = 9.sp)
                }
                if (CustomerProfileStore.addressSaved && !editing) TextButton(onClick = { editing = true }) { Text("Change", color = BrandDark, fontSize = 9.sp) }
            }
            if (!editing && CustomerProfileStore.addressSaved) {
                Surface(color = Mist, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Home", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Icon(Icons.Filled.CheckCircle, null, tint = Brand, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Serviceable", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
                        Text(CustomerProfileStore.completeAddress, color = Muted, fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
            } else {
                ReviewAddressField("House / Flat number *", house, "e.g. Flat 2B, Plot 123") { house = it }
                ReviewAddressField("Street / Building *", street, "Street, apartment or building") { street = it }
                ReviewAddressField("Locality / City *", locality, "Locality and city") { locality = it }
                ReviewAddressField("Landmark (optional)", landmark, "Nearby landmark") { landmark = it }
                ReviewAddressField("Pincode *", pincode, "6-digit pincode", numeric = true) { pincode = it.take(6) }
                error?.let { Text(it, color = Color(0xFFD64545), fontSize = 9.sp) }
                Button(
                    onClick = {
                        when {
                            house.isBlank() || street.isBlank() || locality.isBlank() || pincode.length != 6 -> error = "Please complete all required address fields."
                            else -> {
                                CustomerProfileStore.house = house.trim(); CustomerProfileStore.street = street.trim()
                                CustomerProfileStore.locality = locality.trim(); CustomerProfileStore.landmark = landmark.trim()
                                CustomerProfileStore.pincode = pincode; CustomerProfileStore.addressSaved = true
                                error = null; editing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)
                ) { Icon(Icons.Outlined.Save, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text("Save Delivery Address", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                Text("Your pincode was confirmed during registration. This address will be saved securely to your profile.", color = Muted, fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun ReviewAddressField(label: String, value: String, placeholder: String, numeric: Boolean = false, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = value,
            onValueChange = { updated -> onValueChange(if (numeric) updated.filter(Char::isDigit) else updated) },
            modifier = Modifier.fillMaxWidth().height(48.dp), singleLine = true,
            placeholder = { Text(placeholder, fontSize = 9.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
            textStyle = LocalTextStyle.current.copy(fontSize = 10.sp),
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
private fun DeliveryInformationCard(providerName: String, showLunch: Boolean, showDinner: Boolean) {
    ReviewInfoSurface {
        IconCircle(Icons.Outlined.LocalShipping)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Delivery Information", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (showLunch) DeliveryFact("Lunch", "12:00–2:00 PM", Modifier.weight(1f))
                if (showDinner) DeliveryFact("Dinner", "7:00–9:00 PM", Modifier.weight(1f))
                DeliveryFact("Delivery by", providerName, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DeliveryFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReviewInfoSurface(content: @Composable RowScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun IconCircle(icon: ImageVector) {
    Surface(color = Mist, shape = CircleShape) { Icon(icon, null, tint = Brand, modifier = Modifier.padding(10.dp).size(19.dp)) }
}

@Composable
private fun PriceDetailsCard(plan: MealPackage, base: Int, fee: Int, deliveryFee: Int, discount: Int, total: Int) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Price Details", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            PriceRow("Monthly plan (${plan.title})", formatRupees(base))
            PriceRow("Platform fee", formatRupees(fee))
            PriceRow("30-day delivery fee", formatRupees(deliveryFee))
            if(discount>0) PriceRow("Discount", "− ${formatRupees(discount)}", BrandDark)
            HorizontalDivider(color = Border)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Total Amount", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Inclusive of all taxes", color = Muted, fontSize = 9.sp)
                }
                Text(formatRupees(total), color = BrandDark, fontSize = 21.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun PriceRow(label: String, value: String, valueColor: Color = Ink) {
    Row {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PoliciesCard() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Important Plans & Policies", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PolicyFact(Icons.Outlined.Edit, "Edit before", "cut-off time", Modifier.weight(1f))
                PolicyFact(Icons.Outlined.PauseCircle, "Pause eligible", "meals anytime", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PolicyFact(Icons.Outlined.HealthAndSafety, "Secure & hygienic", "home-style meals", Modifier.weight(1f))
                PolicyFact(Icons.Outlined.SupportAgent, "Support", "we are here to help", Modifier.weight(1f))
            }
        }
        TextButton(onClick = { }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("View cancellation & pause policy", color = BrandDark, fontSize = 10.sp)
            Icon(Icons.Filled.ChevronRight, null, tint = BrandDark, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun PolicyFact(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Mist, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(icon)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(title, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(subtitle, color = Muted, fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}

private data class PaymentMethod(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val trailing: String = "Instant",
    val recommended: Boolean = false
)

private enum class PrototypeState { NONE, LOADING, OFFLINE, SERVER_ERROR, SESSION_EXPIRED, NO_PROVIDERS, PAYMENT_PENDING, PAYMENT_FAILED, PROVIDER_UNAVAILABLE, PACKAGE_UNAVAILABLE, MENU_UNAVAILABLE, TERMS, PRIVACY, REFUND_POLICY, PAUSE_POLICY }

@Composable
private fun NoSubscriptionHomeScreen(onFindPlan: () -> Unit, onLogout: () -> Unit) {
    var testState by remember { mutableStateOf(PrototypeState.NONE) }
    BackHandler(enabled = testState != PrototypeState.NONE) { testState = PrototypeState.NONE }
    if (testState in setOf(PrototypeState.PAYMENT_PENDING, PrototypeState.PAYMENT_FAILED)) {
        PaymentOutcomeScreen(pending = testState == PrototypeState.PAYMENT_PENDING, onBack = { testState = PrototypeState.NONE }, onRetry = { testState = PrototypeState.PAYMENT_PENDING }, onChangeMethod = { testState = PrototypeState.NONE })
        return
    }
    if (testState in setOf(PrototypeState.PROVIDER_UNAVAILABLE, PrototypeState.PACKAGE_UNAVAILABLE, PrototypeState.MENU_UNAVAILABLE)) {
        UnavailableRecoveryScreen(testState, onBack = { testState = PrototypeState.NONE }, onBrowse = onFindPlan, onKeepPlan = { testState = PrototypeState.NONE })
        return
    }
    if (testState in setOf(PrototypeState.TERMS, PrototypeState.PRIVACY, PrototypeState.REFUND_POLICY, PrototypeState.PAUSE_POLICY)) {
        LegalPolicyScreen(testState, onBack = { testState = PrototypeState.NONE })
        return
    }
    if (testState != PrototypeState.NONE) {
        FriendlyAppStateScreen(testState, onBack = { testState = PrototypeState.NONE }, onRetry = { testState = PrototypeState.LOADING })
        return
    }
    Scaffold(containerColor = Color(0xFFFAFCFA)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { NoPlanHeader(onLogout) }
            item { NoPlanHero(onFindPlan) }
            item { NoPlanBenefits() }
            item { SectionTitle("Recommended near you") }
            items(providers.take(2)) { provider -> ProviderCard(provider, onClick = onFindPlan) }
            item { PrototypeTestPanel { testState = it } }
            item { NoPlanLegalLinks { testState = it } }
        }
    }
}

@Composable private fun NoPlanHeader(onLogout: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(175.dp).background(Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(900f, 360f)), RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 20.dp)) { Text("zomeal", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black); Text("Welcome back!", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold); Text("Your next homely meal is only a few taps away", color = Color.White.copy(alpha = .88f), fontSize = 9.sp) }
        IconButton(onClick = onLogout, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Icon(Icons.Outlined.Logout, "Log out", tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

@Composable private fun NoPlanHero(onFindPlan: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(20.dp), shadowElevation = 3.dp) {
        Column(Modifier.padding(17.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FunnyStateIllustration(PrototypeState.NO_PROVIDERS, Modifier.size(128.dp))
            Text("No active meal plan yet", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text("Choose a nearby kitchen, personalize your weekly menu and let us handle the daily cooking.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(13.dp)); Button(onClick = onFindPlan, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Find a Meal Plan", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
        }
    }
}

@Composable private fun NoPlanBenefits() {
    val items = listOf(Triple(Icons.Outlined.Restaurant, "Homely meals", "Menus you can customize"), Triple(Icons.Outlined.CalendarMonth, "Monthly plans", "Pause or edit anytime"), Triple(Icons.Outlined.LocalShipping, "Daily delivery", "Reliable lunch and dinner"))
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) { items.forEach { item -> Surface(Modifier.weight(1f), color = Mist, shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(item.first, null, tint = Brand, modifier = Modifier.size(20.dp)); Text(item.second, color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text(item.third, color = Muted, fontSize = 6.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } } } }
}

@Composable private fun PrototypeTestPanel(onOpen: (PrototypeState) -> Unit) {
    val states = listOf(PrototypeState.LOADING to "Loading", PrototypeState.OFFLINE to "No internet", PrototypeState.SERVER_ERROR to "Server error", PrototypeState.SESSION_EXPIRED to "Session expired", PrototypeState.NO_PROVIDERS to "No providers", PrototypeState.PAYMENT_PENDING to "Payment pending", PrototypeState.PAYMENT_FAILED to "Payment failed", PrototypeState.PROVIDER_UNAVAILABLE to "Provider unavailable", PrototypeState.PACKAGE_UNAVAILABLE to "Package unavailable", PrototypeState.MENU_UNAVAILABLE to "Menu unavailable")
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFFFFAED), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0DFC0))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Prototype Test Screens", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold); Text("Tap any state to preview it during MVP testing.", color = Muted, fontSize = 8.sp); states.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { row.forEach { state -> OutlinedButton(onClick = { onOpen(state.first) }, modifier = Modifier.weight(1f).height(37.dp), shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(state.second, fontSize = 7.sp, fontWeight = FontWeight.Bold) } }; if (row.size == 1) Spacer(Modifier.weight(1f)) } }
        }
    }
}

@Composable private fun FriendlyAppStateScreen(state: PrototypeState, onBack: () -> Unit, onRetry: () -> Unit) {
    val content = when (state) { PrototypeState.LOADING -> Triple("Warming up your meal world…", "Our tiny chef is arranging fresh providers and menus for you.", "Please wait"); PrototypeState.OFFLINE -> Triple("Oops, the internet took a tea break!", "Check Wi-Fi or mobile data and we’ll get your meals back on the table.", "Try Again"); PrototypeState.SERVER_ERROR -> Triple("Our kitchen server spilled the dal", "Nothing was charged or lost. Give us a moment and try again.", "Retry"); PrototypeState.SESSION_EXPIRED -> Triple("Your session got a little sleepy", "Log in again securely to continue managing your meal plan.", "Login Again"); else -> Triple("No kitchens found nearby", "Try a different pincode or check again soon—we’re adding new kitchens every day.", "Change Location") }
    Scaffold(containerColor = Color(0xFFFAFCFA), bottomBar = { Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 7.dp) { Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(18.dp).height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Text(content.third, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) } } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { FunnyStateIllustration(state, Modifier.size(230.dp)); Text(content.first, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center); Spacer(Modifier.height(8.dp)); Text(content.second, color = Muted, fontSize = 11.sp, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center); if (state != PrototypeState.LOADING) TextButton(onClick = onBack) { Text("Back to Test Screens", color = BrandDark, fontSize = 9.sp) } }
    }
}

@Composable private fun FunnyStateIllustration(state: PrototypeState, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0xFFEAF6E7), radius = size.minDimension * .42f); drawCircle(Color(0xFFD7EDC8), radius = size.minDimension * .32f) }
        when (state) { PrototypeState.LOADING -> { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("👨‍🍳", fontSize = 52.sp); CircularProgressIndicator(Modifier.size(24.dp), color = Brand, strokeWidth = 3.dp); Text("stirring…", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold) } }; PrototypeState.OFFLINE -> Text("📡☕", fontSize = 52.sp); PrototypeState.SERVER_ERROR -> Text("🥣💥", fontSize = 54.sp); PrototypeState.SESSION_EXPIRED -> Text("😴🔐", fontSize = 52.sp); PrototypeState.NO_PROVIDERS -> Text("🍽️🔍", fontSize = 54.sp); else -> Text("🍲", fontSize = 56.sp) }
    }
}

@Composable private fun PaymentOutcomeScreen(pending: Boolean, onBack: () -> Unit, onRetry: () -> Unit, onChangeMethod: () -> Unit) {
    Scaffold(containerColor = Color(0xFFFAFCFA), bottomBar = { Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 8.dp) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onChangeMethod, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Change Method", fontSize = 9.sp, fontWeight = FontWeight.Bold) }; Button(onClick = onRetry, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Text(if (pending) "Check Status" else "Retry Payment", fontSize = 9.sp, fontWeight = FontWeight.Bold) } } } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) { item { PaymentHeader(onBack) }; item { Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) { PaymentFunnyIllustration(pending); Text(if (pending) "Payment is being confirmed" else "Payment didn’t go through", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center); Text(if (pending) "Please don’t pay again. Your bank may take a few minutes to confirm the transaction." else "No money was charged. You can safely retry or choose another payment method.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }; item { PaymentStatusDetails(pending) }; item { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(15.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.SupportAgent, null, tint = Brand, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Column { Text("Need help?", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("Keep transaction ID ZM-PAY-1048 ready for support.", color = Muted, fontSize = 8.sp) } } } } }
    }
}

@Composable private fun PaymentFunnyIllustration(pending: Boolean) { Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) { Canvas(Modifier.fillMaxSize()) { drawCircle(if (pending) Color(0xFFFFF0C9) else Color(0xFFFFE8E4), radius = size.minDimension * .42f) }; Text(if (pending) "💳⏳" else "💳😵", fontSize = 62.sp) } }

@Composable private fun PaymentStatusDetails(pending: Boolean) { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Text("Payment details", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold); PriceRow("Amount", "₹6,394"); PriceRow("Method", "UPI"); PriceRow("Transaction ID", "ZM-PAY-1048"); HorizontalDivider(color = Border); Row { Text("Status", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f)); Surface(color = if (pending) Color(0xFFFFF0C9) else Color(0xFFFFE8E4), shape = RoundedCornerShape(8.dp)) { Text(if (pending) "PENDING" else "FAILED", color = if (pending) Color(0xFFA66A00) else Color(0xFFC83B32), fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) } } } } }

@Composable private fun UnavailableRecoveryScreen(state: PrototypeState, onBack: () -> Unit, onBrowse: () -> Unit, onKeepPlan: () -> Unit) {
    val providerCase = state == PrototypeState.PROVIDER_UNAVAILABLE
    val packageCase = state == PrototypeState.PACKAGE_UNAVAILABLE
    val title = when (state) { PrototypeState.PROVIDER_UNAVAILABLE -> "This kitchen is taking a break"; PrototypeState.PACKAGE_UNAVAILABLE -> "This package left the menu"; else -> "One meal option sold out" }
    val detail = when (state) { PrototypeState.PROVIDER_UNAVAILABLE -> "Swaad Ghar is no longer accepting subscriptions for your area. Your saved menu is safe."; PrototypeState.PACKAGE_UNAVAILABLE -> "Lunch + Dinner is temporarily unavailable. Choose an available package or another kitchen."; else -> "Paneer Butter Masala is unavailable for Thursday lunch. Pick a replacement before the cut-off." }
    val emoji = when (state) { PrototypeState.PROVIDER_UNAVAILABLE -> "🏠😴"; PrototypeState.PACKAGE_UNAVAILABLE -> "📦👋"; else -> "🍛🏃" }
    Scaffold(containerColor = Color(0xFFFAFCFA), bottomBar = { Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 8.dp) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Not Now", fontSize = 9.sp, fontWeight = FontWeight.Bold) }; Button(onClick = if (providerCase || packageCase) onBrowse else onKeepPlan, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Text(if (providerCase) "Find Kitchens" else if (packageCase) "View Packages" else "Choose Replacement", fontSize = 8.sp, fontWeight = FontWeight.Bold) } } } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { AppSectionHeader("Plan Update", "A small change needs your attention", Icons.Outlined.Update) }; item { Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) { Canvas(Modifier.fillMaxSize()) { drawCircle(Color(0xFFFFF2D7), radius = size.minDimension * .43f) }; Text(emoji, fontSize = 62.sp) }; Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center); Text(detail, color = Muted, fontSize = 10.sp, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }; item { UnavailableImpactCard(state) }; item { UnavailableSafetyCard(state) } }
    }
}

@Composable private fun UnavailableImpactCard(state: PrototypeState) {
    val facts = when (state) { PrototypeState.PROVIDER_UNAVAILABLE -> listOf("No payment will be collected" to Icons.Outlined.Payments, "Saved address and menu remain available" to Icons.Outlined.Save, "Browse other serviceable kitchens" to Icons.Outlined.Storefront); PrototypeState.PACKAGE_UNAVAILABLE -> listOf("Current price is unchanged until you choose" to Icons.Outlined.CurrencyRupee, "Weekly menu remains saved" to Icons.Outlined.RestaurantMenu, "Compare alternate packages" to Icons.Outlined.CompareArrows); else -> listOf("Only Thursday lunch is affected" to Icons.Outlined.CalendarMonth, "Choose from provider alternatives" to Icons.Outlined.SwapHoriz, "No extra charge for replacement" to Icons.Outlined.Verified) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("What happens now?", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold); facts.forEach { fact -> Row(verticalAlignment = Alignment.CenterVertically) { Surface(color = Mist, shape = CircleShape) { Icon(fact.second, null, tint = Brand, modifier = Modifier.padding(8.dp).size(16.dp)) }; Spacer(Modifier.width(9.dp)); Text(fact.first, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Medium) } } } }
}

@Composable private fun UnavailableSafetyCard(state: PrototypeState) { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(15.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.Shield, null, tint = Brand, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (state == PrototypeState.MENU_UNAVAILABLE) "If you don’t choose before cut-off, the provider’s recommended replacement will be used and clearly shown in your order." else "Zomeal will never move or charge your subscription without your confirmation.", color = Muted, fontSize = 8.sp, lineHeight = 12.sp) } } }

@Composable private fun NoPlanLegalLinks(onOpen: (PrototypeState) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Policies & Legal", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); listOf(PrototypeState.TERMS to "Terms of Service", PrototypeState.PRIVACY to "Privacy Policy", PrototypeState.REFUND_POLICY to "Refund & Cancellation", PrototypeState.PAUSE_POLICY to "Subscription Pause Policy").forEach { item -> Row(Modifier.fillMaxWidth().clickable { onOpen(item.first) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Description, null, tint = Brand, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(item.second, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.size(16.dp)) } } } }
}

@Composable private fun LegalPolicyScreen(state: PrototypeState, onBack: () -> Unit) {
    val title = when (state) { PrototypeState.TERMS -> "Terms of Service"; PrototypeState.PRIVACY -> "Privacy Policy"; PrototypeState.REFUND_POLICY -> "Refund & Cancellation"; else -> "Subscription Pause Policy" }
    val sections = when (state) {
        PrototypeState.TERMS -> listOf("Using Zomeal" to "Zomeal connects customers with independent meal-service providers. Package details, menus, delivery windows and applicable cut-offs are shown before payment.", "Subscriptions" to "A subscription begins only after successful payment. Customers must provide an accurate serviceable delivery address and follow provider menu-change deadlines.", "Fair use" to "Do not misuse accounts, payments, referrals, reviews or support channels. Zomeal may restrict accounts involved in fraud or abuse.")
        PrototypeState.PRIVACY -> listOf("Information we collect" to "We collect account details, phone number, delivery address, meal preferences, orders and payment references needed to provide the service.", "How information is used" to "Data is used for authentication, serviceability, meal delivery, support, safety, payments and product improvement.", "Your choices" to "You may update profile details, notification preferences and request account deletion. Payment credentials are handled by approved payment partners.")
        PrototypeState.REFUND_POLICY -> listOf("Eligible refunds" to "Refunds may apply to failed payments, undelivered meals, verified missing items or eligible service-quality issues.", "How refunds arrive" to "Approved amounts may return to the original payment method or Zomeal Wallet. Banking timelines can vary.", "Cancellations" to "Meal or plan cancellations must follow the provider cut-off shown in the app. Prepared or dispatched meals may not be refundable.")
        else -> listOf("Pausing meals" to "Customers can pause eligible Lunch, Dinner or both before the provider’s daily cut-off.", "Plan impact" to "Eligible paused meals may extend the plan or receive credit according to the selected provider’s package rules.", "Resuming" to "Paused meals can be resumed before cut-off. The Home and My Plan screens show the latest active pause schedule.")
    }
    Scaffold(containerColor = Color(0xFFFAFCFA), bottomBar = { Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 7.dp) { Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(14.dp).height(48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Text("I Understand", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) } } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.linearGradient(listOf(BrandDark, Brand, Lime)), RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp))) { IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(12.dp).size(39.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) }; Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text("zomeal", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black); Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold); Text("Effective 12 August 2026", color = Color.White.copy(alpha = .85f), fontSize = 8.sp) } } }; item { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(14.dp)) { Text("This prototype copy summarizes the MVP policy. Final legal text should be reviewed by a qualified legal professional before launch.", color = Muted, fontSize = 8.sp, lineHeight = 12.sp, modifier = Modifier.padding(12.dp)) } }; items(sections) { section -> Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Column(Modifier.padding(14.dp)) { Text(section.first, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.height(6.dp)); Text(section.second, color = Muted, fontSize = 9.sp, lineHeight = 14.sp) } } }; item { Text("Questions? Contact Zomeal Support from your Profile.", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(18.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
    }
}

@Composable
private fun PaymentScreen(
    provider: Provider,
    plan: MealPackage,
    lunchSelections: Map<Int, String>,
    dinnerSelections: Map<Int, String>,
    basePrice: Int,
    platformFee: Int,
    deliveryFee: Int,
    discount: Int,
    total: Int,
    onBack: () -> Unit,
    onGoHome: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val paymentRepository = remember(context.applicationContext) { SupabaseCustomerRepository(context.applicationContext) }
    val methods = remember {
        listOf(
            PaymentMethod("UPI", "Pay using any UPI app", Icons.Outlined.QrCode2, recommended = true),
            PaymentMethod("Cards", "Visa, Mastercard, RuPay", Icons.Outlined.CreditCard),
            PaymentMethod("Net Banking", "All major banks", Icons.Outlined.AccountBalance),
            PaymentMethod("Wallets", "PhonePe, Paytm, Amazon Pay", Icons.Outlined.AccountBalanceWallet),
            PaymentMethod("EMI", "Credit card EMI", Icons.Outlined.EventRepeat, "From ₹1,066/month")
        )
    }
    var selectedMethod by remember { mutableIntStateOf(0) }
    var paymentComplete by remember { mutableStateOf(false) }
    var paymentLoading by remember { mutableStateOf(false) }
    var paymentError by remember { mutableStateOf<String?>(null) }
    var verifiedPaymentId by remember { mutableStateOf("") }

    fun checkoutAddress()=JSONObject().apply{
        put("house",CustomerProfileStore.house);put("street",CustomerProfileStore.street)
        put("locality",CustomerProfileStore.locality);put("landmark",CustomerProfileStore.landmark);put("pincode",CustomerProfileStore.pincode)
        put("city","Bhubaneswar");put("state","Odisha")
    }
    fun checkoutMenu()=JSONObject().apply{
        put("lunch",JSONObject(lunchSelections.mapKeys{it.key.toString()}))
        put("dinner",JSONObject(dinnerSelections.mapKeys{it.key.toString()}))
        put("preference",when(provider.category){DietFilter.VEG->"VEG";DietFilter.NON_VEG->"NON_VEG";else->"BOTH"})
    }
    LaunchedEffect(provider.id,plan.id) {
        val payload=JSONObject().apply{
            put("provider",JSONObject().put("id",provider.id).put("name",provider.name).put("locality",provider.locality).put("diet",provider.diet).put("dietary_type",provider.category.name).put("description",provider.description).put("photo_path",provider.primaryPhotoPath).put("weekly_menu",JSONArray(provider.weeklyMenu)))
            put("package",JSONObject().put("id",plan.id).put("kind",plan.kind).put("title",plan.title).put("meals",plan.meals).put("price",plan.price))
            put("delivery_address",checkoutAddress());put("weekly_menu",checkoutMenu())
            put("base_price",basePrice);put("platform_fee",platformFee);put("delivery_fee",deliveryFee);put("discount",discount);put("total",total)
            put("start_date",subscriptionStartIso());put("first_meal",if(plan.kind=="DINNER_ONLY")"DINNER" else "LUNCH")
        }
        paymentRepository.saveCheckoutDraft(provider.id,plan.id,payload){error->if(error!=null)paymentError="Checkout could not be saved: $error"}
    }

    fun beginPayment() {
        if(activity==null){paymentError="Payment checkout requires an Android Activity";return}
        paymentLoading=true;paymentError=null;RazorpayCoordinator.result=null
        paymentRepository.createRazorpayOrder(
            plan.id,checkoutAddress(),checkoutMenu(),subscriptionStartIso(),if(plan.kind=="DINNER_ONLY")"DINNER" else "LUNCH"
        ){order,error->
            if(error!=null||order==null){paymentLoading=false;paymentError=error?:"Could not start payment";return@createRazorpayOrder}
            RazorpayCoordinator.pendingOrder=order
            runCatching{
                Checkout().apply{setKeyID(order.keyId)}.open(activity,JSONObject().apply{
                    put("name","Zomeal");put("description","${provider.name} · ${plan.title}")
                    put("image","");put("order_id",order.razorpayOrderId);put("currency",order.currency);put("amount",order.amountPaise)
                    put("theme",JSONObject().put("color","#078A45"));put("retry",JSONObject().put("enabled",true).put("max_count",2))
                    put("notes",JSONObject().put("zomeal_receipt",order.receipt))
                })
            }.onFailure{paymentLoading=false;paymentError=it.message?:"Could not open Razorpay Checkout"}
        }
    }

    LaunchedEffect(RazorpayCoordinator.result) {
        when(val result=RazorpayCoordinator.result){
            is RazorpayAppResult.Success->{
                val order=RazorpayCoordinator.pendingOrder
                if(order==null){paymentLoading=false;paymentError="Payment order context was lost"}
                else paymentRepository.verifyRazorpayPayment(order.paymentOrderId,result.orderId.ifBlank{order.razorpayOrderId},result.paymentId,result.signature){json,error->
                    paymentLoading=false
                    if(error!=null||json?.optBoolean("verified")!=true)paymentError=error?:"Payment could not be verified"
                    else{
                        verifiedPaymentId=result.paymentId
                        if(json.optBoolean("subscription_activated")){
                            paymentRepository.activeSubscription { persisted,_ ->
                                if(persisted!=null)CustomerSubscriptionStore.current=persisted
                                paymentRepository.clearCheckoutDraft{}
                                paymentComplete=true
                            }
                        } else paymentComplete=true
                    }
                    RazorpayCoordinator.result=null
                }
            }
            is RazorpayAppResult.Failure->{paymentLoading=false;paymentError=result.message;RazorpayCoordinator.result=null}
            null->Unit
        }
    }

    if (paymentComplete) {
        PaymentSuccessScreen(
            provider = provider,
            plan = plan,
            total = total,
            paymentMethod = methods[selectedMethod].title,
            gatewayPaymentId = verifiedPaymentId,
            lunchSelections = lunchSelections,
            dinnerSelections = dinnerSelections,
            onGoHome = onGoHome
        )
        return
    }

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 10.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(112.dp)) {
                            Text("Total amount", color = Muted, fontSize = 10.sp)
                            Text(formatRupees(total), color = BrandDark, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text("Inclusive of taxes", color = Muted, fontSize = 9.sp)
                        }
                        Button(
                            onClick = { beginPayment() },
                            enabled = !paymentLoading,
                            modifier = Modifier.weight(1f).height(54.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand)
                        ) {
                            Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if(paymentLoading)"Opening secure checkout…" else "Pay securely", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(17.dp))
                        }
                    }
                    HorizontalDivider(color = Border)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SecurityMiniFact(Icons.Outlined.VerifiedUser, "PCI DSS certified")
                        SecurityMiniFact(Icons.Outlined.AccountBalance, "RBI compliant")
                        SecurityMiniFact(Icons.Outlined.Groups, "Trusted payments")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { PaymentHeader(onBack) }
            item { CompactPaymentOverview(provider, plan, basePrice, platformFee, deliveryFee, discount, total) }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text("Choose Payment Method", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Select an option to complete your payment", color = Muted, fontSize = 10.sp)
                }
            }
            items(methods.size) { index ->
                PaymentMethodCard(
                    method = methods[index],
                    selected = selectedMethod == index,
                    onSelect = { selectedMethod = index }
                )
            }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Mist, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.GppGood, null, tint = Brand, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Your payment is protected by ", color = Muted, fontSize = 10.sp)
                        Text("256-bit SSL encryption", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            paymentError?.let { message -> item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp),color=Color(0xFFFFE8E4),shape=RoundedCornerShape(14.dp)){
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.Top){Icon(Icons.Outlined.ErrorOutline,null,tint=Color(0xFFC83B32),modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(message,color=Color(0xFF8D2923),fontSize=10.sp,lineHeight=14.sp)}
                }
            } }
        }
    }
}

@Composable
private fun PaymentHeader(onBack: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(116.dp).background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(950f, 360f)),
            RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
        )
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(start = 12.dp).size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).align(Alignment.CenterStart)
        ) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(19.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("zomeal", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("Payment", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text("Choose how you want to pay", color = Color.White.copy(alpha = .9f), fontSize = 10.sp)
        }
        Row(Modifier.padding(end = 14.dp).align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GppGood, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Column {
                Text("Secure", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CompactPaymentOverview(
    provider: Provider,
    plan: MealPackage,
    base: Int,
    fee: Int,
    deliveryFee: Int,
    discount: Int,
    total: Int
) {
    val startDate = remember { subscriptionDateRange().first }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(13.dp)).background(provider.tint)) {
                    ProviderFoodArt(provider.accent, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(provider.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${plan.title}  ·  ${plan.meals}  ·  30 days", color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Starts $startDate", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Surface(color = Mist, shape = RoundedCornerShape(11.dp)) {
                    Text("Reviewed ✓", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                }
            }
            HorizontalDivider(color = Border)
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Amount payable", color = Muted, fontSize = 9.sp)
                    Text(formatRupees(total), color = BrandDark, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    Text("Inclusive of all taxes", color = Muted, fontSize = 8.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Plan ${formatRupees(base)} · platform ${formatRupees(fee)}", color = Muted, fontSize = 8.sp)
                    Text("Delivery ${formatRupees(deliveryFee)}${if(discount>0)" · save ${formatRupees(discount)}" else ""}", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PaymentPlanSummary(provider: Provider, plan: MealPackage) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Color.White, shape = RoundedCornerShape(20.dp), shadowElevation = 3.dp) {
        Column(Modifier.padding(16.dp)) {
            Text("Plan Summary", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(92.dp).clip(RoundedCornerShape(15.dp)).background(provider.tint)) {
                    ProviderFoodArt(provider.accent, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(provider.name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                        RatingPill(provider.rating, provider.reviews)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.LocationOn, null, tint = Muted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(provider.locality, color = Muted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(7.dp))
                    Surface(color = Mist, shape = RoundedCornerShape(9.dp)) {
                        Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Eco, null, tint = Brand, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(provider.diet, color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(color = Mist, shape = RoundedCornerShape(15.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReviewPlanFact(Icons.Outlined.CalendarMonth, "Monthly package", "30 days plan", Modifier.weight(1f))
                    ReviewDivider()
                    ReviewPlanFact(plan.icon, plan.title, plan.meals, Modifier.weight(1f))
                    ReviewDivider()
                    ReviewPlanFact(Icons.Outlined.EventAvailable, "Start date", "24 Aug 2026", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PaymentAmountCard(plan: MealPackage, base: Int, fee: Int, discount: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Amount Payable", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(9.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PriceRow("Monthly plan (${plan.title})", formatRupees(base))
                PriceRow("Platform fee", formatRupees(fee))
                PriceRow("Discount", "− ${formatRupees(discount)}", BrandDark)
                HorizontalDivider(color = Border)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Total Amount", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Inclusive of all taxes", color = Muted, fontSize = 9.sp)
                    }
                    Text(formatRupees(total), color = BrandDark, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodCard(method: PaymentMethod, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable(onClick = onSelect),
        color = Color.White,
        shape = RoundedCornerShape(15.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Brand else Border),
        shadowElevation = if (selected) 2.dp else 1.dp
    ) {
        Box {
            if (method.recommended) {
                Surface(color = BrandDark, shape = RoundedCornerShape(bottomStart = 7.dp, bottomEnd = 7.dp), modifier = Modifier.padding(start = 54.dp).align(Alignment.TopStart)) {
                    Text("Recommended", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect, colors = RadioButtonDefaults.colors(selectedColor = Brand))
                Surface(color = Mist, shape = CircleShape) {
                    Icon(method.icon, null, tint = if (method.title == "Cards") Color(0xFF245EAE) else BrandDark, modifier = Modifier.padding(10.dp).size(19.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(method.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    Text(method.subtitle, color = Muted, fontSize = 9.sp)
                }
                Text(method.trailing, color = if (method.trailing == "Instant") BrandDark else Muted, fontSize = 9.sp, fontWeight = if (method.trailing == "Instant") FontWeight.Bold else FontWeight.Normal)
                if (method.trailing == "Instant") Icon(Icons.Filled.Bolt, null, tint = Brand, modifier = Modifier.size(14.dp))
                Icon(Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun SecurityMiniFact(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Brand, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Muted, fontSize = 8.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSuccessScreen(
    provider: Provider,
    plan: MealPackage,
    total: Int,
    paymentMethod: String,
    gatewayPaymentId: String,
    lunchSelections: Map<Int, String>,
    dinnerSelections: Map<Int, String>,
    onGoHome: () -> Unit
) {
    var startOffsetDays by remember { mutableIntStateOf(0) }
    var pendingStartOffset by remember { mutableIntStateOf(0) }
    var showPlanDetails by remember { mutableStateOf(false) }
    var showDateOptions by remember { mutableStateOf(false) }
    var showStartMealOptions by remember { mutableStateOf(false) }
    var firstSlot by remember(plan.kind) { mutableStateOf(if (plan.kind == "DINNER_ONLY") "Dinner" else "Lunch") }
    var pendingFirstSlot by remember { mutableStateOf(firstSlot) }
    val firstMeal = if (firstSlot == "Dinner") dinnerSelections[0].orEmpty() else lunchSelections[0].orEmpty()
    val firstChoice = (lunchChoices + dinnerChoices).firstOrNull { it.name == firstMeal }
        ?: if (firstSlot == "Dinner") dinnerChoices.first() else lunchChoices.first()
    val fallbackOrderId = remember { "ZM${System.currentTimeMillis().toString().takeLast(8)}" }
    val orderId = gatewayPaymentId.ifBlank { fallbackOrderId }
    val (startDate, endDate) = remember(startOffsetDays) { subscriptionDateRange(startOffsetDays) }

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 10.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(
                    onClick = { showPlanDetails = true },
                    modifier = Modifier.weight(1f).padding(vertical = 9.dp).height(50.dp),
                    shape = RoundedCornerShape(15.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Brand)
                ) { Text("View My Plan", color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onGoHome,
                    modifier = Modifier.weight(1.35f).padding(vertical = 9.dp).height(50.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand)
                ) {
                    Icon(Icons.Outlined.Home, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Go to Homepage", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            }
        }
    ) { scaffoldPadding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(scaffoldPadding).statusBarsPadding()
        ) {
            val compact = maxHeight < 760.dp
            Column(
                Modifier.fillMaxSize().padding(bottom = 5.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp)
            ) {
                SuccessHero(compact)
                PaymentConfirmedCard(total, paymentMethod, orderId)
                SuccessProviderCard(provider)
                SuccessPlanDetails(plan, startDate, endDate, onChangeDate = { showDateOptions = true })
                FirstMealCard(firstSlot, firstMeal, firstChoice, provider.name, startDate, compact)
                SuccessAssuranceStrip()
            }
        }
    }

    if (showPlanDetails) {
        AlertDialog(
            onDismissRequest = { showPlanDetails = false },
            icon = { Icon(Icons.Outlined.CalendarMonth, null, tint = Brand) },
            title = { Text("Your Active Plan", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(provider.name, color = Ink, fontWeight = FontWeight.Bold)
                    Text("${plan.title} · ${plan.meals} · 30 days", color = Muted, fontSize = 12.sp)
                    Text("$startDate – $endDate", color = BrandDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("First meal: $firstSlot · $firstMeal", color = Muted, fontSize = 11.sp)
                    Text("Paid ${formatRupees(total)} using $paymentMethod", color = Muted, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showPlanDetails = false }) { Text("Done", color = BrandDark) } }
        )
    }

    if (showDateOptions) {
        val todayMillis = remember { System.currentTimeMillis().let { it - (it % 86_400_000L) } }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = todayMillis + startOffsetDays * 86_400_000L,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayMillis
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDateOptions = false },
            confirmButton = {
                TextButton(onClick = {
                    val selected = datePickerState.selectedDateMillis ?: todayMillis
                    val offset = ((selected - todayMillis) / 86_400_000L).toInt().coerceAtLeast(0)
                    showDateOptions = false
                    if (plan.title == "Lunch & Dinner") {
                        pendingStartOffset = offset
                        pendingFirstSlot = firstSlot
                        showStartMealOptions = true
                    } else {
                        startOffsetDays = offset
                    }
                }) { Text("Confirm", color = BrandDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showDateOptions = false }) { Text("Cancel", color = Muted) } }
        ) {
            Column {
                DatePicker(
                    state = datePickerState,
                    modifier = Modifier.height(405.dp),
                    showModeToggle = false,
                    title = { Text("Choose service start date", modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) },
                    headline = null
                )
            }
        }
    }

    if (showStartMealOptions) {
        AlertDialog(
            onDismissRequest = { showStartMealOptions = false },
            icon = { Icon(Icons.Outlined.RestaurantMenu, null, tint = Brand) },
            title = { Text("Start with which meal?", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your selected date is ${subscriptionDateRange(pendingStartOffset).first}. Choose the first meal you will be available to receive.", color = Muted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        listOf("Lunch", "Dinner").forEach { slot ->
                            Surface(
                                modifier = Modifier.weight(1f).clickable { pendingFirstSlot = slot },
                                color = if (pendingFirstSlot == slot) Mist else Color.White,
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(if (pendingFirstSlot == slot) 2.dp else 1.dp, if (pendingFirstSlot == slot) Brand else Border)
                            ) {
                                Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(if (slot == "Lunch") Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null, tint = if (slot == "Lunch") Color(0xFFFFB300) else Color(0xFF5E4A9E), modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.height(5.dp))
                                    Text(slot, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(if (slot == "Lunch") "12–2 PM" else "7–9 PM", color = Muted, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { startOffsetDays = pendingStartOffset; firstSlot = pendingFirstSlot; showStartMealOptions = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Confirm $pendingFirstSlot", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showStartMealOptions = false }) { Text("Back", color = Muted) } }
        )
    }
}

@Composable
private fun SuccessHero(compact: Boolean) {
    Box(
        Modifier.fillMaxWidth().height(if (compact) 148.dp else 170.dp).background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(950f, 620f)),
            RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
        )
    ) {
        ConfettiArt(Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("zomeal", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(if (compact) 5.dp else 8.dp))
            Surface(color = Color.White, shape = CircleShape, shadowElevation = 10.dp) {
                Icon(Icons.Filled.Check, null, tint = Brand, modifier = Modifier.padding(if (compact) 10.dp else 13.dp).size(if (compact) 25.dp else 29.dp))
            }
            Spacer(Modifier.height(if (compact) 7.dp else 10.dp))
            Text("Payment successful!", color = Color.White, fontSize = if (compact) 19.sp else 21.sp, fontWeight = FontWeight.Black)
            Text("Your meal plan is active · Welcome to the family", color = Color.White.copy(alpha = .92f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun ConfettiArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val colors = listOf(Color(0xFFFFC52E), Color(0xFF72D7A0), Color.White, Color(0xFF88B936))
        val points = listOf(.16f to .28f, .27f to .17f, .39f to .27f, .62f to .17f, .76f to .3f, .84f to .18f, .21f to .5f, .71f to .49f, .88f to .56f, .33f to .58f)
        points.forEachIndexed { index, point ->
            drawRect(colors[index % colors.size], Offset(size.width * point.first, size.height * point.second), androidx.compose.ui.geometry.Size(7.dp.toPx(), 7.dp.toPx()))
        }
        drawCircle(Color.White.copy(alpha = .13f), 80.dp.toPx(), Offset(size.width * .5f, size.height * .38f))
    }
}

@Composable
private fun SuccessProviderCard(provider: Provider) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Color.White, shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(66.dp).clip(RoundedCornerShape(14.dp)).background(provider.tint)) {
                ApprovedProviderImage(provider, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    RatingPill(provider.rating, provider.reviews)
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, null, tint = Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(provider.locality, color = Muted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(4.dp))
                Surface(color = Mist, shape = RoundedCornerShape(9.dp)) {
                    Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Eco, null, tint = Brand, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(provider.diet, color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessPlanDetails(plan: MealPackage, startDate: String, endDate: String, onChangeDate: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your Plan Details", color = BrandDark, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onChangeDate, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                    Icon(Icons.Outlined.EditCalendar, null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Change date", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = Border)
            Row(Modifier.padding(top = 7.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(plan.icon, null, tint = Brand, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(plan.title, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${plan.meals} · 30 days", color = Muted, fontSize = 8.sp)
                }
                Box(Modifier.width(1.dp).height(34.dp).background(Border))
                Spacer(Modifier.width(9.dp))
                Icon(Icons.Outlined.EventAvailable, null, tint = Brand, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1.35f)) {
                    Text("Service period", color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("$startDate – $endDate", color = Muted, fontSize = 7.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PaymentConfirmedCard(total: Int, paymentMethod: String, orderId: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Mist, shape = RoundedCornerShape(17.dp)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Brand, shape = CircleShape) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.padding(11.dp).size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Payment Successful!", color = BrandDark, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text("We've received your payment of ${formatRupees(total)}.", color = Muted, fontSize = 10.sp)
                Text("Paid using $paymentMethod  ·  Order $orderId", color = Muted, fontSize = 9.sp)
            }
            Icon(Icons.Outlined.ReceiptLong, null, tint = Brand, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FirstMealCard(slot: String, meal: String, choice: MenuChoice, providerName: String, startDate: String, compact: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Your First Meal", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(3.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(19.dp), shadowElevation = 3.dp) {
            Row(Modifier.height(if (compact) 90.dp else 100.dp)) {
                Column(Modifier.weight(1.25f).padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (slot == "Lunch") Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null, tint = if (slot == "Lunch") Color(0xFFFFB300) else Color(0xFF34547A), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(slot, color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(meal, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("By $providerName", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Rice · Dal · Salad", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Event, null, tint = Brand, modifier = Modifier.size(11.dp))
                        Text(" $startDate", color = Muted, fontSize = 7.sp)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.Schedule, null, tint = Muted, modifier = Modifier.size(11.dp))
                        Text(if (slot == "Lunch") " 12–2 PM" else " 7–9 PM", color = Muted, fontSize = 9.sp)
                    }
                }
                Box(Modifier.weight(.75f).fillMaxHeight().background(Color(0xFFFFE2B8))) {
                    ApprovedDishImage(choice, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun SuccessAssuranceStrip() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 5.dp),
        color = Mist,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            SuccessMiniInfo(Icons.Outlined.LocalShipping, "On-time delivery", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(24.dp).background(Border))
            SuccessMiniInfo(Icons.Outlined.EditCalendar, "Flexible start", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(24.dp).background(Border))
            SuccessMiniInfo(Icons.Outlined.SupportAgent, "Always supported", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SuccessMiniInfo(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Brand, modifier = Modifier.size(14.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = Muted, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun WhatsNextSection() {
    val steps = listOf(
        Triple(Icons.Outlined.RoomService, "Relax & Enjoy", "We'll take care of your meals."),
        Triple(Icons.Outlined.CalendarMonth, "Manage Anytime", "Edit, pause or skip meals."),
        Triple(Icons.Outlined.SupportAgent, "We're Here", "Support is always available.")
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = Border)
            Text("  What's Next?  ", color = BrandDark, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            HorizontalDivider(Modifier.weight(1f), color = Border)
        }
        Spacer(Modifier.height(14.dp))
        Row {
            steps.forEach { step ->
                Column(Modifier.weight(1f).padding(horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = Mist, shape = CircleShape) { Icon(step.first, null, tint = BrandDark, modifier = Modifier.padding(12.dp).size(20.dp)) }
                    Spacer(Modifier.height(8.dp))
                    Text(step.second, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(step.third, color = Muted, fontSize = 8.sp, lineHeight = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveSubscriberHome(provider: Provider, onBrowseProviders: () -> Unit, onLogout: () -> Unit) {
    val context=LocalContext.current.applicationContext
    val repository=remember(context){SupabaseCustomerRepository(context)}
    var selectedNav by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var selectedMeal by remember { mutableStateOf("Lunch") }
    var pauseStartMillis by remember { mutableStateOf<Long?>(null) }
    var pauseDays by remember { mutableIntStateOf(1) }
    var pauseSlot by remember { mutableStateOf("Both") }
    var pauseSummary by remember { mutableStateOf<String?>(null) }
    var showPauseCalendar by remember { mutableStateOf(false) }
    var showWalletScreen by remember { mutableStateOf(false) }
    var showSupportScreen by remember { mutableStateOf(false) }
    var showNotificationsScreen by remember { mutableStateOf(false) }
    var showHomeReviewScreen by remember { mutableStateOf(false) }
    var showHomeRatingCard by remember { mutableStateOf(true) }
    var homeQuickRating by remember { mutableIntStateOf(0) }
    var showDailyMenuChange by remember { mutableStateOf(false) }
    var showPauseScreen by remember { mutableStateOf(false) }
    var showFullWeeklyMenu by remember { mutableStateOf(false) }
    var showSubscribedProviderDetails by remember { mutableStateOf(false) }
    val todayIndex = remember { (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 }
    val tomorrowIndex = (todayIndex + 1) % 7
    val persistedSubscription = CustomerSubscriptionStore.current
    val todayIso = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time) }
    val tomorrowIso = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR,1) }.time) }
    fun persistedMeal(date:String,slot:String):SavedCustomerMeal? = persistedSubscription?.dailyMeals
        ?.firstOrNull { it.serviceDate==date && it.mealSlot.equals(slot,true) && it.itemName.isNotBlank() }
        ?.let { SavedCustomerMeal(it.itemName,"") }
    fun persistedWeeklyMeal(dayIndex:Int,slot:String):SavedCustomerMeal? {
        val rows=persistedSubscription?.weeklyMenu?:return null
        for(index in 0 until rows.length()){
            val row=rows.optJSONObject(index)?:continue
            if(row.optInt("day_of_week")==dayIndex+1&&row.optString("meal_slot").equals(slot,true)){
                val name=row.optString("item_name").trim()
                if(name.isNotBlank())return SavedCustomerMeal(name,"")
            }
        }
        return null
    }
    val todayLunchMenu = remember(provider.weeklyMenu, todayIndex) { providerMealSlot(provider.weeklyMenu, todayIndex, "LUNCH") }
    val todayDinnerMenu = remember(provider.weeklyMenu, todayIndex) { providerMealSlot(provider.weeklyMenu, todayIndex, "DINNER") }
    val tomorrowLunchMenu = remember(provider.weeklyMenu, tomorrowIndex) { providerMealSlot(provider.weeklyMenu, tomorrowIndex, "LUNCH") }
    val tomorrowDinnerMenu = remember(provider.weeklyMenu, tomorrowIndex) { providerMealSlot(provider.weeklyMenu, tomorrowIndex, "DINNER") }
    fun selectedChoice(menu: ProviderMealSlot, saved: SavedCustomerMeal?): MenuChoice =
        (menu.mainCourses.firstOrNull { it.name.equals(saved?.mainCourse,true) }
            ?: menu.mainCourses.firstOrNull()
            ?: saved?.mainCourse?.takeIf { it.isNotBlank() }?.let { MenuChoice(it,Color(0xFFD8D7D2),Brand) }
            ?: MenuChoice("Menu being updated", Color(0xFFD8D7D2), Brand)).let { choice ->
                val fallback = provider.mealPhotoPath.takeUnless { it.equals("null", true) }.orEmpty()
                if (choice.photoPath.isBlank() && fallback.isNotBlank()) choice.copy(photoPath = fallback) else choice
            }
    val savedTodayLunch = persistedMeal(todayIso,"LUNCH") ?: persistedWeeklyMeal(todayIndex,"LUNCH") ?: CustomerMenuStore.lunches[todayIndex]
    val savedTodayDinner = persistedMeal(todayIso,"DINNER") ?: persistedWeeklyMeal(todayIndex,"DINNER") ?: CustomerMenuStore.dinners[todayIndex]
    val savedTomorrowLunch = persistedMeal(tomorrowIso,"LUNCH") ?: persistedWeeklyMeal(tomorrowIndex,"LUNCH") ?: CustomerMenuStore.lunches[tomorrowIndex]
    val savedTomorrowDinner = persistedMeal(tomorrowIso,"DINNER") ?: persistedWeeklyMeal(tomorrowIndex,"DINNER") ?: CustomerMenuStore.dinners[tomorrowIndex]
    var homeLunchChoice by remember(provider.id, tomorrowIndex, savedTomorrowLunch) { mutableStateOf(selectedChoice(tomorrowLunchMenu, savedTomorrowLunch)) }
    var homeDinnerChoice by remember(provider.id, tomorrowIndex, savedTomorrowDinner) { mutableStateOf(selectedChoice(tomorrowDinnerMenu, savedTomorrowDinner)) }
    var homeLunchCarb by remember(provider.id, savedTomorrowLunch) { mutableStateOf(savedTomorrowLunch?.carb ?: tomorrowLunchMenu.carbs.firstOrNull().orEmpty()) }
    var homeDinnerCarb by remember(provider.id, savedTomorrowDinner) { mutableStateOf(savedTomorrowDinner?.carb ?: tomorrowDinnerMenu.carbs.firstOrNull().orEmpty()) }
    var todayLunchChoice by remember(provider.id, todayIndex, savedTodayLunch) { mutableStateOf(selectedChoice(todayLunchMenu, savedTodayLunch)) }
    var todayDinnerChoice by remember(provider.id, todayIndex, savedTodayDinner) { mutableStateOf(selectedChoice(todayDinnerMenu, savedTodayDinner)) }
    var todayLunchCarb by remember(provider.id, savedTodayLunch) { mutableStateOf(savedTodayLunch?.carb ?: todayLunchMenu.carbs.firstOrNull().orEmpty()) }
    var todayDinnerCarb by remember(provider.id, savedTodayDinner) { mutableStateOf(savedTodayDinner?.carb ?: todayDinnerMenu.carbs.firstOrNull().orEmpty()) }
    val homeHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val homeMinute = remember { Calendar.getInstance().get(Calendar.MINUTE) }
    val showingTomorrowMenu = homeHour >= 22 || (homeHour == 0 && homeMinute == 0)
    val homeMenuDate = remember(showingTomorrowMenu) { SimpleDateFormat("EEEE, dd MMM yyyy", Locale.ENGLISH).format(Calendar.getInstance().apply { if (showingTomorrowMenu) add(Calendar.DAY_OF_YEAR, 1) }.time) }

    BackHandler(enabled = showSubscribedProviderDetails) { showSubscribedProviderDetails = false }
    if(showSubscribedProviderDetails){
        ProviderDetailsScreen(provider,onBack={showSubscribedProviderDetails=false},onActivated={},subscriptionView=true)
        return
    }
    BackHandler(enabled = showFullWeeklyMenu) { showFullWeeklyMenu = false }
    if (showFullWeeklyMenu) {
        FullWeeklyMenuScreen(
            tomorrowLunch = homeLunchChoice.name,
            tomorrowDinner = homeDinnerChoice.name,
            tomorrowLunchCarb = homeLunchCarb,
            tomorrowDinnerCarb = homeDinnerCarb,
            onBack = { showFullWeeklyMenu = false },
            onEditTomorrow = { slot -> selectedMeal = slot; showFullWeeklyMenu = false; showDailyMenuChange = true },
            onPauseMeals = { showFullWeeklyMenu = false; showPauseScreen = true }
        )
        return
    }
    BackHandler(enabled = showPauseScreen) { showPauseScreen = false }
    if (showPauseScreen) {
        PauseMealsScreen(
            onBack = { showPauseScreen = false },
            onConfirm = { dates,slot,summary ->
                val subscriptionId=persistedSubscription?.id.orEmpty()
                if(subscriptionId.isBlank())pauseSummary="Your active subscription could not be found."
                else repository.pauseMeals(subscriptionId,dates,slot){result,error->pauseSummary=if(error!=null)"Pause was not saved: $error" else "$summary · ${result?.optInt("updated_meals")?:0} meals updated"}
                showPauseScreen = false
            }
        )
        return
    }
    BackHandler(enabled = showDailyMenuChange) { showDailyMenuChange = false }
    if (showDailyMenuChange) {
        val changeTargetsTomorrow = if (selectedMeal == "Lunch") homeHour >= 15 else homeHour >= 22
        DailyMenuChangeScreen(
            provider = provider,
            slot = selectedMeal,
            currentChoice = if (changeTargetsTomorrow) { if (selectedMeal == "Lunch") homeLunchChoice else homeDinnerChoice } else { if (selectedMeal == "Lunch") todayLunchChoice else todayDinnerChoice },
            currentCarb = if (changeTargetsTomorrow) { if (selectedMeal == "Lunch") homeLunchCarb else homeDinnerCarb } else { if (selectedMeal == "Lunch") todayLunchCarb else todayDinnerCarb },
            onBack = { showDailyMenuChange = false },
            onSave = { choice, carb ->
                if (changeTargetsTomorrow) {
                    if (selectedMeal == "Lunch") { homeLunchChoice = choice; homeLunchCarb = carb; CustomerMenuStore.lunches[tomorrowIndex] = SavedCustomerMeal(choice.name, carb) }
                    else { homeDinnerChoice = choice; homeDinnerCarb = carb; CustomerMenuStore.dinners[tomorrowIndex] = SavedCustomerMeal(choice.name, carb) }
                } else {
                    if (selectedMeal == "Lunch") { todayLunchChoice = choice; todayLunchCarb = carb; CustomerMenuStore.lunches[todayIndex] = SavedCustomerMeal(choice.name, carb) }
                    else { todayDinnerChoice = choice; todayDinnerCarb = carb; CustomerMenuStore.dinners[todayIndex] = SavedCustomerMeal(choice.name, carb) }
                }
                val targetDate=if(changeTargetsTomorrow)tomorrowIso else todayIso
                val mealId=persistedSubscription?.dailyMeals?.firstOrNull{it.serviceDate==targetDate&&it.mealSlot.equals(selectedMeal,true)}?.id
                if(!mealId.isNullOrBlank()&&choice.id.isNotBlank())repository.changeDailyMeal(mealId,choice.id){_,error->if(error!=null)pauseSummary="Menu update was not saved: $error"}
            }
        )
        return
    }
    BackHandler(enabled = showHomeReviewScreen) { showHomeReviewScreen = false }
    if (showHomeReviewScreen) {
        RatingReviewScreen(
            provider = provider,
            meal = "Dal Tadka",
            initialRating = homeQuickRating,
            onBack = { showHomeReviewScreen = false },
            onSupport = { showHomeReviewScreen = false; showSupportScreen = true },
            onSubmitted = { showHomeReviewScreen = false; showHomeRatingCard = false }
        )
        return
    }
    BackHandler(enabled = showNotificationsScreen) { showNotificationsScreen = false }
    if (showNotificationsScreen) {
        NotificationCentreScreen(
            onBack = { showNotificationsScreen = false },
            onDestination = { destination ->
                showNotificationsScreen = false
                when (destination) {
                    "wallet" -> showWalletScreen = true
                    "orders" -> selectedNav = 2
                    "plan" -> selectedNav = 1
                    "support" -> showSupportScreen = true
                    "weekly_menu" -> showFullWeeklyMenu = true
                    "profile" -> selectedNav = 3
                }
            }
        )
        return
    }
    BackHandler(enabled = showWalletScreen) { showWalletScreen = false }
    if (showWalletScreen) {
        WalletScreen(onBack = { showWalletScreen = false })
        return
    }
    if (showSupportScreen) {
        SupportCentreScreen(onBack = { showSupportScreen = false })
        return
    }
    when (selectedNav) {
        1 -> { MyPlanScreen(provider, onNav = { selectedNav = it }, onSupport = { showSupportScreen = true }, onWeeklyMenu = { showFullWeeklyMenu = true }, onProviderDetails={showSubscribedProviderDetails=true}, onBrowseProviders = onBrowseProviders); return }
        2 -> { OrdersScreen(provider, onNav = { selectedNav = it }, onSupport = { showSupportScreen = true }); return }
        3 -> {
            ProfileScreen(
                providerName = provider.name,
                onNav = { selectedNav = it },
                onWallet = { showWalletScreen = true },
                onSupport = { showSupportScreen = true },
                onBrowseProviders = onBrowseProviders,
                onLogout = onLogout
            )
            return
        }
    }

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        bottomBar = { ZomealBottomBar(selectedNav) { index ->
            selectedNav = index
        } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { SubscriberHeader(provider, onNotifications = { showNotificationsScreen = true }, onWallet = { showWalletScreen = true }) }
            pauseSummary?.let { summary -> item { PausedSubscriptionBanner(summary) { pauseSummary = null } } }
            item { TodayMenuHeader(showTomorrow = showingTomorrowMenu, date = homeMenuDate) { showFullWeeklyMenu = true } }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (CustomerMenuStore.packageKind != "DINNER_ONLY") {
                        DailyMealCard(
                            slot = "Lunch",
                            meal = (if (showingTomorrowMenu) homeLunchChoice else todayLunchChoice).name,
                            sides = (listOf(if (showingTomorrowMenu) homeLunchCarb else todayLunchCarb) + (if (showingTomorrowMenu) tomorrowLunchMenu.included else todayLunchMenu.included)).filter { it.isNotBlank() }.joinToString(" · "),
                            accent = Color(0xFF16834A),
                            choice = if (showingTomorrowMenu) homeLunchChoice else todayLunchChoice,
                            calories = 542,
                            protein = 18,
                            carbs = 72,
                            fat = 18,
                            onInfo = { selectedMeal = "Lunch"; dialog = "meal_info" },
                            onCancel = {
                                selectedMeal = "Lunch"
                                if (showingTomorrowMenu || homeHour < 7) dialog = "cancel"
                                else pauseSummary = "Lunch cancellation closed at 7:00 AM. Today's lunch is already finalized."
                            },
                            onChange = { selectedMeal = "Lunch"; showDailyMenuChange = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (CustomerMenuStore.packageKind != "LUNCH_ONLY") {
                        DailyMealCard(
                            slot = "Dinner",
                            meal = (if (showingTomorrowMenu) homeDinnerChoice else todayDinnerChoice).name,
                            sides = (listOf(if (showingTomorrowMenu) homeDinnerCarb else todayDinnerCarb) + (if (showingTomorrowMenu) tomorrowDinnerMenu.included else todayDinnerMenu.included)).filter { it.isNotBlank() }.joinToString(" · "),
                            accent = Color(0xFF6546A8),
                            choice = if (showingTomorrowMenu) homeDinnerChoice else todayDinnerChoice,
                            calories = 456,
                            protein = 14,
                            carbs = 64,
                            fat = 16,
                            onInfo = { selectedMeal = "Dinner"; dialog = "meal_info" },
                            onCancel = {
                                selectedMeal = "Dinner"
                                if (showingTomorrowMenu || homeHour < 16) dialog = "cancel"
                                else pauseSummary = "Dinner cancellation closed at 4:00 PM. Today's dinner is already finalized."
                            },
                            onChange = { selectedMeal = "Dinner"; showDailyMenuChange = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            item { NextMealCard(provider.name) }
            if (showHomeRatingCard) {
                item {
                    HomeMealRatingCard(
                        rating = homeQuickRating,
                        onRating = { homeQuickRating = it },
                        onReview = { showHomeReviewScreen = true },
                        onDismiss = { showHomeRatingCard = false }
                    )
                }
            }
            item { NutritionOverview { dialog = "daily_nutrition" } }
            item {
                SubscriberQuickActions(
                    onPause = { showPauseScreen = true },
                    onPlan = { selectedNav = 1 },
                    onOrders = { selectedNav = 2 },
                    onSupport = { showSupportScreen = true }
                )
            }
        }
    }

    if (showPauseCalendar) {
        val today = remember { System.currentTimeMillis().let { it - it % 86_400_000L } }
        val state = rememberDatePickerState(initialSelectedDateMillis = today, selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today
        })
        DatePickerDialog(
            onDismissRequest = { showPauseCalendar = false },
            confirmButton = { TextButton(onClick = { pauseStartMillis = state.selectedDateMillis ?: today; showPauseCalendar = false; dialog = "pause_options" }) { Text("Next", color = BrandDark, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showPauseCalendar = false }) { Text("Cancel", color = Muted) } }
        ) { DatePicker(state = state, modifier = Modifier.height(405.dp), showModeToggle = false, headline = null, title = { Text("Pause from which date?", Modifier.padding(24.dp, 16.dp, 0.dp, 6.dp), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }) }
    }

    HomeActionDialog(
        type = dialog,
        provider = provider,
        meal = selectedMeal,
        pauseStartMillis = pauseStartMillis,
        pauseDays = pauseDays,
        pauseSlot = pauseSlot,
        onPauseDays = { pauseDays = it },
        onPauseSlot = { pauseSlot = it },
        onDismiss = { dialog = null },
        onPauseConfirm = {
            val date = SimpleDateFormat("dd MMM", Locale.ENGLISH).format(pauseStartMillis ?: System.currentTimeMillis())
            pauseSummary = "$pauseSlot meals paused from $date for $pauseDays day${if (pauseDays > 1) "s" else ""}"
            dialog = null
        },
        onMealCancelConfirm = {
            val subscriptionId = persistedSubscription?.id.orEmpty()
            val targetDate = if (showingTomorrowMenu) tomorrowIso else todayIso
            if (subscriptionId.isBlank()) {
                pauseSummary = "Your active subscription could not be found. Please sign in again."
                dialog = null
            } else repository.pauseMeals(subscriptionId, listOf(targetDate), selectedMeal) { result, error ->
                pauseSummary = if (error != null) "Cancellation was not saved: $error"
                else "$selectedMeal cancelled for ${if (showingTomorrowMenu) "tomorrow" else "today"}. ${result?.optInt("updated_meals") ?: 1} meal updated."
                dialog = null
            }
        }
    )
}

@Composable
private fun PausedSubscriptionBanner(summary: String, onResume: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        color = Color(0xFFFFF5E8),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0C98C))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PauseCircle, null, tint = Color(0xFFB76B16), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Your subscription is paused", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                Text(summary, color = Muted, fontSize = 9.sp)
            }
            TextButton(onClick = onResume) { Text("Resume", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun HomeActionDialog(
    type: String?,
    provider: Provider,
    meal: String,
    pauseStartMillis: Long?,
    pauseDays: Int,
    pauseSlot: String,
    onPauseDays: (Int) -> Unit,
    onPauseSlot: (String) -> Unit,
    onDismiss: () -> Unit,
    onPauseConfirm: () -> Unit,
    onMealCancelConfirm: () -> Unit
) {
    if (type == null) return
    var selection by remember(type, meal) { mutableStateOf("") }
    val isLunch = meal == "Lunch"
    val title = when (type) {
        "meal_info" -> "$meal meal information"
        "pause_options" -> "Pause your meals"
        "plan" -> "My Active Plan"
        "orders" -> "Order History"
        "support" -> "Zomeal Support"
        "track" -> "Track Your Order"
        "full_week" -> "Your Weekly Menu"
        "daily_nutrition" -> "Today's Nutrition"
        "notifications" -> "Notifications"
        "wallet" -> "Zomeal Wallet"
        "profile" -> "Your Profile"
        "cancel" -> "Cancel $meal"
        "change" -> "Change $meal Menu"
        else -> "Zomeal"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(when (type) {
            "meal_info" -> Icons.Outlined.Info
            "pause_options" -> Icons.Outlined.PauseCircle
            "plan" -> Icons.Outlined.CalendarMonth
            "orders" -> Icons.Outlined.ShoppingBag
            "support" -> Icons.Outlined.SupportAgent
            "track" -> Icons.Outlined.LocalShipping
            "full_week" -> Icons.Outlined.DateRange
            "daily_nutrition" -> Icons.Outlined.MonitorHeart
            "notifications" -> Icons.Outlined.Notifications
            "wallet" -> Icons.Outlined.AccountBalanceWallet
            "profile" -> Icons.Outlined.Person
            else -> Icons.Outlined.RestaurantMenu
        }, null, tint = Brand) },
        title = { Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold) },
        text = {
            when (type) {
                "meal_info" -> MealInformationContent(
                    meal = if (isLunch) "Paneer Butter Masala" else "Seasonal Mix Veg",
                    calories = if (isLunch) 542 else 456,
                    protein = if (isLunch) 18 else 14,
                    carbs = if (isLunch) 72 else 64,
                    fat = if (isLunch) 18 else 16
                )
                "pause_options" -> PauseOptionsContent(pauseStartMillis, pauseDays, pauseSlot, onPauseDays, onPauseSlot)
                "plan" -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(provider.name, color = Ink, fontWeight = FontWeight.Bold)
                    Text("Monthly · Lunch + Dinner · 30 days", color = Muted, fontSize = 11.sp)
                    Text("18 days remaining · Ends 22 Sep 2026", color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Free delivery · Pause anytime · Weekly menu customizable", color = Muted, fontSize = 10.sp)
                }
                "orders" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Today · Lunch · Preparing", "Yesterday · Lunch + Dinner · Delivered", "22 Aug · Lunch + Dinner · Delivered", "21 Aug · Lunch + Dinner · Delivered").forEach {
                        Surface(color = Mist, shape = RoundedCornerShape(11.dp)) { Text(it, Modifier.fillMaxWidth().padding(10.dp), color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium) }
                    }
                }
                "support" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Chat with support", "Request a callback", "Email support").forEach { option ->
                        Surface(Modifier.fillMaxWidth().clickable { selection = "$option selected. Our team will respond shortly." }, color = Mist, shape = RoundedCornerShape(11.dp)) {
                            Text(option, Modifier.padding(11.dp), color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (selection.isNotBlank()) Text(selection, color = BrandDark, fontSize = 9.sp)
                }
                "track" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Lunch is being prepared", color = BrandDark, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { .55f }, modifier = Modifier.fillMaxWidth(), color = Brand, trackColor = Border)
                    Text("Expected delivery: 12:00 PM – 2:00 PM\nDelivered by ${provider.name}", color = Muted, fontSize = 11.sp)
                }
                "full_week" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Mon · Paneer Masala / Mix Veg", "Tue · Dal Tadka / Chana Masala", "Wed · Chana Masala / Egg Tadka", "Thu · Kadai Paneer / Mix Veg", "Fri · Rajma / Dal Tadka", "Sat · Paneer Masala / Chana", "Sun · Mix Veg / Egg Tadka").forEach {
                        Surface(color = Mist, shape = RoundedCornerShape(9.dp)) { Text(it, Modifier.fillMaxWidth().padding(8.dp), color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Medium) }
                    }
                }
                "daily_nutrition" -> MealInformationContent("Lunch + Dinner total", 998, 32, 136, 34)
                "notifications" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Your lunch is being prepared", "Weekly menu saved successfully", "Payment received and plan activated").forEach {
                        Surface(color = Mist, shape = RoundedCornerShape(10.dp)) { Text(it, Modifier.fillMaxWidth().padding(10.dp), color = Ink, fontSize = 10.sp) }
                    }
                }
                "wallet" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Available balance", color = Muted, fontSize = 10.sp)
                    Text("₹1,250", color = BrandDark, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("Wallet credits are automatically applied to eligible renewals and refunds.", color = Muted, fontSize = 10.sp)
                }
                "profile" -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Ashutosh Nayak", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text("+91 98XXXXXX42", color = Muted, fontSize = 10.sp)
                    Text("Khandagiri, Bhubaneswar · 751030", color = Muted, fontSize = 10.sp)
                    Text("Pure Veg preference · Notifications enabled", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                "cancel" -> Text("Cancel today's $meal delivery? This will not pause the rest of your plan.", color = Muted, fontSize = 11.sp)
                "change" -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Choose another main course", color = Muted, fontSize = 11.sp)
                    val options = if (isLunch) listOf("Dal Tadka", "Chicken Curry", "Fish Masala") else listOf("Chana Masala", "Egg Tadka", "Chicken Masala")
                    options.forEach { option ->
                        Surface(Modifier.fillMaxWidth().clickable { selection = option }, color = if (selection == option) Mist else Color.White, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (selection == option) Brand else Border)) {
                            Text(option, Modifier.padding(10.dp), color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = when (type) {
                    "pause_options" -> onPauseConfirm
                    "cancel" -> onMealCancelConfirm
                    else -> onDismiss
                },
                colors = ButtonDefaults.buttonColors(containerColor = Brand),
                shape = RoundedCornerShape(11.dp)
            ) { Text(when (type) { "pause_options" -> "Pause Meals"; "cancel" -> "Confirm Cancel"; "change" -> "Save Menu"; else -> "Done" }, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        },
        dismissButton = if (type in listOf("pause_options", "cancel", "change")) ({ TextButton(onClick = onDismiss) { Text("Back", color = Muted) } }) else null
    )
}

@Composable
private fun MealInformationContent(meal: String, calories: Int, protein: Int, carbs: Int, fat: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(meal, color = BrandDark, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        Surface(color = Mist, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(11.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                CompactMacro("$calories", "kcal"); CompactMacro("${protein}g", "protein"); CompactMacro("${carbs}g", "carbs"); CompactMacro("${fat}g", "fat")
            }
        }
        Text("Allergen guidance", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("Prepared in a kitchen that may handle dairy, nuts, gluten and soy. Contact support for severe allergies.", color = Muted, fontSize = 10.sp)
        Text("Important instructions", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("Consume within 2 hours of delivery. Refrigerate leftovers immediately. Reheat only once.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun PauseOptionsContent(startMillis: Long?, days: Int, slot: String, onDays: (Int) -> Unit, onSlot: (String) -> Unit) {
    val date = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(startMillis ?: System.currentTimeMillis())
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Starting $date", color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("For how many days?", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 3, 7).forEach { count -> FilterChip(selected = days == count, onClick = { onDays(count) }, label = { Text("$count", fontSize = 9.sp) }) }
        }
        Text("Which meals?", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Lunch", "Dinner", "Both").forEach { meal -> FilterChip(selected = slot == meal, onClick = { onSlot(meal) }, label = { Text(meal, fontSize = 9.sp) }) }
        }
        Text("Paused meals will not be delivered and the unused days will be added to the end of your plan.", color = Muted, fontSize = 9.sp)
    }
}

private data class ZomealNotification(
    val id: Int,
    val category: String,
    val title: String,
    val message: String,
    val time: String,
    val destination: String,
    val icon: ImageVector,
    val read: Boolean = false
)

@Composable
private fun NotificationCentreScreen(onBack: () -> Unit, onDestination: (String) -> Unit) {
    val notifications = remember {
        mutableStateListOf(
            ZomealNotification(1, "Delivery", "Lunch is being prepared", "Swaad Ghar has started preparing Paneer Butter Masala. Track its progress.", "2 min ago", "orders", Icons.Outlined.LocalShipping),
            ZomealNotification(2, "Payment", "Wallet refund received", "₹300 has been credited to your Zomeal Wallet for a paused meal.", "24 min ago", "wallet", Icons.Outlined.AccountBalanceWallet),
            ZomealNotification(3, "Menu", "Tomorrow’s menu closes soon", "Change tomorrow’s lunch or dinner before the 8:00 PM cut-off.", "1 hr ago", "weekly_menu", Icons.Outlined.RestaurantMenu),
            ZomealNotification(4, "Plan", "Your plan has 18 days left", "Review your plan, upcoming menus and renewal information.", "Today · 9:10 AM", "plan", Icons.Outlined.CalendarMonth, true),
            ZomealNotification(5, "Support", "Support ticket updated", "Our team replied to ticket ZM-1084 about your meal quality report.", "Yesterday", "support", Icons.Outlined.SupportAgent, true),
            ZomealNotification(6, "Reward", "You earned a referral reward", "₹150 is ready in your wallet after your friend’s first subscription.", "10 Aug", "wallet", Icons.Outlined.CardGiftcard, true),
            ZomealNotification(7, "Account", "Delivery address saved", "Your serviceable Home address was securely added to your profile.", "9 Aug", "profile", Icons.Outlined.LocationOn, true)
        )
    }
    var filter by remember { mutableStateOf("All") }
    val visible = notifications.filter { filter == "All" || !it.read }
    val unread = notifications.count { !it.read }
    BackHandler(onBack = onBack)

    Scaffold(containerColor = Color(0xFFFAFCFA)) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { NotificationHeader(unread, onBack) { notifications.indices.forEach { index -> notifications[index] = notifications[index].copy(read = true) } } }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Unread").forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(if (option == "Unread") "Unread ($unread)" else "All notifications", fontSize = 9.sp) }, modifier = Modifier.weight(1f), leadingIcon = if (filter == option) ({ Icon(Icons.Filled.Check, null, modifier = Modifier.size(13.dp)) }) else null)
                    }
                }
            }
            if (visible.isEmpty()) item { NotificationEmptyState() }
            visible.forEachIndexed { index, notification ->
                if (index == 0 || notification.time.startsWith("Yesterday") || notification.time.startsWith("10 ")) item { Text(if (index == 0) "Recent" else "Earlier", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 18.dp, top = 3.dp)) }
                item(key = notification.id) {
                    NotificationItem(
                        notification = notification,
                        onClick = {
                            val actualIndex = notifications.indexOfFirst { it.id == notification.id }
                            if (actualIndex >= 0) notifications[actualIndex] = notification.copy(read = true)
                            onDestination(notification.destination)
                        },
                        onReadToggle = {
                            val actualIndex = notifications.indexOfFirst { it.id == notification.id }
                            if (actualIndex >= 0) notifications[actualIndex] = notification.copy(read = !notification.read)
                        }
                    )
                }
            }
            item { NotificationPreferenceHint { onDestination("profile") } }
        }
    }
}

@Composable
private fun NotificationHeader(unread: Int, onBack: () -> Unit, onMarkAllRead: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(138.dp).background(Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(900f, 380f)), RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp))) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 10.dp, top = 12.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("zomeal", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Text("Notifications", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(if (unread == 0) "You’re all caught up" else "$unread updates need your attention", color = Color.White.copy(alpha = .88f), fontSize = 9.sp)
        }
        if (unread > 0) TextButton(onClick = onMarkAllRead, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 9.dp, bottom = 5.dp)) { Text("Mark all read", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun NotificationItem(notification: ZomealNotification, onClick: () -> Unit, onReadToggle: () -> Unit) {
    val accent = when (notification.category) { "Payment", "Reward" -> Color(0xFF0B7D47); "Delivery" -> Color(0xFF1674A5); "Menu" -> Color(0xFF9A6A00); "Support" -> Color(0xFF6546A8); else -> Brand }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onClick),
        color = if (notification.read) Color.White else accent.copy(alpha = .055f),
        shape = RoundedCornerShape(17.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (notification.read) Border else accent.copy(alpha = .25f)),
        shadowElevation = if (notification.read) 0.dp else 2.dp
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box {
                Surface(color = accent.copy(alpha = .11f), shape = CircleShape) { Icon(notification.icon, null, tint = accent, modifier = Modifier.padding(10.dp).size(18.dp)) }
                if (!notification.read) Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFE53935)).align(Alignment.TopEnd))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(notification.category, color = accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(notification.time, color = Muted, fontSize = 7.sp) }
                Text(notification.title, color = Ink, fontSize = 11.sp, fontWeight = if (notification.read) FontWeight.Bold else FontWeight.ExtraBold)
                Text(notification.message, color = Muted, fontSize = 8.sp, lineHeight = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(when (notification.destination) { "wallet" -> "Open wallet"; "orders" -> "View order"; "weekly_menu" -> "Review menu"; "plan" -> "View plan"; "support" -> "View support reply"; else -> "Open profile" }, color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold); Icon(Icons.Filled.KeyboardArrowRight, null, tint = Brand, modifier = Modifier.size(14.dp)) }
            }
            IconButton(onClick = onReadToggle, modifier = Modifier.size(30.dp)) { Icon(if (notification.read) Icons.Outlined.MarkEmailUnread else Icons.Outlined.DoneAll, if (notification.read) "Mark unread" else "Mark read", tint = Muted, modifier = Modifier.size(15.dp)) }
        }
    }
}

@Composable private fun NotificationEmptyState() { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.NotificationsNone, null, tint = Brand, modifier = Modifier.size(30.dp)); Spacer(Modifier.height(7.dp)); Text("No unread notifications", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text("New meal and account updates will appear here.", color = Muted, fontSize = 8.sp) } } }

@Composable private fun NotificationPreferenceHint(onClick: () -> Unit) { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onClick), color = Mist, shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.NotificationsActive, null, tint = Brand, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text("Choose what Zomeal sends you", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("Manage meal, payment and offer alerts in Profile.", color = Muted, fontSize = 8.sp) }; Icon(Icons.Filled.KeyboardArrowRight, null, tint = Brand, modifier = Modifier.size(16.dp)) } } }

@Composable
private fun WalletScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { SupabaseCustomerRepository(context.applicationContext) }
    var balance by remember { mutableIntStateOf(0) }
    var showAddMoney by remember { mutableStateOf(false) }
    var walletMessage by remember { mutableStateOf<String?>(null) }
    val sharedChannels = remember { mutableStateMapOf<String, Boolean>() }
    var referralEarned by remember { mutableIntStateOf(0) }
    var friendsJoined by remember { mutableIntStateOf(0) }
    var friendsRewarded by remember { mutableIntStateOf(0) }
    var referralCode by remember { mutableStateOf("") }
    var shareLink by remember { mutableStateOf("https://zomeal.in") }
    var referrerReward by remember { mutableIntStateOf(0) }
    var referredReward by remember { mutableIntStateOf(0) }
    var rewardLimit by remember { mutableIntStateOf(1000) }
    var activity by remember { mutableStateOf(JSONArray()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        repository.referralDashboard { data, error ->
            loading = false
            if (data != null) {
                balance = (data.optLong("balance_paise") / 100).toInt()
                referralEarned = (data.optLong("lifetime_referral_earned_paise") / 100).toInt()
                friendsJoined = data.optInt("friends_joined")
                friendsRewarded = data.optInt("friends_rewarded")
                referralCode = data.optString("referral_code")
                shareLink = data.optString("share_link", "https://zomeal.in/?ref=$referralCode")
                referrerReward = (data.optLong("referrer_reward_paise") / 100).toInt()
                referredReward = (data.optLong("referred_reward_paise") / 100).toInt()
                rewardLimit = (data.optLong("cycle_cap_paise") / 100).toInt()
                activity = data.optJSONArray("activity") ?: JSONArray()
            } else walletMessage = error ?: "Could not load your wallet. Please try again."
        }
    }
    fun shareReferral(channel: String) {
        if (referralCode.isBlank()) { walletMessage = "Your referral code is still loading."; return }
        val text = "Join Zomeal for home-style meals. Use my referral code $referralCode. You can earn ₹$referredReward after your first successful paid subscription: $shareLink"
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share Zomeal via $channel"))
        sharedChannels[channel] = true
    }

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 8.dp) {
                Button(
                    onClick = { showAddMoney = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand)
                ) {
                    Icon(Icons.Outlined.AddCard, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add Money to Wallet", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { WalletHeader(balance, onBack, onAddMoney = { showAddMoney = true }) }
            walletMessage?.let { message -> item { WalletMessageBanner(message) { walletMessage = null } } }
            item { WalletQuickFacts(referralEarned, rewardLimit, friendsJoined) }
            item { ReferAndEarnCard(sharedChannels, referralCode, referrerReward, referredReward, rewardLimit, referralEarned, friendsRewarded, onCopy = {
                if (referralCode.isNotBlank()) {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Zomeal referral code", referralCode))
                    walletMessage = "Referral code copied."
                }
            }, onShare = ::shareReferral) }
            item { WalletTransactions(activity, loading) }
            item { WalletSecurityStrip() }
        }
    }

    if (showAddMoney) {
        AlertDialog(
            onDismissRequest = { showAddMoney = false },
            icon = { Icon(Icons.Outlined.AccountBalanceWallet, null, tint = Brand) },
            title = { Text("Add Money", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("Wallet recharge is coming soon.", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("For safety, only verified Razorpay payments and referral rewards can change this balance. No demo money will be added.", color = Muted, fontSize = 10.sp)
                }
            },
            confirmButton = {
                Button(onClick = { showAddMoney = false }, colors = ButtonDefaults.buttonColors(containerColor = Brand), shape = RoundedCornerShape(12.dp)) { Text("Got it", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showAddMoney = false }) { Text("Cancel", color = Muted) } }
        )
    }
}

@Composable
private fun WalletHeader(balance: Int, onBack: () -> Unit, onAddMoney: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(218.dp).background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(950f, 520f)),
            RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
        )
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 10.dp, top = 12.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("zomeal wallet", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            Surface(color = Color.White.copy(alpha = .15f), shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .25f))) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color.White, shape = CircleShape) { Icon(Icons.Outlined.AccountBalanceWallet, null, tint = Brand, modifier = Modifier.padding(10.dp).size(22.dp)) }
                    Spacer(Modifier.width(13.dp))
                    Column {
                        Text("Available balance", color = Color.White.copy(alpha = .82f), fontSize = 9.sp)
                        Text("₹${"%,d".format(balance)}", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            TextButton(onClick = onAddMoney) { Text("+ Recharge wallet", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun WalletMessageBanner(message: String, onDismiss: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = Brand, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(message, color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) { Icon(Icons.Filled.Close, null, modifier = Modifier.size(14.dp)) }
        }
    }
}

@Composable
private fun WalletQuickFacts(referral: Int, rewardLimit: Int, friendsJoined: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        WalletFactCard(Icons.Outlined.CardGiftcard, "Referral earned", "₹$referral", Modifier.weight(1f))
        WalletFactCard(Icons.Outlined.Savings, "Reward limit", "₹$rewardLimit", Modifier.weight(1f))
        WalletFactCard(Icons.Outlined.Groups, "Friends joined", friendsJoined.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun WalletFactCard(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(15.dp), shadowElevation = 1.dp) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Brand, modifier = Modifier.size(18.dp)); Spacer(Modifier.height(5.dp))
            Text(value, color = BrandDark, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text(label, color = Muted, fontSize = 7.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ReferAndEarnCard(sharedChannels: Map<String, Boolean>, referralCode: String, referrerReward: Int, referredReward: Int, rewardLimit: Int, earned: Int, friendsRewarded: Int, onCopy: () -> Unit, onShare: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFF1FAEE), shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCFE7C7))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Brand, shape = CircleShape) { Icon(Icons.Outlined.Campaign, null, tint = Color.White, modifier = Modifier.padding(11.dp).size(21.dp)) }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Refer & Earn up to ₹${"%,d".format(rewardLimit)}", color = BrandDark, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    Text("You get ₹$referrerReward and your friend gets ₹$referredReward after their first successful paid subscription.", color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
                }
                Icon(Icons.Outlined.Savings, null, tint = Color(0xFFFFA000), modifier = Modifier.size(34.dp))
            }
            Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onCopy).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Your referral code", color = Muted, fontSize = 8.sp); Text(referralCode.ifBlank { "Loading…" }, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
                    Icon(Icons.Outlined.ContentCopy, null, tint = Brand, modifier = Modifier.size(17.dp))
                }
            }
            Text("Share using", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    Triple("WhatsApp", Icons.Outlined.Chat, Color(0xFF25A85A)),
                    Triple("Instagram", Icons.Outlined.PhotoCamera, Color(0xFFC13584)),
                    Triple("Messages", Icons.Outlined.Sms, Color(0xFF3578E5)),
                    Triple("More", Icons.Outlined.Share, Brand)
                ).forEach { channel ->
                    Column(Modifier.weight(1f).clickable { onShare(channel.first) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(color = channel.third.copy(alpha = .1f), shape = CircleShape) { Icon(if (sharedChannels[channel.first] == true) Icons.Filled.Check else channel.second, null, tint = channel.third, modifier = Modifier.padding(9.dp).size(18.dp)) }
                        Text(channel.first, color = Muted, fontSize = 7.sp, maxLines = 1)
                    }
                }
            }
            val progress = if (rewardLimit <= 0) 0f else (earned.toFloat() / rewardLimit).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Brand, trackColor = Border)
            Text("₹$earned earned · $friendsRewarded qualified friend${if (friendsRewarded == 1) "" else "s"} · ₹${(rewardLimit-earned).coerceAtLeast(0)} available", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AffiliateEarningsCard(earned: Int, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(19.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Insights, null, tint = Brand, modifier = Modifier.size(23.dp)); Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) { Text("Zomeal Affiliate Earnings", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold); Text("Earn commission by promoting verified subscriptions", color = Muted, fontSize = 8.sp) }
                Text("₹$earned", color = BrandDark, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
            Row {
                AffiliateMetric("12", "Link clicks", Modifier.weight(1f)); AffiliateMetric("4", "Sign-ups", Modifier.weight(1f)); AffiliateMetric("2", "Paid plans", Modifier.weight(1f)); AffiliateMetric("₹390", "Pending", Modifier.weight(1f))
            }
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(38.dp), shape = RoundedCornerShape(13.dp)) { Text("View Affiliate Dashboard", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun AffiliateMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text(value, color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold); Text(label, color = Muted, fontSize = 7.sp, maxLines = 1) }
}

@Composable
private fun WalletTransactions(activity: JSONArray, loading: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Text("Recent Wallet Activity", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(17.dp), shadowElevation = 1.dp) {
            Column {
                when {
                    loading -> Text("Loading activity…", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(18.dp))
                    activity.length() == 0 -> Text("No wallet activity yet. Share your code to start earning.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(18.dp))
                    else -> (0 until activity.length()).forEach { index ->
                        val entry = activity.optJSONObject(index) ?: JSONObject()
                        val amount = (entry.optLong("amount_paise") / 100).toInt()
                        WalletTransaction(Icons.Outlined.CardGiftcard, if (entry.optString("type").startsWith("REFERR")) "Referral reward" else "Wallet activity", entry.optString("description", "Verified Zomeal activity"), "${if (amount >= 0) "+" else "−"} ₹${kotlin.math.abs(amount)}", if (amount >= 0) BrandDark else Ink)
                        if (index < activity.length() - 1) HorizontalDivider(color = Border)
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletTransaction(icon: ImageVector, title: String, subtitle: String, amount: String, amountColor: Color) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Mist, shape = CircleShape) { Icon(icon, null, tint = Brand, modifier = Modifier.padding(8.dp).size(16.dp)) }
        Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(title, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 8.sp) }
        Text(amount, color = amountColor, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun WalletSecurityStrip() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        SecurityMiniFact(Icons.Outlined.VerifiedUser, "Secure wallet")
        SecurityMiniFact(Icons.Outlined.AccountBalance, "RBI-compliant partner")
        SecurityMiniFact(Icons.Outlined.Lock, "Encrypted payments")
    }
}

@Composable
private fun AppSectionHeader(title: String, subtitle: String, icon: ImageVector, onBack: (() -> Unit)? = null) {
    Box(
        Modifier.fillMaxWidth().height(132.dp).background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(900f, 360f)),
            RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp)
        )
    ) {
        onBack?.let { back -> IconButton(onClick = back, modifier = Modifier.padding(start = 10.dp, top = 12.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp)) } }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("zomeal", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(5.dp)); Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Color.White.copy(alpha = .86f), fontSize = 9.sp)
        }
        Surface(color = Color.White.copy(alpha = .15f), shape = CircleShape, modifier = Modifier.padding(end = 16.dp).align(Alignment.CenterEnd)) { Icon(icon, null, tint = Color.White, modifier = Modifier.padding(9.dp).size(18.dp)) }
    }
}

@Composable
private fun MyPlanScreen(provider: Provider, onNav: (Int) -> Unit, onSupport: () -> Unit, onWeeklyMenu: () -> Unit, onProviderDetails:()->Unit, onBrowseProviders: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { SupabaseCustomerRepository(context) }
    var message by remember { mutableStateOf<String?>(null) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var confirmCancellation by remember { mutableStateOf(false) }
    BackHandler { onNav(0) }
    Scaffold(containerColor = Color(0xFFFAFCFA), bottomBar = { ZomealBottomBar(1, onNav) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item { AppSectionHeader("My Plan", "Manage your active meal subscription", Icons.Outlined.CalendarMonth) { onNav(0) } }
            message?.let { item { WalletMessageBanner(it) { message = null } } }
            item { MyPlanHero(provider,onProviderDetails) }
            item { PlanTimelineCard() }
            item { Text("Weekly Menu", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp)) }
            item { MyPlanWeekPreview(onWeeklyMenu) }
            item { PlanDeliveryAddress { message = "Delivery address editor opened. Serviceability will be checked before saving." } }
            item { PlanManagementActions(onPause = { message = "Pause controls opened from Home." }, onChangeProvider = onBrowseProviders, onCancel = { showCancelDialog = true }, onSupport = onSupport) }
            item { PlanPaymentSummary() }
            item { TextButton(onClick = { message = "Cancellation policy opened. No change has been made to your plan." }, modifier = Modifier.fillMaxWidth()) { Text("Cancellation, pause & refund policy", color = BrandDark, fontSize = 10.sp) } }
        }
    }
    if (showCancelDialog && !confirmCancellation) AlertDialog(
        onDismissRequest = { showCancelDialog = false },
        icon = { Icon(Icons.Outlined.SwapHoriz, null, tint = Brand) },
        title = { Text("Would you rather change provider?", fontWeight = FontWeight.ExtraBold) },
        text = { Text("You can keep your active plan and choose another approved provider, package and seven-day menu. Any eligible balance will be settled from your Zomeal wallet—no new payment screen is needed.", color = Muted, fontSize = 11.sp) },
        confirmButton = { Button(onClick = { showCancelDialog = false; onBrowseProviders() }, colors = ButtonDefaults.buttonColors(containerColor = Brand)) { Text("Change provider") } },
        dismissButton = { TextButton(onClick = { confirmCancellation = true }) { Text("Continue cancellation", color = Color(0xFFD64545)) } }
    )
    if (showCancelDialog && confirmCancellation) AlertDialog(
        onDismissRequest = { showCancelDialog = false; confirmCancellation = false },
        icon = { Icon(Icons.Outlined.Cancel, null, tint = Color(0xFFD64545)) },
        title = { Text("Confirm cancellation request", fontWeight = FontWeight.ExtraBold) },
        text = { Text("This skips provider change. Zomeal will review the cancellation within 48 hours; meals remain active until approval.", color = Muted, fontSize = 11.sp) },
        confirmButton = { Button(onClick = {
            val subscriptionId = CustomerSubscriptionStore.current?.id.orEmpty()
            if (subscriptionId.isBlank()) message = "Your active subscription could not be found. Sign in again and retry."
            else repository.requestSubscriptionChange(subscriptionId, "CANCEL_SUBSCRIPTION", reason = "Customer declined provider change and requested cancellation from My Plan") { result, error -> message = if (result != null && error == null) "Cancellation request submitted for review." else error ?: "Could not submit the request." }
            showCancelDialog = false; confirmCancellation = false
        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD64545))) { Text("Request cancellation") } },
        dismissButton = { TextButton(onClick = { confirmCancellation = false }) { Text("Back") } }
    )
}

@Composable
private fun MyPlanHero(provider: Provider,onProviderDetails:()->Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick=onProviderDetails), color = Color.White, shape = RoundedCornerShape(20.dp), shadowElevation = 3.dp) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(70.dp).clip(RoundedCornerShape(15.dp)).background(provider.tint)) { ApprovedProviderImage(provider, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(provider.name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold); Text("Monthly · Lunch + Dinner", color = Muted, fontSize = 10.sp); Text("Pure Veg · 2 meals/day", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                Column(horizontalAlignment=Alignment.End){RatingPill(provider.rating, provider.reviews);Spacer(Modifier.height(5.dp));Row(verticalAlignment=Alignment.CenterVertically){Text("Provider details",color=BrandDark,fontSize=8.sp,fontWeight=FontWeight.Bold);Icon(Icons.Filled.KeyboardArrowRight,null,tint=Brand,modifier=Modifier.size(15.dp))}}
            }
            Row(verticalAlignment = Alignment.Bottom) { Text("18", color = BrandDark, fontSize = 27.sp, fontWeight = FontWeight.Black); Text(" days remaining", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 5.dp)); Spacer(Modifier.weight(1f)); Text("60% complete", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            LinearProgressIndicator(progress = { .6f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Brand, trackColor = Border)
        }
    }
}

@Composable
private fun PlanTimelineCard() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(17.dp)) {
        Row(Modifier.padding(vertical = 13.dp)) { ReviewPlanFact(Icons.Outlined.EventAvailable, "Start date", "24 Aug 2026", Modifier.weight(1f)); ReviewDivider(); ReviewPlanFact(Icons.Outlined.Event, "End date", "22 Sep 2026", Modifier.weight(1f)); ReviewDivider(); ReviewPlanFact(Icons.Outlined.LocalShipping, "Delivery", "Daily", Modifier.weight(1f)) }
    }
}

@Composable
private fun MyPlanWeekPreview(onEdit: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 1.dp) {
        Column(Modifier.padding(13.dp)) {
            listOf("Mon" to "Paneer / Mix Veg", "Tue" to "Dal Tadka / Chana", "Wed" to "Rajma / Egg Tadka", "Thu" to "Kadai Paneer / Mix Veg").chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { item -> Surface(Modifier.weight(1f), color = Mist, shape = RoundedCornerShape(10.dp)) { Column(Modifier.padding(9.dp)) { Text(item.first, color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(item.second, color = Muted, fontSize = 8.sp, maxLines = 1) } } } }
                Spacer(Modifier.height(7.dp))
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().height(38.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)); Text("View & Change Full Menu", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun PlanDeliveryAddress(onChange: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp), shadowElevation = 1.dp) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { IconCircle(Icons.Outlined.LocationOn); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Delivery Address", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Text("Home · Plot 123, Khandagiri, Bhubaneswar – 751030", color = Muted, fontSize = 9.sp) }; TextButton(onClick = onChange) { Text("Change", color = BrandDark, fontSize = 9.sp) } }
    }
}

@Composable
private fun PlanManagementActions(onPause: () -> Unit, onChangeProvider: () -> Unit, onCancel: () -> Unit, onSupport: () -> Unit) {
    val actions = listOf(Triple(Icons.Outlined.PauseCircle, "Pause", onPause), Triple(Icons.Outlined.SwapHoriz, "Provider", onChangeProvider), Triple(Icons.Outlined.Cancel, "Cancel", onCancel), Triple(Icons.Outlined.SupportAgent, "Support", onSupport))
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) { actions.forEach { action -> Surface(Modifier.weight(1f).clickable(onClick = action.third), color = Color.White, shape = RoundedCornerShape(14.dp), shadowElevation = 1.dp) { Column(Modifier.padding(vertical = 11.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(action.first, null, tint = Brand, modifier = Modifier.size(18.dp)); Text(action.second, color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold) } } } }
}

@Composable
private fun PlanPaymentSummary() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ReceiptLong, null, tint = Brand, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Last payment", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text("₹6,394 · UPI · ZM29722124", color = Muted, fontSize = 9.sp) }; Surface(color = Mist, shape = RoundedCornerShape(9.dp)) { Text("Paid", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) } }
    }
}

@Composable
private fun DailyMenuChangeScreen(
    provider: Provider,
    slot: String,
    currentChoice: MenuChoice,
    currentCarb: String,
    onBack: () -> Unit,
    onSave: (MenuChoice, String) -> Unit
) {
    val isLunch = slot == "Lunch"
    val choices = if (isLunch) lunchChoices else dinnerChoices
    val carbOptions = if (isLunch) listOf("Rice", "Roti") else listOf("Roti", "Paratha", "Puri")
    val openingHour = if (isLunch) 15 else 23
    val closingHour = if (isLunch) 8 else 16
    val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val targetTomorrow = if (isLunch) currentHour >= 15 else currentHour >= 22
    val changeAllowed = if (targetTomorrow) currentHour >= openingHour else currentHour < closingHour
    val openingLabel = if (isLunch) "3:00 PM" else "11:00 PM"
    val closingLabel = if (isLunch) "8:00 AM" else "4:00 PM"
    val targetTitle = if (targetTomorrow) "Tomorrow's" else "Today's"
    val targetLabel = remember(targetTomorrow) { SimpleDateFormat("EEE, dd MMM", Locale.ENGLISH).format(Calendar.getInstance().apply { if (targetTomorrow) add(Calendar.DAY_OF_YEAR, 1) }.time) }
    val timingLabel = if (targetTomorrow) "opens after $openingLabel" else "change before $closingLabel"
    var selectedChoice by remember(slot, currentChoice) { mutableStateOf(currentChoice) }
    var selectedCarb by remember(slot, currentCarb) { mutableStateOf(currentCarb) }
    var showCutoffPopup by remember { mutableStateOf(!changeAllowed) }
    var showSavedPopup by remember { mutableStateOf(false) }
    val providerAllowsCarbChanges = true
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 8.dp) {
                Button(
                    onClick = { if (changeAllowed) { onSave(selectedChoice, selectedCarb); showSavedPopup = true } else showCutoffPopup = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).height(50.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark, disabledContainerColor = Border)
                ) { Icon(if (changeAllowed) Icons.Outlined.Save else Icons.Outlined.Lock, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text(if (changeAllowed) "Save $targetTitle $slot" else if (targetTomorrow) "Available after $openingLabel" else "$slot Change Closed", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item { DailyMenuChangeHeader(slot, timingLabel, targetLabel, targetTomorrow, onBack) }
            item { DailyChangeStatusCard(slot, if (targetTomorrow) openingLabel else closingLabel, changeAllowed, targetTomorrow, provider.name) }
            item { MenuTargetSelectionCard(slot, currentChoice, currentCarb, targetTitle) }
            item { Text("Choose an alternate main course", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp)) }
            item {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.chunked(2).forEach { rowChoices ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowChoices.forEach { choice -> DailyAlternativeCard(choice, selectedChoice.name == choice.name, enabled = changeAllowed, modifier = Modifier.weight(1f)) { if (changeAllowed) selectedChoice = choice else showCutoffPopup = true } }
                            if (rowChoices.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            item { DailyAccompanimentSection(slot, selectedCarb, carbOptions, providerAllowsCarbChanges, changeAllowed, onLocked = { showCutoffPopup = true }) { selectedCarb = it } }
            item { DailyFixedItemsCard(if (isLunch) listOf("Dal", "Salad", "Achar") else listOf("Dal", "Seasonal side", "Achar")) }
            item { DailyTargetChangeSummary(slot, selectedChoice.name, selectedCarb, targetLabel, targetTitle) }
        }
    }

    if (showCutoffPopup) AlertDialog(
        onDismissRequest = { showCutoffPopup = false }, icon = { Icon(Icons.Outlined.LockClock, null, tint = Color(0xFFD17A00)) },
        title = { Text(if (targetTomorrow) "Tomorrow's $slot menu is not open yet" else "Today's $slot change window closed", fontWeight = FontWeight.ExtraBold) },
        text = { Text(if (targetTomorrow) "You can change tomorrow's $slot after $openingLabel. The menu will remain visible as Tomorrow's Menu until midnight." else "Today's $slot could only be changed before $closingLabel. The next change window opens at $openingLabel for tomorrow's meal.", color = Muted, fontSize = 11.sp) },
        confirmButton = { Button(onClick = { showCutoffPopup = false }, colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Text("Got it") } }
    )
    if (showSavedPopup) AlertDialog(
        onDismissRequest = { }, icon = { Icon(Icons.Filled.CheckCircle, null, tint = Brand) },
        title = { Text("$targetTitle menu updated", fontWeight = FontWeight.ExtraBold) },
        text = { Text("Your $slot for $targetLabel is now ${selectedChoice.name} with $selectedCarb. This one-day choice does not overwrite the permanent weekly menu.", color = Muted, fontSize = 11.sp) },
        confirmButton = { Button(onClick = { showSavedPopup = false; onBack() }, colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Text("Back to Home") } }
    )
}

@Composable private fun DailyMenuChangeHeader(slot: String, timing: String, date: String, tomorrow: Boolean, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(138.dp).background(Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(920f, 390f)), RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp))) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 10.dp, top = 12.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text("zomeal", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black); Text("${if (tomorrow) "Tomorrow's" else "Today's"} $slot", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold); Text("$date · $timing", color = Color.White.copy(alpha = .88f), fontSize = 9.sp) }
        Surface(color = Color.White.copy(alpha = .16f), shape = CircleShape, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { Icon(if (slot == "Lunch") Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null, tint = Color.White, modifier = Modifier.padding(9.dp).size(19.dp)) }
    }
}

@Composable private fun DailyChangeStatusCard(slot: String, time: String, allowed: Boolean, tomorrow: Boolean, providerName: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = if (allowed) Mist else Color(0xFFFFF5E8), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (allowed) Border else Color(0xFFF0C98C))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (allowed) Icons.Outlined.Timer else Icons.Outlined.LockClock, null, tint = if (allowed) Brand else Color(0xFFB76B16), modifier = Modifier.size(21.dp)); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(if (allowed) "${if (tomorrow) "Tomorrow's" else "Today's"} $slot menu is editable" else if (tomorrow) "Opens at $time" else "Today's cut-off was $time", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold); Text(if (allowed) "Choose from alternatives supplied by $providerName." else if (tomorrow) "This menu becomes editable at the provider's opening time." else "Your existing selection will be prepared and delivered.", color = Muted, fontSize = 8.sp) }; Surface(color = if (allowed) Brand.copy(alpha = .1f) else Color(0xFFD17A00).copy(alpha = .1f), shape = RoundedCornerShape(8.dp)) { Text(if (allowed) "OPEN" else "LOCKED", color = if (allowed) BrandDark else Color(0xFFB76B16), fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(7.dp)) } }
    }
}

@Composable private fun DailyCurrentSelectionCard(slot: String, choice: MenuChoice, carb: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp), shadowElevation = 1.dp) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(61.dp).clip(RoundedCornerShape(12.dp)).background(choice.base.copy(alpha = .12f))) { ApprovedDishImage(choice, Modifier.fillMaxSize()) }; Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("Current $slot", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text(choice.name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Text("$carb · Dal · Salad · Achar", color = Muted, fontSize = 8.sp) }; Icon(Icons.Filled.CheckCircle, null, tint = Brand, modifier = Modifier.size(18.dp)) } }
}

@Composable private fun DailyAlternativeCard(choice: MenuChoice, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.height(128.dp).clickable(onClick = onClick), color = if (selected) Brand.copy(alpha = .05f) else Color.White, shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Brand else Border)) {
        Box { Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.fillMaxWidth().weight(1f).background(choice.base.copy(alpha = if (enabled) .08f else .03f))) { DishArt(choice, Modifier.fillMaxSize()) }; Text(choice.name, color = if (enabled) Ink else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(7.dp)) }; if (selected) Surface(color = Brand, shape = CircleShape, modifier = Modifier.padding(7.dp).align(Alignment.TopEnd)) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.padding(4.dp).size(11.dp)) }; if (!enabled) Icon(Icons.Outlined.Lock, null, tint = Muted, modifier = Modifier.align(Alignment.Center).size(21.dp)) }
    }
}

@Composable private fun DailyAccompanimentSection(slot: String, selected: String, options: List<String>, providerAllows: Boolean, enabled: Boolean, onLocked: () -> Unit, onSelect: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Row { Column(Modifier.weight(1f)) { Text("Choose accompaniment", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold); Text(if (providerAllows) "Allowed by the provider for today’s $slot" else "Fixed by the provider today", color = Muted, fontSize = 8.sp) }; Icon(if (providerAllows) Icons.Outlined.Edit else Icons.Outlined.Lock, null, tint = Brand, modifier = Modifier.size(17.dp)) }; Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { options.forEach { option -> FilterChip(selected = selected == option, onClick = { if (enabled && providerAllows) onSelect(option) else onLocked() }, enabled = true, label = { Text(option, fontSize = 8.sp) }, modifier = Modifier.weight(1f)) } } }
    }
}

@Composable private fun DailyFixedItemsCard(items: List<String>) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(15.dp)) { Column(Modifier.padding(12.dp)) { Text("Included items · Non-changeable", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.height(7.dp)); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items.forEach { item -> Surface(Modifier.weight(1f), color = Color.White, shape = RoundedCornerShape(9.dp)) { Text(item, color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } } } }
}
}

@Composable private fun DailyChangeSummary(slot: String, main: String, carb: String, cutoff: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFFFFAED), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF2DFC1))) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.Info, null, tint = Color(0xFFB7791F), modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Column { Text("One-day change only", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("Today’s $slot will be $main with $carb. Saving before $cutoff updates the Home card only; your main weekly menu stays unchanged.", color = Muted, fontSize = 8.sp, lineHeight = 12.sp) } } }
}

@Composable private fun TomorrowSelectionCard(slot: String, choice: MenuChoice, carb: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp), shadowElevation = 1.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(61.dp).clip(RoundedCornerShape(12.dp)).background(choice.base.copy(alpha = .12f))) { DishArt(choice, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text("Tomorrow's current $slot selection", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text(choice.name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Text("$carb · Dal · Salad · Achar", color = Muted, fontSize = 8.sp) }
            Icon(Icons.Filled.CheckCircle, null, tint = Brand, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable private fun DailyNextDayChangeSummary(slot: String, main: String, carb: String, date: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFFFFAED), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF2DFC1))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.Info, null, tint = Color(0xFFB7791F), modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Column { Text("One-day advance change", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("$date $slot will be $main with $carb. This changes only the next day's meal; today's Home menu and the permanent weekly menu stay unchanged.", color = Muted, fontSize = 8.sp, lineHeight = 12.sp) } }
    }
}

@Composable private fun MenuTargetSelectionCard(slot: String, choice: MenuChoice, carb: String, targetTitle: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp), shadowElevation = 1.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(61.dp).clip(RoundedCornerShape(12.dp)).background(choice.base.copy(alpha = .12f))) { DishArt(choice, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("$targetTitle current $slot selection", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text(choice.name, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Text("$carb · Dal · Salad · Achar", color = Muted, fontSize = 8.sp) }; Icon(Icons.Filled.CheckCircle, null, tint = Brand, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable private fun DailyTargetChangeSummary(slot: String, main: String, carb: String, date: String, targetTitle: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFFFFAED), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF2DFC1))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.Info, null, tint = Color(0xFFB7791F), modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Column { Text("One-day menu change", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("$targetTitle $slot on $date will be $main with $carb. This choice applies only to that delivery day and does not overwrite the weekly menu.", color = Muted, fontSize = 8.sp, lineHeight = 12.sp) } }
    }
}

@Composable
private fun PauseMealsScreen(onBack: () -> Unit, onConfirm: (List<String>,String,String) -> Unit) {
    val selectedDays = remember { mutableStateListOf<Int>().apply { add(1) } }
    var slot by remember { mutableStateOf("Both") }
    var showConfirm by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("dd MMM", Locale.ENGLISH) }
    val fullFormatter = remember { SimpleDateFormat("EEE, dd MMM yyyy", Locale.ENGLISH) }
    val isoFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    fun calendarFor(offset: Int) = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
    val firstDate = selectedDays.minOrNull()?.let { fullFormatter.format(calendarFor(it).time) }.orEmpty()
    val lastDate = selectedDays.maxOrNull()?.let { fullFormatter.format(calendarFor(it).time) }.orEmpty()
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 9.dp) {
                Button(
                    onClick = { showConfirm = true }, enabled = selectedDays.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).height(50.dp),
                    shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)
                ) { Icon(Icons.Outlined.PauseCircle, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Review Pause Request", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item { PauseMealsHeader(onBack) }
            item { PausePlanStatusCard() }
            item { PauseQuickDuration(selectedDays) }
            item { PauseDateCalendar(selectedDays, dateFormatter) }
            item { PauseMealSlotCard(slot) { slot = it } }
            item { PauseSummaryCard(selectedDays.size, slot, firstDate, lastDate) }
            item { PausePolicyCard() }
        }
    }

    if (showConfirm) AlertDialog(
        onDismissRequest = { showConfirm = false },
        icon = { Icon(Icons.Outlined.PauseCircle, null, tint = Brand) },
        title = { Text("Confirm meal pause", fontWeight = FontWeight.ExtraBold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Pause $slot meals on ${selectedDays.size} selected date${if (selectedDays.size == 1) "" else "s"}?", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(if (firstDate == lastDate) firstDate else "$firstDate to $lastDate", color = BrandDark, fontSize = 10.sp); Text("No meals will be delivered for the selected slots. Eligible unused meal credits remain available according to your provider’s pause policy.", color = Muted, fontSize = 10.sp) } },
        confirmButton = { Button(onClick = { showConfirm = false; onConfirm(selectedDays.map{isoFormatter.format(calendarFor(it).time)},slot,"$slot meals paused on ${selectedDays.size} selected date${if (selectedDays.size == 1) "" else "s"} from ${dateFormatter.format(calendarFor(selectedDays.minOrNull() ?: 1).time)}") }, colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Text("Confirm Pause", fontSize = 10.sp) } },
        dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Go back", color = Muted, fontSize = 10.sp) } }
    )
}

@Composable private fun PauseMealsHeader(onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(138.dp).background(Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(920f, 390f)), RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp))) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 10.dp, top = 12.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text("zomeal", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black); Text("Pause or Skip Meals", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold); Text("Choose dates and meal slots", color = Color.White.copy(alpha = .88f), fontSize = 9.sp) }
        Surface(color = Color.White.copy(alpha = .16f), shape = CircleShape, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { Icon(Icons.Outlined.PauseCircle, null, tint = Color.White, modifier = Modifier.padding(9.dp).size(19.dp)) }
    }
}

@Composable private fun PausePlanStatusCard() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Surface(color = Brand, shape = CircleShape) { Icon(Icons.Outlined.CalendarMonth, null, tint = Color.White, modifier = Modifier.padding(10.dp).size(19.dp)) }; Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("Swaad Ghar · Monthly Plan", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Text("Lunch + Dinner · 18 days remaining", color = Muted, fontSize = 8.sp) }; Surface(color = Mist, shape = RoundedCornerShape(9.dp)) { Text("ACTIVE", color = BrandDark, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(7.dp)) } }
    }
}

@Composable private fun PauseQuickDuration(selectedDays: MutableList<Int>) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Quick selection", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1, 3, 5, 7).forEach { count -> FilterChip(selected = selectedDays.size == count && selectedDays.sorted() == (1..count).toList(), onClick = { selectedDays.clear(); selectedDays.addAll(1..count) }, label = { Text(if (count == 1) "Tomorrow" else "$count days", fontSize = 8.sp) }, modifier = Modifier.weight(1f)) } }; Text("Or select individual dates below.", color = Muted, fontSize = 8.sp) }
    }
}

@Composable private fun PauseDateCalendar(selectedDays: MutableList<Int>, formatter: SimpleDateFormat) {
    val weekdayFormatter = remember { SimpleDateFormat("EEE", Locale.ENGLISH) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Select pause dates", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold); Text("The next 14 eligible delivery days", color = Muted, fontSize = 8.sp) }; TextButton(onClick = { selectedDays.clear() }) { Text("Clear", color = BrandDark, fontSize = 8.sp) } }
            listOf((1..7).toList(), (8..14).toList()).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    week.forEach { offset ->
                        val date = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }.time
                        val selected = offset in selectedDays
                        Surface(
                            modifier = Modifier.weight(1f).height(54.dp).clickable { if (selected) selectedDays.remove(offset) else selectedDays.add(offset) },
                            color = if (selected) BrandDark else Color.White, contentColor = if (selected) Color.White else Ink,
                            shape = RoundedCornerShape(11.dp), border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Border)
                        ) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(weekdayFormatter.format(date), fontSize = 7.sp, fontWeight = FontWeight.Bold); Text(formatter.format(date).substringBefore(" "), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold); if (selected) Icon(Icons.Filled.Check, null, modifier = Modifier.size(10.dp)) } }
                    }
                }
            }
        }
    }
}

@Composable private fun PauseMealSlotCard(selected: String, onSelect: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Text("Which meals should be paused?", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold); Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf(Triple("Lunch", Icons.Outlined.LightMode, Color(0xFFE0A000)), Triple("Dinner", Icons.Outlined.DarkMode, Color(0xFF6546A8)), Triple("Both", Icons.Outlined.Restaurant, Brand)).forEach { option -> Surface(Modifier.weight(1f).height(58.dp).clickable { onSelect(option.first) }, color = if (selected == option.first) option.third.copy(alpha = .09f) else Color.White, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(if (selected == option.first) 2.dp else 1.dp, if (selected == option.first) option.third else Border)) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(option.second, null, tint = option.third, modifier = Modifier.size(16.dp)); Text(option.first, color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold) } } } } }
    }
}

@Composable private fun PauseSummaryCard(count: Int, slot: String, firstDate: String, lastDate: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Pause summary", color = BrandDark, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Row { Text("Selected meals", color = Muted, fontSize = 9.sp, modifier = Modifier.weight(1f)); Text("$slot · $count date${if (count == 1) "" else "s"}", color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold) }; Row { Text("Period", color = Muted, fontSize = 9.sp, modifier = Modifier.weight(1f)); Text(if (count == 0) "Select dates" else if (firstDate == lastDate) firstDate else "$firstDate – $lastDate", color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold) } } }
}

@Composable private fun PausePolicyCard() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFFFFAED), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF2DFC1))) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.Info, null, tint = Color(0xFFB7791F), modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Column { Text("Before you pause", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("Pause before the provider’s daily cut-off. Paused meals will not be prepared or delivered. Eligible credits and plan extensions follow the provider’s policy.", color = Muted, fontSize = 8.sp, lineHeight = 12.sp) } } }
}

@Composable
private fun FullWeeklyMenuScreen(
    tomorrowLunch: String,
    tomorrowDinner: String,
    tomorrowLunchCarb: String,
    tomorrowDinnerCarb: String,
    onBack: () -> Unit,
    onEditTomorrow: (String) -> Unit,
    onPauseMeals: () -> Unit
) {
    val dayFormatter = remember { SimpleDateFormat("EEEE", Locale.ENGLISH) }
    val nextSevenDays = remember { (0..6).map { offset -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) } } }
    val dayNames = remember { nextSevenDays.map { dayFormatter.format(it.time) } }
    val lunches = remember { mutableStateListOf("Paneer Butter Masala", "Dal Tadka", "Rajma Masala", "Kadai Paneer", "Chana Masala", "Seasonal Mix Veg", "Paneer Do Pyaza") }
    val dinners = remember { mutableStateListOf("Seasonal Mix Veg", "Chana Masala", "Egg Tadka", "Mix Veg Curry", "Dal Makhani", "Paneer Masala", "Aloo Gobi") }
    val lunchCarbs = listOf("Rice", "Roti", "Rice", "Roti", "Rice", "Rice", "Roti")
    val dinnerCarbs = listOf("Roti", "Paratha", "Roti", "Puri", "Roti", "Paratha", "Roti")
    val dateFormatter = remember { SimpleDateFormat("dd MMM", Locale.ENGLISH) }
    val pausedMeals = remember { mutableStateListOf<String>() }
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    var message by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    Scaffold(containerColor = Color(0xFFFAFCFA)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            item { FullWeekHeader(onBack) }
            message?.let { item { WalletMessageBanner(it) { message = null } } }
            item { WeeklyPlanOverviewCard() }
            item { WeeklyMenuLegend() }
            dayNames.indices.forEach { index ->
                val date = nextSevenDays[index]
                val isTomorrow = index == 1
                val lunch = if (isTomorrow) tomorrowLunch else lunches[index]
                val dinner = if (isTomorrow) tomorrowDinner else dinners[index]
                val lunchCarb = if (isTomorrow) tomorrowLunchCarb else lunchCarbs[index]
                val dinnerCarb = if (isTomorrow) tomorrowDinnerCarb else dinnerCarbs[index]
                val lunchEligible = index > 0 || hour < 8
                val dinnerEligible = index > 0 || hour < 16
                item(key = dayNames[index]) {
                    FullWeekDayCard(
                        day = dayNames[index], date = dateFormatter.format(date.time),
                        lunch = lunch, lunchCarb = lunchCarb, dinner = dinner, dinnerCarb = dinnerCarb,
                        isToday = index == 0, isTomorrow = isTomorrow,
                        lunchEligible = lunchEligible, dinnerEligible = dinnerEligible,
                        lunchPaused = "${index}-Lunch" in pausedMeals, dinnerPaused = "${index}-Dinner" in pausedMeals,
                        onEditLunch = { if (!lunchEligible) message = "Today’s lunch change window closed at 8:00 AM." else if (isTomorrow) onEditTomorrow("Lunch") else { lunches[index] = listOf("Paneer Butter Masala", "Dal Tadka", "Rajma Masala", "Kadai Paneer")[(listOf("Paneer Butter Masala", "Dal Tadka", "Rajma Masala", "Kadai Paneer").indexOf(lunches[index]) + 1).coerceAtLeast(0) % 4]; message = "${dayNames[index]} lunch updated." } },
                        onEditDinner = { if (!dinnerEligible) message = "Today’s dinner change window closed at 4:00 PM." else if (isTomorrow) onEditTomorrow("Dinner") else { dinners[index] = listOf("Seasonal Mix Veg", "Chana Masala", "Egg Tadka", "Mix Veg Curry")[(listOf("Seasonal Mix Veg", "Chana Masala", "Egg Tadka", "Mix Veg Curry").indexOf(dinners[index]) + 1).coerceAtLeast(0) % 4]; message = "${dayNames[index]} dinner updated." } },
                        onPauseLunch = { val key = "${index}-Lunch"; if (!lunchEligible) message = "Today’s lunch pause cut-off has passed." else { if (key in pausedMeals) pausedMeals.remove(key) else pausedMeals.add(key); message = "${dayNames[index]} lunch ${if (key in pausedMeals) "paused" else "resumed"}." } },
                        onPauseDinner = { val key = "${index}-Dinner"; if (!dinnerEligible) message = "Today’s dinner pause cut-off has passed." else { if (key in pausedMeals) pausedMeals.remove(key) else pausedMeals.add(key); message = "${dayNames[index]} dinner ${if (key in pausedMeals) "paused" else "resumed"}." } }
                    )
                }
            }
            item { WeeklyPauseAction(onPauseMeals) }
            item { WeeklyMenuInformation() }
        }
    }
}

@Composable private fun FullWeekHeader(onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(138.dp).background(Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(920f, 390f)), RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp))) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 10.dp, top = 12.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text("zomeal", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black); Text("Full Weekly Menu", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold); Text("Lunch and dinner · Monday to Sunday", color = Color.White.copy(alpha = .88f), fontSize = 9.sp) }
        Surface(color = Color.White.copy(alpha = .16f), shape = CircleShape, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { Icon(Icons.Outlined.DateRange, null, tint = Color.White, modifier = Modifier.padding(9.dp).size(19.dp)) }
    }
}

@Composable private fun WeeklyPlanOverviewCard() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
        Row(Modifier.padding(vertical = 14.dp)) { ReviewPlanFact(Icons.Outlined.Restaurant, "Lunch + Dinner", "2 meals/day", Modifier.weight(1f)); ReviewDivider(); ReviewPlanFact(Icons.Outlined.CalendarMonth, "7-day menu", "14 meals", Modifier.weight(1f)); ReviewDivider(); ReviewPlanFact(Icons.Outlined.Schedule, "18 days left", "Active plan", Modifier.weight(1f)) }
    }
}

@Composable private fun WeeklyMenuLegend() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(13.dp)) { Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { WeeklyLegendDot(Brand, "Next 7 days editable"); Spacer(Modifier.weight(1f)); WeeklyLegendDot(Color(0xFFD17A00), "Today’s cut-off applies"); Spacer(Modifier.weight(1f)); WeeklyLegendDot(Color(0xFFD64545), "Pause by meal") } }
}

@Composable private fun WeeklyLegendDot(color: Color, label: String) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(7.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(4.dp)); Text(label, color = Muted, fontSize = 7.sp) } }

@Composable private fun FullWeekDayCard(day: String, date: String, lunch: String, lunchCarb: String, dinner: String, dinnerCarb: String, isToday: Boolean, isTomorrow: Boolean, lunchEligible: Boolean, dinnerEligible: Boolean, lunchPaused: Boolean, dinnerPaused: Boolean, onEditLunch: () -> Unit, onEditDinner: () -> Unit, onPauseLunch: () -> Unit, onPauseDinner: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(if (isTomorrow) 2.dp else 1.dp, if (isTomorrow) Brand.copy(alpha = .55f) else Border), shadowElevation = if (isTomorrow) 2.dp else 0.dp) {
        Column {
            Row(Modifier.fillMaxWidth().background(if (isTomorrow) Brand.copy(alpha = .075f) else Mist).padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(day, color = if (isTomorrow) BrandDark else Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Text(date, color = Muted, fontSize = 8.sp) }; Surface(color = when { isToday -> Color(0xFF1674A5).copy(alpha = .1f); isTomorrow -> Brand.copy(alpha = .1f); else -> Border.copy(alpha = .6f) }, shape = RoundedCornerShape(8.dp)) { Text(when { isToday -> "TODAY"; isTomorrow -> "TOMORROW"; else -> "SAVED" }, color = when { isToday -> Color(0xFF1674A5); isTomorrow -> BrandDark; else -> Muted }, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) } }
            WeeklyMealRow("Lunch", Icons.Outlined.LightMode, Color(0xFFE0A000), lunch, "$lunchCarb · Dal · Salad · Achar", lunchEligible, lunchPaused, onEditLunch, onPauseLunch)
            HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 12.dp))
            WeeklyMealRow("Dinner", Icons.Outlined.DarkMode, Color(0xFF6546A8), dinner, "$dinnerCarb · Dal · Seasonal side · Achar", dinnerEligible, dinnerPaused, onEditDinner, onPauseDinner)
        }
    }
}

@Composable private fun WeeklyMealRow(slot: String, icon: ImageVector, accent: Color, meal: String, sides: String, editable: Boolean, paused: Boolean, onEdit: () -> Unit, onPause: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) { Surface(color = accent.copy(alpha = .1f), shape = CircleShape) { Icon(icon, null, tint = accent, modifier = Modifier.padding(8.dp).size(16.dp)) }; Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("$slot${if (paused) " · PAUSED" else ""}", color = if (paused) Color(0xFFD64545) else accent, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text(if (paused) "Meal paused" else meal, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold); Text(if (paused) "Tap resume to restore this meal" else sides, color = Muted, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; IconButton(onClick = { if (editable && !paused) onEdit() }, modifier = Modifier.size(34.dp).clip(CircleShape).background(if (editable && !paused) Mist else Border.copy(alpha = .45f))) { Icon(Icons.Outlined.Edit, "Edit $slot", tint = if (editable && !paused) Brand else Muted, modifier = Modifier.size(15.dp)) }; Spacer(Modifier.width(4.dp)); IconButton(onClick = onPause, modifier = Modifier.size(34.dp).clip(CircleShape).background(if (paused) Color(0xFFD64545).copy(alpha = .1f) else Mist)) { Icon(if (paused) Icons.Outlined.PlayCircle else Icons.Outlined.PauseCircle, if (paused) "Resume $slot" else "Pause $slot", tint = if (paused) Color(0xFFD64545) else Brand, modifier = Modifier.size(16.dp)) } }
}

@Composable private fun WeeklyPauseAction(onPause: () -> Unit) {
    OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(43.dp), shape = RoundedCornerShape(13.dp)) { Icon(Icons.Outlined.PauseCircle, null, tint = Brand, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Pause or Skip Selected Meals", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
}

@Composable private fun WeeklyMenuInformation() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFFFFAED), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF2DFC1))) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.Info, null, tint = Color(0xFFB7791F), modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Column { Text("About menu changes", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("You can edit or pause the rolling next seven days. Today’s lunch and dinner follow their provider cut-offs. Changes here affect only the selected delivery day and do not replace your recurring weekly template.", color = Muted, fontSize = 8.sp, lineHeight = 12.sp) } } }
}

@Composable
private fun LiveOrderTrackingScreen(provider: Provider, onBack: () -> Unit, onSupport: () -> Unit) {
    var actionMessage by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = Color(0xFFFAFCFA)) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { TrackingHeader(onBack) }
            actionMessage?.let { item { WalletMessageBanner(it) { actionMessage = null } } }
            item { TrackingEtaCard() }
            item { TrackingRouteCard() }
            item { TrackingProgressTimeline() }
            item { TrackingPartnerCard(onCall = { actionMessage = "Calling Rahul, your delivery partner…" }, onChat = { actionMessage = "Secure chat with your delivery partner opened." }) }
            item { TrackingMealCard(provider) }
            item { TrackingAddressCard() }
            item { TrackingInstructionsCard() }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSupport, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp)) { Icon(Icons.Outlined.ReportProblem, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Report issue", fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    Button(onClick = onSupport, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Icon(Icons.Outlined.SupportAgent, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Contact support", fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable private fun TrackingHeader(onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(138.dp).background(Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(920f, 390f)), RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp))) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 10.dp, top = 12.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text("zomeal", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black); Text("Track Your Meal", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold); Text("Order ZM-L240826 · Lunch", color = Color.White.copy(alpha = .88f), fontSize = 9.sp) }
        Surface(color = Color.White.copy(alpha = .16f), shape = CircleShape, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { Icon(Icons.Outlined.TwoWheeler, null, tint = Color.White, modifier = Modifier.padding(9.dp).size(19.dp)) }
    }
}

@Composable private fun TrackingEtaCard() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(20.dp), shadowElevation = 3.dp) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Brand, shape = CircleShape) { Icon(Icons.Outlined.Schedule, null, tint = Color.White, modifier = Modifier.padding(12.dp).size(22.dp)) }
            Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text("Arriving in 24–30 min", color = BrandDark, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold); Text("Expected by 12:38 PM · On time", color = Muted, fontSize = 9.sp) }
            Surface(color = Mist, shape = RoundedCornerShape(10.dp)) { Text("ON TIME", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) }
        }
    }
}

@Composable private fun TrackingRouteCard() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFEAF5ED), shape = RoundedCornerShape(19.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Box(Modifier.fillMaxWidth().height(142.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                repeat(6) { index -> drawLine(Color.White.copy(alpha = .85f), Offset(0f, size.height * (index + 1) / 7), Offset(size.width, size.height * (index + 1) / 7), 2.dp.toPx()) }
                repeat(4) { index -> drawLine(Color.White.copy(alpha = .85f), Offset(size.width * (index + 1) / 5, 0f), Offset(size.width * (index + 1) / 5, size.height), 2.dp.toPx()) }
                val route = Path().apply { moveTo(size.width * .15f, size.height * .72f); cubicTo(size.width * .32f, size.height * .15f, size.width * .65f, size.height * .85f, size.width * .84f, size.height * .28f) }
                drawPath(route, Brand, style = Stroke(4.dp.toPx()))
                drawCircle(BrandDark, 8.dp.toPx(), Offset(size.width * .15f, size.height * .72f)); drawCircle(Color(0xFFD64545), 9.dp.toPx(), Offset(size.width * .84f, size.height * .28f))
            }
            Surface(color = Color.White, shape = RoundedCornerShape(10.dp), shadowElevation = 2.dp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) { Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.TwoWheeler, null, tint = Brand, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("2.1 km away", color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold) } }
            Icon(Icons.Filled.LocationOn, null, tint = Color(0xFFD64545), modifier = Modifier.align(Alignment.TopEnd).padding(end = 34.dp, top = 18.dp).size(25.dp))
        }
    }
}

@Composable private fun TrackingProgressTimeline() {
    val steps = listOf("Confirmed" to "11:42 AM", "Preparing" to "11:48 AM", "Packed" to "12:02 PM", "Picked up" to "12:08 PM", "Near you" to "Next", "Delivered" to "Pending")
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Text("Delivery progress", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.height(12.dp))
            steps.forEachIndexed { index, step ->
                val complete = index <= 3; val current = index == 3
                Row(Modifier.height(if (index == steps.lastIndex) 34.dp else 46.dp), verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Surface(color = if (complete) Brand else Border, shape = CircleShape, modifier = Modifier.size(if (current) 21.dp else 18.dp)) { if (complete) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.padding(4.dp)) }; if (index < steps.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(if (index < 3) Brand else Border)) }
                    Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(step.first, color = if (current) BrandDark else Ink, fontSize = 10.sp, fontWeight = if (current) FontWeight.ExtraBold else FontWeight.Bold); if (current) Text("Rahul is on the way with your meal", color = Muted, fontSize = 8.sp) }; Text(step.second, color = if (current) BrandDark else Muted, fontSize = 8.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable private fun TrackingPartnerCard(onCall: () -> Unit, onChat: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = BrandDark, shape = CircleShape) { Text("RK", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(12.dp)) }
            Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Rahul Kumar", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Text("Delivery partner · OD 02 AB 4821", color = Muted, fontSize = 8.sp); Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Star, null, tint = Color(0xFFFFB300), modifier = Modifier.size(12.dp)); Text(" 4.8 · 520 deliveries", color = Muted, fontSize = 8.sp) } }
            IconButton(onClick = onChat, modifier = Modifier.size(37.dp).clip(CircleShape).background(Color.White)) { Icon(Icons.Outlined.Chat, "Chat", tint = Brand, modifier = Modifier.size(17.dp)) }; Spacer(Modifier.width(6.dp)); IconButton(onClick = onCall, modifier = Modifier.size(37.dp).clip(CircleShape).background(Brand)) { Icon(Icons.Outlined.Phone, "Call", tint = Color.White, modifier = Modifier.size(17.dp)) }
        }
    }
}

@Composable private fun TrackingMealCard(provider: Provider) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(66.dp).clip(RoundedCornerShape(13.dp)).background(provider.tint)) { DishArt(lunchChoices.first(), Modifier.fillMaxSize()) }; Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("Today’s Lunch", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text("Paneer Butter Masala", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Text("Rice · Dal · Salad · Achar", color = Muted, fontSize = 8.sp); Text("Prepared by ${provider.name}", color = Muted, fontSize = 8.sp, modifier = Modifier.padding(top = 4.dp)) }; Surface(color = Mist, shape = RoundedCornerShape(9.dp)) { Text("1 meal", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(7.dp)) } }
    }
}

@Composable private fun TrackingAddressCard() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { IconCircle(Icons.Outlined.Home); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Delivering to Home", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold); Text(if (CustomerProfileStore.addressSaved) CustomerProfileStore.completeAddress else "Plot 123, Khandagiri, Bhubaneswar – 751030", color = Muted, fontSize = 8.sp, lineHeight = 12.sp) }; Icon(Icons.Filled.CheckCircle, null, tint = Brand, modifier = Modifier.size(17.dp)) } }
}

@Composable private fun TrackingInstructionsCard() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFFFFAED), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF2DFC1))) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.Info, null, tint = Color(0xFFB7791F), modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Column { Text("Delivery instructions", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("Please call on arrival. Leave the sealed tiffin with security only if I’m unavailable.", color = Muted, fontSize = 8.sp, lineHeight = 12.sp) } } }
}

@Composable
private fun RatingReviewScreen(provider: Provider, meal: String, initialRating: Int = 0, onBack: () -> Unit, onSupport: () -> Unit, onSubmitted: () -> Unit) {
    var overallRating by remember { mutableIntStateOf(initialRating) }
    val categoryRatings = remember { mutableStateMapOf("Taste" to 0, "Quantity" to 0, "Packaging" to 0, "Hygiene" to 0, "Delivery" to 0) }
    val selectedTags = remember { mutableStateListOf<String>() }
    var feedback by remember { mutableStateOf("") }
    var photoAttached by remember { mutableStateOf(false) }
    var anonymous by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var showIssueReport by remember { mutableStateOf(false) }
    if (showIssueReport) {
        MealIssueRefundScreen(provider = provider, meal = meal, onBack = { showIssueReport = false }, onContactSupport = onSupport)
        return
    }
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 9.dp) {
                Button(onClick = { showSuccess = true }, enabled = overallRating > 0, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).height(50.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Icon(Icons.Outlined.RateReview, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Submit Review", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ReviewRatingHeader(onBack) }
            item { ReviewMealSummary(provider, meal) }
            item { OverallRatingCard(overallRating) { overallRating = it } }
            item { CategoryRatingsCard(categoryRatings) { category, rating -> categoryRatings[category] = rating } }
            item { FeedbackTagsCard(selectedTags) }
            item { ReviewCommentCard(feedback) { feedback = it.take(500) } }
            item { ReviewPhotoCard(photoAttached) { photoAttached = !photoAttached } }
            item { AnonymousReviewCard(anonymous) { anonymous = it } }
            item { SeriousIssueCard { showIssueReport = true } }
            item { ReviewPrivacyNote() }
        }
    }

    if (showSuccess) AlertDialog(
        onDismissRequest = { }, icon = { Icon(Icons.Filled.CheckCircle, null, tint = Brand, modifier = Modifier.size(30.dp)) },
        title = { Text("Thank you for your feedback!", fontWeight = FontWeight.ExtraBold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Your $overallRating-star review for $meal has been submitted${if (anonymous) " anonymously" else ""}.", color = Muted, fontSize = 11.sp); Surface(color = Mist, shape = RoundedCornerShape(10.dp)) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Favorite, null, tint = Brand, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Your feedback helps Zomeal and the kitchen improve future meals.", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold) } } } },
        confirmButton = { Button(onClick = { showSuccess = false; onSubmitted() }, colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) { Text("Back to Orders") } }
    )
}

@Composable private fun ReviewRatingHeader(onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(138.dp).background(Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(920f, 390f)), RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp))) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 10.dp, top = 12.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text("zomeal", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black); Text("Rate Your Meal", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold); Text("Tell us about your experience", color = Color.White.copy(alpha = .88f), fontSize = 9.sp) }
        Surface(color = Color.White.copy(alpha = .16f), shape = CircleShape, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { Icon(Icons.Outlined.StarRate, null, tint = Color.White, modifier = Modifier.padding(9.dp).size(19.dp)) }
    }
}

@Composable private fun ReviewMealSummary(provider: Provider, meal: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(67.dp).clip(RoundedCornerShape(13.dp)).background(provider.tint)) { DishArt(lunchChoices.first(), Modifier.fillMaxSize()) }; Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text("Delivered · Lunch", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text(meal, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold); Text("Rice · Dal · Salad · Achar", color = Muted, fontSize = 8.sp); Text("${provider.name} · 23 Aug 2026", color = Muted, fontSize = 8.sp, modifier = Modifier.padding(top = 4.dp)) }; Surface(color = Mist, shape = RoundedCornerShape(9.dp)) { Text("ZM-2386", color = BrandDark, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(7.dp)) } }
    }
}

@Composable private fun OverallRatingCard(rating: Int, onRating: (Int) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("How was your meal?", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold); Text(when (rating) { 1 -> "Very disappointing"; 2 -> "Could be better"; 3 -> "It was okay"; 4 -> "Really good"; 5 -> "Loved it!"; else -> "Tap a star to rate" }, color = if (rating > 0) BrandDark else Muted, fontSize = 9.sp, fontWeight = if (rating > 0) FontWeight.Bold else FontWeight.Normal); Spacer(Modifier.height(10.dp)); RatingStars(rating, starSize = 32) { onRating(it) } }
    }
}

@Composable private fun RatingStars(rating: Int, starSize: Int = 20, onRating: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { (1..5).forEach { star -> IconButton(onClick = { onRating(star) }, modifier = Modifier.size((starSize + 7).dp)) { Icon(if (star <= rating) Icons.Filled.Star else Icons.Outlined.StarBorder, "Rate $star stars", tint = if (star <= rating) Color(0xFFFFB300) else Color(0xFFC6CEC9), modifier = Modifier.size(starSize.dp)) } } }
}

@Composable private fun CategoryRatingsCard(ratings: Map<String, Int>, onRating: (String, Int) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("Rate each part", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold); Text("Optional, but useful for the kitchen", color = Muted, fontSize = 8.sp); ratings.forEach { (category, value) -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(category, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) { (1..5).forEach { star -> IconButton(onClick = { onRating(category, star) }, modifier = Modifier.size(28.dp)) { Icon(if (star <= value) Icons.Filled.Star else Icons.Outlined.StarBorder, null, tint = if (star <= value) Color(0xFFFFB300) else Border, modifier = Modifier.size(18.dp)) } } } } }
    }
}
}

@Composable private fun FeedbackTagsCard(selected: MutableList<String>) {
    val tags = listOf("Tasty", "Fresh", "Good quantity", "Well packed", "On time", "Too spicy", "Small portion", "Needs improvement")
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Quick feedback", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold); tags.chunked(2).forEach { rowTags -> Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { rowTags.forEach { tag -> FilterChip(selected = tag in selected, onClick = { if (tag in selected) selected.remove(tag) else selected.add(tag) }, label = { Text(tag, fontSize = 8.sp) }, modifier = Modifier.weight(1f), leadingIcon = if (tag in selected) ({ Icon(Icons.Filled.Check, null, modifier = Modifier.size(12.dp)) }) else null) } } } }
    }
}

@Composable private fun ReviewCommentCard(feedback: String, onFeedback: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Column(Modifier.padding(13.dp)) { Row { Text("Write your review", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f)); Text("${feedback.length}/500", color = Muted, fontSize = 7.sp) }; Spacer(Modifier.height(7.dp)); OutlinedTextField(value = feedback, onValueChange = onFeedback, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5, placeholder = { Text("Tell us what you liked or what can be improved…", fontSize = 9.sp) }, textStyle = LocalTextStyle.current.copy(fontSize = 10.sp), shape = RoundedCornerShape(12.dp)) } }
}

@Composable private fun ReviewPhotoCard(attached: Boolean, onToggle: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onToggle), color = if (attached) Mist else Color.White, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (attached) Brand.copy(alpha = .45f) else Border)) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Surface(color = if (attached) Brand else Mist, shape = RoundedCornerShape(11.dp)) { Icon(if (attached) Icons.Filled.CheckCircle else Icons.Outlined.AddAPhoto, null, tint = if (attached) Color.White else Brand, modifier = Modifier.padding(9.dp).size(18.dp)) }; Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(if (attached) "Meal photo attached" else "Add a meal photo", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(if (attached) "Tap to remove the attachment" else "Optional · helps us understand food issues", color = Muted, fontSize = 8.sp) }; Text(if (attached) "Remove" else "Add", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold) } }
}

@Composable private fun AnonymousReviewCard(anonymous: Boolean, onChange: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(15.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.VisibilityOff, null, tint = Brand, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Post anonymously", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("Your name will not be visible to the provider", color = Muted, fontSize = 8.sp) }; Switch(checked = anonymous, onCheckedChange = onChange, modifier = Modifier.scale(.78f)) } }
}

@Composable private fun SeriousIssueCard(onSupport: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onSupport), color = Color(0xFFFFF3F1), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF1CAC5))) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ReportProblem, null, tint = Color(0xFFD64545), modifier = Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Report a serious issue", color = Color(0xFFB83131), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold); Text("Food safety, missing items, contamination or incorrect meal", color = Muted, fontSize = 8.sp) }; Icon(Icons.Filled.KeyboardArrowRight, null, tint = Color(0xFFD64545), modifier = Modifier.size(17.dp)) } }
}

@Composable private fun ReviewPrivacyNote() { Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.VerifiedUser, null, tint = Brand, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("Reviews follow Zomeal community guidelines and may be moderated for safety.", color = Muted, fontSize = 7.sp) } }

@Composable
private fun MealIssueRefundScreen(provider: Provider, meal: String, onBack: () -> Unit, onContactSupport: () -> Unit) {
    var issue by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf("Wallet refund") }
    var photoCount by remember { mutableIntStateOf(0) }
    var submitted by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), color = Color.White, shadowElevation = 9.dp) {
                if (submitted) {
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).height(49.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) {
                        Text("Done", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                } else {
                    Button(onClick = { submitted = true }, enabled = issue.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).height(49.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = BrandDark)) {
                        Icon(Icons.Outlined.Send, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(7.dp)); Text("Submit Request", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { IssueHeader(onBack) }
            if (submitted) {
                item { IssueSubmittedCard(issue, resolution) }
                item { ComplaintProgressCard() }
                item { RefundInformationCard(resolution) }
                item { IssueSupportContactCard(onContactSupport) }
            } else {
                item { IssueOrderCard(provider, meal) }
                item { IssueTypeCard(issue) { issue = it } }
                item { IssueDescriptionCard(details) { details = it.take(500) } }
                item { IssueEvidenceCard(photoCount) { if (photoCount < 3) photoCount++ } }
                item { ResolutionChoiceCard(resolution) { resolution = it } }
                if (issue == "Food safety") item { FoodSafetyWarning(onContactSupport) }
                item { IssuePolicyNote() }
            }
        }
    }
}

@Composable
private fun IssueHeader(onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(132.dp).background(Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(900f, 360f)), RoundedCornerShape(bottomStart = 27.dp, bottomEnd = 27.dp))) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 10.dp, top = 12.dp).size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("zomeal", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Text("Meal Issue & Refund", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Text("We’ll help make this right", color = Color.White.copy(alpha = .88f), fontSize = 9.sp)
        }
        Surface(color = Color.White.copy(alpha = .16f), shape = CircleShape, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { Icon(Icons.Outlined.SupportAgent, null, tint = Color.White, modifier = Modifier.padding(9.dp).size(19.dp)) }
    }
}

@Composable
private fun IssueOrderCard(provider: Provider, meal: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(62.dp).clip(RoundedCornerShape(12.dp)).background(provider.tint)) { DishArt(lunchChoices.first(), Modifier.fillMaxSize()) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text("Delivered today · Lunch", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text(meal, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Text("${provider.name} · Order ZM-2386", color = Muted, fontSize = 8.sp); Text("₹216 meal value", color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp)) }
            Icon(Icons.Filled.CheckCircle, null, tint = Brand, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun IssueTypeCard(selected: String, onSelect: (String) -> Unit) {
    val issues = listOf("Missing item", "Wrong meal", "Poor quality", "Damaged pack", "Late delivery", "Food safety")
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What went wrong?", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text("Select the issue that best describes your experience", color = Muted, fontSize = 8.sp)
            issues.chunked(2).forEach { rowIssues ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    rowIssues.forEach { value -> FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(value, fontSize = 8.sp) }, modifier = Modifier.weight(1f), leadingIcon = if (selected == value) ({ Icon(Icons.Filled.Check, null, modifier = Modifier.size(12.dp)) }) else null) }
                }
            }
        }
    }
}

@Composable
private fun IssueDescriptionCard(details: String, onChange: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(13.dp)) {
            Row { Text("Describe the issue", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f)); Text("${details.length}/500", color = Muted, fontSize = 7.sp) }
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(value = details, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5, placeholder = { Text("Tell us what happened and which items were affected…", fontSize = 9.sp) }, textStyle = LocalTextStyle.current.copy(fontSize = 10.sp), shape = RoundedCornerShape(12.dp))
        }
    }
}

@Composable
private fun IssueEvidenceCard(photoCount: Int, onAdd: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Brand, shape = RoundedCornerShape(11.dp)) { Icon(Icons.Outlined.AddAPhoto, null, tint = Color.White, modifier = Modifier.padding(9.dp).size(18.dp)) }
            Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Add photos", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("Food, packaging or receipt · up to 3 photos", color = Muted, fontSize = 8.sp) }
            OutlinedButton(onClick = onAdd, enabled = photoCount < 3, modifier = Modifier.height(34.dp), shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (photoCount == 0) "Add" else "$photoCount added", fontSize = 8.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ResolutionChoiceCard(selected: String, onSelect: (String) -> Unit) {
    val choices = listOf(Triple("Wallet refund", "Fast credit after approval", Icons.Outlined.AccountBalanceWallet), Triple("Replacement", "Send a replacement meal", Icons.Outlined.Restaurant), Triple("Support callback", "Speak with our care team", Icons.Outlined.Phone))
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preferred resolution", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            choices.forEach { choice ->
                Surface(Modifier.fillMaxWidth().clickable { onSelect(choice.first) }, color = if (selected == choice.first) Mist else Color.White, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (selected == choice.first) Brand else Border)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = selected == choice.first, onClick = { onSelect(choice.first) }, modifier = Modifier.scale(.8f)); Icon(choice.third, null, tint = Brand, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Column { Text(choice.first, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(choice.second, color = Muted, fontSize = 7.sp) } }
                }
            }
        }
    }
}

@Composable
private fun FoodSafetyWarning(onContact: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFFFF0EE), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0C3BD))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Warning, null, tint = Color(0xFFC93636), modifier = Modifier.size(20.dp)); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Food-safety concern", color = Color(0xFFAC2929), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold); Text("Do not consume the meal. Keep the packaging and contact us immediately.", color = Muted, fontSize = 8.sp) }; Text("Call now", color = Color(0xFFAC2929), fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onContact).padding(5.dp)) }
    }
}

@Composable
private fun IssuePolicyNote() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.Top) { Icon(Icons.Outlined.VerifiedUser, null, tint = Brand, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("Requests are reviewed using order, delivery and provider information. Eligible refunds are credited to your Zomeal Wallet.", color = Muted, fontSize = 7.sp, lineHeight = 10.sp) }
}

@Composable
private fun IssueSubmittedCard(issue: String, resolution: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(19.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Brand.copy(alpha = .25f))) {
        Column(Modifier.fillMaxWidth().padding(17.dp), horizontalAlignment = Alignment.CenterHorizontally) { Surface(color = Brand, shape = CircleShape) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.padding(12.dp).size(24.dp)) }; Spacer(Modifier.height(8.dp)); Text("Request submitted", color = BrandDark, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold); Text("Ticket ZM-SUP-1048", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("${if (issue.isBlank()) "Meal issue" else issue} · $resolution", color = Muted, fontSize = 8.sp); Text("We’ll update you within 30 minutes", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun ComplaintProgressCard() {
    val steps = listOf("Request received" to true, "Kitchen review" to false, "Resolution approved" to false, "Refund or replacement" to false)
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Text("Complaint progress", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold); steps.forEach { step -> Row(verticalAlignment = Alignment.CenterVertically) { Surface(color = if (step.second) Brand else Border, shape = CircleShape, modifier = Modifier.size(18.dp)) { if (step.second) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.padding(4.dp)) }; Spacer(Modifier.width(9.dp)); Text(step.first, color = if (step.second) BrandDark else Muted, fontSize = 9.sp, fontWeight = if (step.second) FontWeight.Bold else FontWeight.Normal); Spacer(Modifier.weight(1f)); Text(if (step.second) "Done" else "Pending", color = Muted, fontSize = 7.sp) } }
        }
    }
}

@Composable
private fun RefundInformationCard(resolution: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color(0xFFFFFAED), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (resolution == "Wallet refund") Icons.Outlined.AccountBalanceWallet else Icons.Outlined.Info, null, tint = Brand, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(9.dp)); Column { Text("Requested: $resolution", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold); Text(if (resolution == "Wallet refund") "Eligible amount: up to ₹216 · credited after approval" else "Our team will confirm availability and next steps", color = Muted, fontSize = 8.sp) } }
    }
}

@Composable
private fun IssueSupportContactCard(onContact: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onContact), color = Color.White, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.SupportAgent, null, tint = Brand, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Need immediate help?", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold); Text("Chat or speak with Zomeal Support", color = Muted, fontSize = 8.sp) }; Icon(Icons.Filled.KeyboardArrowRight, null, tint = Brand, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun OrdersScreen(provider: Provider, onNav: (Int) -> Unit, onSupport: () -> Unit) {
    var filter by remember { mutableStateOf("Upcoming") }
    var selected by remember { mutableStateOf<String?>(null) }
    var reviewMeal by remember { mutableStateOf<String?>(null) }
    var reviewMessage by remember { mutableStateOf<String?>(null) }
    if (reviewMeal != null) {
        RatingReviewScreen(provider = provider, meal = reviewMeal.orEmpty(), onBack = { reviewMeal = null }, onSupport = onSupport, onSubmitted = { reviewMeal = null; reviewMessage = "Thank you! Your review was submitted successfully." })
        return
    }
    val orders = when (filter) {
        "Upcoming" -> listOf(Triple("Lunch · Today", "Paneer Butter Masala", "Preparing"), Triple("Dinner · Today", "Seasonal Mix Veg", "Scheduled"))
        "Delivered" -> listOf(Triple("Lunch · 23 Aug", "Dal Tadka", "Delivered"), Triple("Dinner · 23 Aug", "Chana Masala", "Delivered"), Triple("Lunch · 22 Aug", "Rajma", "Delivered"))
        "Paused" -> listOf(Triple("Lunch + Dinner · 20 Aug", "Plan pause", "Paused"))
        else -> listOf(Triple("Dinner · 18 Aug", "Seasonal Mix Veg", "Cancelled"))
    }
    Scaffold(containerColor = Color(0xFFFAFCFA), bottomBar = { ZomealBottomBar(2, onNav) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { AppSectionHeader("Orders", "View upcoming and past meals", Icons.Outlined.ReceiptLong) { onNav(0) } }
            reviewMessage?.let { item { WalletMessageBanner(it) { reviewMessage = null } } }
            item { OrderSummaryStrip() }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Upcoming", "Delivered", "Paused", "Cancelled").forEach { value ->
                        FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value, fontSize = 8.sp) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (orders.isEmpty()) item { EmptyStateCard("No $filter orders", "Your meals will appear here.") }
            items(orders) { order -> OrderHistoryCard(provider, order.first, order.second, order.third, onDetails = { if (order.third == "Delivered") reviewMeal = order.second else selected = order.first }, onSupport = onSupport) }
        }
    }
    selected?.let { order -> AlertDialog(onDismissRequest = { selected = null }, icon = { Icon(Icons.Outlined.LocalShipping, null, tint = Brand) }, title = { Text(order, fontWeight = FontWeight.Bold) }, text = { Text("Swaad Ghar is preparing this meal. Delivery window: 12:00 PM – 2:00 PM. Your delivery partner and live tracking will appear after dispatch.", fontSize = 11.sp) }, confirmButton = { Button(onClick = { selected = null }) { Text("Done") } }, dismissButton = { TextButton(onClick = { selected = null; onSupport() }) { Text("Get help") } }) }
}

@Composable private fun OrderSummaryStrip() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(vertical = 13.dp)) { ReviewPlanFact(Icons.Outlined.Schedule, "Next meal", "12:00 PM", Modifier.weight(1f)); ReviewDivider(); ReviewPlanFact(Icons.Outlined.CheckCircle, "Delivered", "36 meals", Modifier.weight(1f)); ReviewDivider(); ReviewPlanFact(Icons.Outlined.PauseCircle, "Paused", "2 meals", Modifier.weight(1f)) }
    }
}

@Composable
private fun OrderHistoryCard(provider: Provider, slot: String, meal: String, status: String, onDetails: () -> Unit, onSupport: () -> Unit) {
    val statusColor = when (status) { "Delivered" -> Brand; "Cancelled" -> Color(0xFFD64545); "Paused" -> Color(0xFFB7791F); else -> BrandDark }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(provider.tint)) { ApprovedProviderImage(provider, Modifier.fillMaxSize()) }; Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(slot, color = Muted, fontSize = 9.sp); Text(meal, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold); Text(provider.name, color = Muted, fontSize = 8.sp) }; Surface(color = statusColor.copy(alpha = .1f), shape = RoundedCornerShape(9.dp)) { Text(status, color = statusColor, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) } }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { OutlinedButton(onClick = onSupport, modifier = Modifier.weight(1f).height(34.dp), contentPadding = PaddingValues(0.dp)) { Text("Report issue", fontSize = 8.sp) }; Button(onClick = onDetails, modifier = Modifier.weight(1f).height(34.dp), contentPadding = PaddingValues(0.dp)) { Text(if (status == "Delivered") "Rate meal" else "View details", fontSize = 8.sp) } }
        }
    }
}

@Composable
private fun SupportCentreScreen(onBack: () -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = Color(0xFFFAFCFA)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { AppSectionHeader("Support Centre", "Quick help for every meal and payment", Icons.Outlined.SupportAgent, onBack) }
            message?.let { item { WalletMessageBanner(it) { message = null } } }
            item { SupportContactCard { message = "$it request started. Our team will assist you shortly." } }
            item { SectionTitle("How can we help?") }
            item { SupportIssueGrid { message = "$it support opened. Select the affected order to continue." } }
            item { ActiveTicketCard { message = "Ticket ZM-1084 opened. Last update: our team is checking with the kitchen." } }
            item { SectionTitle("Frequently asked questions") }
            items(listOf("How do I pause a meal?" to "Open Home → Pause Plan, select dates and choose lunch, dinner or both.", "Can I change tomorrow’s menu?" to "Yes, until the provider’s menu cut-off time shown in My Plan.", "When will a refund arrive?" to "Eligible refunds return to the original payment method within 5–7 working days.").withIndex().toList()) { indexed ->
                Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable { expanded = if (expanded == indexed.index) null else indexed.index }, color = Color.White, shape = RoundedCornerShape(13.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Column(Modifier.padding(12.dp)) { Row { Text(indexed.value.first, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Icon(if (expanded == indexed.index) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = Brand, modifier = Modifier.size(17.dp)) }; if (expanded == indexed.index) Text(indexed.value.second, color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 7.dp)) } }
            }
        }
    }
}

@Composable private fun SupportContactCard(onAction: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp)) { Text("We’re here for you", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold); Text("Typical response time is under 2 minutes.", color = Muted, fontSize = 9.sp); Spacer(Modifier.height(11.dp)); Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { listOf(Triple(Icons.Outlined.Chat, "Live chat", "chat"), Triple(Icons.Outlined.Phone, "Call us", "call"), Triple(Icons.Outlined.Email, "Email", "email")).forEach { action -> OutlinedButton(onClick = { onAction(action.third) }, modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(0.dp)) { Icon(action.first, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(action.second, fontSize = 8.sp) } } } }
}
}

@Composable private fun SupportIssueGrid(onIssue: (String) -> Unit) {
    val issues = listOf(Icons.Outlined.Schedule to "Meal is late", Icons.Outlined.Restaurant to "Food issue", Icons.Outlined.Payment to "Payment help", Icons.Outlined.Autorenew to "Pause or refund")
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { issues.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { row.forEach { issue -> Surface(Modifier.weight(1f).clickable { onIssue(issue.second) }, color = Color.White, shape = RoundedCornerShape(14.dp), shadowElevation = 1.dp) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(issue.first, null, tint = Brand, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(issue.second, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold) } } } } } }
}

@Composable private fun ActiveTicketCard(onClick: () -> Unit) { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onClick), color = Color.White, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { IconCircle(Icons.Outlined.ConfirmationNumber); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text("Active ticket · ZM-1084", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text("Meal quality · In progress", color = Muted, fontSize = 8.sp) }; Text("View", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold) } } }

@Composable
private fun ProfileScreen(
    providerName: String,
    onNav: (Int) -> Unit,
    onWallet: () -> Unit,
    onSupport: () -> Unit,
    onBrowseProviders: () -> Unit,
    onLogout: () -> Unit
) {
    val profileContext=LocalContext.current.applicationContext
    val subscriptionRepository = remember(profileContext) { SupabaseCustomerRepository(profileContext) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var editAddress by remember { mutableStateOf(false) }
    var policyScreen by remember { mutableStateOf<PrototypeState?>(null) }
    var mealAlerts by remember { mutableStateOf(true) }; var offers by remember { mutableStateOf(false) }
    var subscriptionAction by remember { mutableStateOf<String?>(null) }
    var requestSubmitted by remember { mutableStateOf<String?>(null) }
    var requestError by remember { mutableStateOf<String?>(null) }
    policyScreen?.let { policy ->
        LegalPolicyScreen(policy, onBack = { policyScreen = null })
        return
    }
    Scaffold(containerColor = Color(0xFFFAFCFA), bottomBar = { ZomealBottomBar(3, onNav) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { AppSectionHeader("Profile", "Your account, preferences and security", Icons.Outlined.Person) { onNav(0) } }
            item { ProfileIdentityCard { dialog = "Edit profile" } }
            item { SectionTitle("Account") }
            item { ProfileMenuCard(listOf(Triple(Icons.Outlined.LocationOn, "Saved addresses", if (CustomerProfileStore.addressSaved) "Home · ${CustomerProfileStore.locality}" else "Add your delivery address"), Triple(Icons.Outlined.AccountBalanceWallet, "Zomeal Wallet", "₹1,250 available"), Triple(Icons.Outlined.Payment, "Payment methods", "UPI and cards"))) { label -> when (label) { "Zomeal Wallet" -> onWallet(); "Saved addresses" -> editAddress = true; else -> dialog = label } } }
            item { SectionTitle("Meal preferences") }
            item { PreferenceCard { dialog = it } }
            requestSubmitted?.let { message ->
                item {
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = Brand, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(message, color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            requestError?.let { message -> item { MarketplaceStatusCard("Subscription request needs attention",true,message) } }
            item { SectionTitle("Notifications") }
            item { ToggleSettingCard("Meal and delivery alerts", "Order status, menu cut-off and pause reminders", mealAlerts) { mealAlerts = it } }
            item { ToggleSettingCard("Offers and rewards", "Wallet bonuses and referral campaigns", offers) { offers = it } }
            item { SectionTitle("Help & settings") }
            item { ProfileMenuCard(listOf(Triple(Icons.Outlined.SupportAgent, "Support Centre", "Chat, call or raise a ticket"), Triple(Icons.Outlined.Language, "Language", "English"), Triple(Icons.Outlined.Shield, "Privacy & security", "Permissions and account data"))) { label -> if (label == "Support Centre") onSupport() else dialog = label } }
            item { SectionTitle("Legal & Policies") }
            item {
                ProfileMenuCard(
                    listOf(
                        Triple(Icons.Outlined.Description, "Terms of Service", "Rules for using Zomeal"),
                        Triple(Icons.Outlined.PrivacyTip, "Privacy Policy", "How we protect and use your data"),
                        Triple(Icons.Outlined.CurrencyRupee, "Refund & Cancellation", "Refund eligibility and timelines"),
                        Triple(Icons.Outlined.PauseCircle, "Subscription Pause Policy", "Pause deadlines, credits and extensions")
                    )
                ) { label ->
                    policyScreen = when (label) {
                        "Terms of Service" -> PrototypeState.TERMS
                        "Privacy Policy" -> PrototypeState.PRIVACY
                        "Refund & Cancellation" -> PrototypeState.REFUND_POLICY
                        else -> PrototypeState.PAUSE_POLICY
                    }
                }
            }
            item { OutlinedButton(onClick = { dialog = "Log out" }, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(43.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD64545))) { Icon(Icons.Outlined.Logout, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Log out", fontSize = 10.sp, fontWeight = FontWeight.Bold) } }
        }
    }
    if (editAddress) ProfileAddressDialog(onDismiss = { editAddress = false })
    subscriptionAction?.let { action ->
        val changingProvider = action == "Change service provider"
        AlertDialog(
            onDismissRequest = { subscriptionAction = null },
            icon = { Icon(if (changingProvider) Icons.Outlined.SwapHoriz else Icons.Outlined.Cancel, null, tint = if (changingProvider) Brand else Color(0xFFD64545)) },
            title = { Text(action, fontWeight = FontWeight.ExtraBold) },
            text = {
                Text(
                    if (changingProvider)
                        "Your current plan with $providerName remains active while you browse. A provider change is completed only after you choose an available replacement and Zomeal confirms the transfer."
                    else
                        "Your plan with $providerName will not stop immediately. Zomeal will review the request within 48 hours and show the eligible refund before confirming cancellation.",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        subscriptionAction = null
                        if (changingProvider) onBrowseProviders()
                        else {
                            val subscriptionId=CustomerSubscriptionStore.current?.id.orEmpty()
                            if(subscriptionId.isBlank()) requestError="Your active subscription has not been restored from the server. Sign in again and retry."
                            else subscriptionRepository.requestSubscriptionChange(subscriptionId,"CANCEL_SUBSCRIPTION") { _,error ->
                                if(error!=null)requestError=error
                                else requestSubmitted="Cancellation request recorded for review. Your meals remain active until it is approved."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (changingProvider) Brand else Color(0xFFD64545))
                ) { Text(if (changingProvider) "Browse providers" else "Request cancellation", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { subscriptionAction = null }) { Text("Keep current plan", color = Muted) } }
        )
    }
    dialog?.let { action -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(action, fontWeight = FontWeight.Bold) }, text = { Text(when (action) { "Meal preferences" -> "Pure vegetarian preference selected. You can also add allergies and ingredients to avoid."; "Log out" -> "Are you sure you want to log out of Zomeal?"; else -> "$action settings are ready to manage from this screen." }, fontSize = 11.sp) }, confirmButton = { Button(onClick = { if (action == "Log out") onLogout() else dialog = null }) { Text(if (action == "Log out") "Log out" else "Done") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } }) }
}

@Composable
private fun ProfileAddressDialog(onDismiss: () -> Unit) {
    var house by remember { mutableStateOf(CustomerProfileStore.house) }
    var street by remember { mutableStateOf(CustomerProfileStore.street) }
    var locality by remember { mutableStateOf(CustomerProfileStore.locality) }
    var landmark by remember { mutableStateOf(CustomerProfileStore.landmark) }
    var pincode by remember { mutableStateOf(CustomerProfileStore.pincode) }
    var availability by remember { mutableStateOf<String?>(null) }
    val serviceable = availability == "Available"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.LocationOn, null, tint = Brand) },
        title = { Text("Change delivery address", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Check availability before replacing the address saved in your profile.", color = Muted, fontSize = 9.sp)
                ReviewAddressField("House / Flat number *", house, "House or flat") { house = it; availability = null }
                ReviewAddressField("Street / Building *", street, "Street or building") { street = it; availability = null }
                ReviewAddressField("Locality / City *", locality, "Locality and city") { locality = it; availability = null }
                ReviewAddressField("Landmark (optional)", landmark, "Nearby landmark") { landmark = it; availability = null }
                ReviewAddressField("Pincode *", pincode, "6-digit pincode", numeric = true) { pincode = it.take(6); availability = null }
                availability?.let { result ->
                    Surface(color = if (serviceable) Mist else Color(0xFFFFF1F0), shape = RoundedCornerShape(10.dp)) { Text(if (serviceable) "✓ Service providers are available at this address." else "No active provider serves this address yet. Your existing address remains unchanged.", color = if (serviceable) BrandDark else Color(0xFFD64545), fontSize = 9.sp, modifier = Modifier.padding(9.dp)) }
                }
                OutlinedButton(onClick = { availability = if (house.isNotBlank() && street.isNotBlank() && locality.isNotBlank() && pincode in setOf("751030", "751019", "751003", "751012")) "Available" else "Unavailable" }, modifier = Modifier.fillMaxWidth().height(38.dp)) { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)); Text("Check provider availability", fontSize = 9.sp) }
            }
        },
        confirmButton = {
            Button(enabled = serviceable, onClick = { CustomerProfileStore.house = house.trim(); CustomerProfileStore.street = street.trim(); CustomerProfileStore.locality = locality.trim(); CustomerProfileStore.landmark = landmark.trim(); CustomerProfileStore.pincode = pincode; CustomerProfileStore.addressSaved = true; onDismiss() }) { Text("Save address", fontSize = 9.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 9.sp) } }
    )
}

@Composable private fun ProfileIdentityCard(onEdit: () -> Unit) { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(20.dp), shadowElevation = 2.dp) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Surface(color = Brand, shape = CircleShape) { Text("AN", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(15.dp)) }; Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text("Ashutosh Nayak", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold); Text("+91 98XXXXXX42", color = Muted, fontSize = 9.sp); Text("Verified member", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold) }; IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit profile", tint = Brand, modifier = Modifier.size(18.dp)) } } } }

@Composable private fun ProfileMenuCard(items: List<Triple<ImageVector, String, String>>, onClick: (String) -> Unit) { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Column { items.forEachIndexed { index, item -> Row(Modifier.fillMaxWidth().clickable { onClick(item.second) }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(item.first, null, tint = Brand, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(item.second, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(item.third, color = Muted, fontSize = 8.sp) }; Icon(Icons.Filled.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(17.dp)) }; if (index < items.lastIndex) HorizontalDivider(Modifier.padding(start = 42.dp), color = Border) } } } }

@Composable private fun PreferenceCard(onClick: (String) -> Unit) { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(17.dp)) { Column(Modifier.padding(13.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Eco, null, tint = Brand, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Pure Veg", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = { onClick("Meal preferences") }) { Text("Edit", fontSize = 9.sp) } }; Text("Allergies: Peanuts · Avoid: Mushroom", color = Muted, fontSize = 9.sp); Text("These preferences help kitchens recommend suitable menus.", color = Muted, fontSize = 8.sp, modifier = Modifier.padding(top = 5.dp)) } } }

@Composable private fun ToggleSettingCard(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 8.sp) }; Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.scale(.78f)) } } }

@Composable private fun SectionTitle(text: String) { Text(text, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp)) }
@Composable private fun EmptyStateCard(title: String, subtitle: String) { Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(17.dp)) { Column(Modifier.padding(22.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.ReceiptLong, null, tint = Brand, modifier = Modifier.size(28.dp)); Text(title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 9.sp) } } }

@Composable
private fun SubscriberHeader(provider: Provider, onNotifications: () -> Unit, onWallet: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(250.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(205.dp).background(
                Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(950f, 500f)),
                RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
            )
        )
        Column(Modifier.padding(start = 20.dp, top = 23.dp)) {
            Text("zomeal", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            Text("Good Morning, Ashutosh!", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocationOn, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Khandagiri, Bhubaneswar · 751030", color = Color.White.copy(alpha = .92f), fontSize = 12.sp)
                Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Row(Modifier.padding(end = 16.dp, top = 24.dp).align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeHeaderAction(Icons.Outlined.Notifications, "Notifications", onNotifications, badge = "3", caption = "Alerts")
            HomeHeaderAction(Icons.Outlined.AccountBalanceWallet, "Wallet", onWallet, caption = "₹1,250")
        }
        Box(Modifier.align(Alignment.BottomCenter)) { ActivePlanCard(provider) }
    }
}

@Composable
private fun HomeHeaderAction(icon: ImageVector, label: String, onClick: () -> Unit, badge: String? = null, caption: String) {
    Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            IconButton(onClick = onClick, modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .17f))) {
                Icon(icon, label, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            badge?.let {
                Surface(color = Color(0xFFE53935), shape = CircleShape, modifier = Modifier.size(16.dp).align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(it, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(caption, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ActivePlanCard(provider: Provider) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(112.dp), color = Color.White, shape = RoundedCornerShape(21.dp), shadowElevation = 5.dp) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Brand, shape = CircleShape) { Icon(Icons.Outlined.CalendarMonth, null, tint = Color.White, modifier = Modifier.padding(11.dp).size(21.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Your Plan", color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(provider.name, color = Ink, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Mist, shape = RoundedCornerShape(7.dp)) { Text(provider.diet, color = BrandDark, fontSize = 7.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) }
                    Spacer(Modifier.width(5.dp))
                    Text("Monthly · 30 days", color = Muted, fontSize = 8.sp, maxLines = 1)
                }
            }
            Box(Modifier.width(1.dp).height(50.dp).background(Border))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.width(88.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("18", color = BrandDark, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text(" days left", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
                LinearProgressIndicator(progress = { .6f }, color = Brand, trackColor = Border, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                Text("Ends 22 Sep 2026", color = Muted, fontSize = 8.sp, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

@Composable
private fun TodayMenuHeader(showTomorrow: Boolean, date: String, onViewWeek: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (showTomorrow) "Tomorrow's Menu" else "Today's Menu", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(date, color = Muted, fontSize = 11.sp)
        }
        OutlinedButton(onClick = onViewWeek, modifier = Modifier.height(38.dp), shape = RoundedCornerShape(17.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Outlined.CalendarMonth, null, tint = Brand, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text("View full week", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DailyMealCard(
    slot: String,
    meal: String,
    sides: String,
    accent: Color,
    choice: MenuChoice,
    calories: Int,
    protein: Int,
    carbs: Int,
    fat: Int,
    onInfo: () -> Unit,
    onCancel: () -> Unit,
    onChange: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = accent.copy(alpha = .035f),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .22f))
    ) {
        Column(Modifier.padding(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (slot == "Lunch") Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null, tint = if (slot == "Lunch") Color(0xFFFFB300) else accent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(slot, color = accent, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onInfo, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Info, "Meal information", tint = accent, modifier = Modifier.size(16.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(13.dp)).background(choice.base.copy(alpha = .14f))) {
                ApprovedDishImage(choice, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(7.dp))
            Text(meal, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sides, color = Muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(34.dp), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Outlined.PauseCircle, null, tint = Color(0xFFD64545), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Cancel", color = Color(0xFFD64545), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onChange, modifier = Modifier.weight(1.15f).height(34.dp), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Outlined.Edit, null, tint = Brand, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Change", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CompactMacro(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 6.sp)
    }
}

@Composable
private fun MacroFact(icon: ImageVector, value: String, label: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
            Text(value, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = Muted, fontSize = 7.sp)
    }
}

@Composable
private fun NextMealCard(providerName: String) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Brand, shape = CircleShape) { Icon(Icons.Outlined.Schedule, null, tint = Color.White, modifier = Modifier.padding(10.dp).size(19.dp)) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Next Meal", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Lunch · 12:00 PM – 2:00 PM", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("$providerName will deliver on time", color = Muted, fontSize = 9.sp)
            }
            Surface(color = Color.White, shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                Text("Scheduled", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp))
            }
        }
    }
}

@Composable
private fun HomeMealRatingCard(rating: Int, onRating: (Int) -> Unit, onReview: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        color = Color(0xFFFFFBEE),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0DDA9))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFFFF1C7), shape = CircleShape) {
                    Icon(Icons.Filled.Star, null, tint = Color(0xFFFFB300), modifier = Modifier.padding(9.dp).size(18.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("Rate Your Last Meal", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Lunch · Dal Tadka · Delivered today", color = Muted, fontSize = 8.sp)
                }
                Text("Not now", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onDismiss).padding(5.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RatingStars(rating = rating, starSize = 22, onRating = onRating)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onReview,
                    modifier = Modifier.height(37.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                    contentPadding = PaddingValues(horizontal = 13.dp)
                ) {
                    Icon(Icons.Outlined.RateReview, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (rating > 0) "Continue" else "Write a Review", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun NutritionOverview(onDetails: () -> Unit) {
    val nutrients = listOf(
        Triple("998", "Calories", .5f), Triple("32g", "Protein", .64f), Triple("136g", "Carbs", .48f), Triple("34g", "Fat", .54f)
    )
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(19.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text("Today's Nutrition Overview", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text("See details", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onDetails))
            }
            Spacer(Modifier.height(14.dp))
            Row {
                nutrients.forEach { nutrient ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawArc(Border, -90f, 360f, false, style = Stroke(6.dp.toPx()))
                                drawArc(Brand, -90f, 360f * nutrient.third, false, style = Stroke(6.dp.toPx()))
                            }
                            Text("${(nutrient.third * 100).toInt()}%", color = BrandDark, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(nutrient.first, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        Text(nutrient.second, color = Muted, fontSize = 8.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriberQuickActions(onPause: () -> Unit, onPlan: () -> Unit, onOrders: () -> Unit, onSupport: () -> Unit) {
    val actions = listOf(
        Triple(Icons.Outlined.PauseCircle, "Pause Plan", "Pause meals"),
        Triple(Icons.Outlined.CalendarMonth, "My Plan", "View details"),
        Triple(Icons.Outlined.ShoppingBag, "Order History", "Past orders"),
        Triple(Icons.Outlined.SupportAgent, "Support", "We're here")
    )
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(vertical = 15.dp)) {
            actions.forEachIndexed { index, action ->
                val onClick = listOf(onPause, onPlan, onOrders, onSupport)[index]
                Column(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = Mist, shape = CircleShape) { Icon(action.first, null, tint = Brand, modifier = Modifier.padding(9.dp).size(17.dp)) }
                    Text(action.second, color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(action.third, color = Muted, fontSize = 7.sp, maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(onContinue: (String) -> Unit, onCreateAccount: () -> Unit) {
    var mobile by rememberSaveable { mutableStateOf("") }
    val valid = mobile.length == 10
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFFFBFDF9)).statusBarsPadding().navigationBarsPadding()
    ) {
        val compact = maxHeight < 700.dp
        SignupBackgroundArt(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(if (compact) 205.dp else 265.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.zomeal_logo),
                        contentDescription = "Zomeal",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.width(if (compact) 205.dp else 235.dp).height(if (compact) 105.dp else 130.dp)
                    )
                    Text("Welcome back!", color = Ink, fontSize = if (compact) 21.sp else 25.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Login to manage your meals and subscription", color = Muted, fontSize = if (compact) 9.sp else 11.sp)
                }
            }
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (compact) 13.dp else 18.dp),
                color = Color.White.copy(alpha = .98f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                shadowElevation = 8.dp
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = if (compact) 18.dp else 22.dp, vertical = if (compact) 18.dp else 25.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Mist, shape = CircleShape) { Icon(Icons.Outlined.PhoneAndroid, null, tint = Brand, modifier = Modifier.padding(10.dp).size(21.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Column { Text("Login with mobile number", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold); Text("We’ll send a secure OTP to verify you", color = Muted, fontSize = 9.sp) }
                        }
                        Text("Mobile Number", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        TextField(
                            value = mobile,
                            onValueChange = { mobile = it.filter(Char::isDigit).take(10) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            placeholder = { Text("Enter your mobile number", fontSize = 11.sp) },
                            leadingIcon = { Text("🇮🇳  +91", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = signupFieldColors()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Lock, null, tint = Brand, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(6.dp)); Text("Your account and payment information are protected", color = Muted, fontSize = 8.sp) }
                        Surface(color = Mist, shape = RoundedCornerShape(15.dp)) {
                            Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.RestaurantMenu, null, tint = Brand, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(9.dp)); Column { Text("Everything in one place", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("Daily menus, plan controls, orders and support", color = Muted, fontSize = 8.sp) }
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = { onContinue(mobile) },
                            enabled = valid,
                            modifier = Modifier.fillMaxWidth().height(if (compact) 54.dp else 58.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand, disabledContainerColor = Border)
                        ) {
                            Text("Send OTP", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f)); Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("New to Zomeal? ", color = Muted, fontSize = 10.sp); Text("Create an account", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onCreateAccount).padding(3.dp)) }
                        Spacer(Modifier.height(7.dp))
                        Text("By continuing, you agree to Zomeal’s Terms & Privacy Policy", color = Muted, fontSize = 7.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignupScreen(onContinue: (String, String, String, String) -> Unit, onLogin: () -> Unit) {
    var fullName by rememberSaveable { mutableStateOf("") }
    var mobile by rememberSaveable { mutableStateOf("") }
    var pincode by rememberSaveable { mutableStateOf("") }
    var referralCode by rememberSaveable { mutableStateOf("") }
    var useCurrentLocation by rememberSaveable { mutableStateOf(true) }
    val valid = fullName.trim().length >= 2 && mobile.length == 10 && pincode.length == 6

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFBFDF9))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val compact = maxHeight < 760.dp
        SignupBackgroundArt(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            SignupHero(compact)
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (compact) 12.dp else 18.dp),
                color = Color.White.copy(alpha = .98f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = if (compact) 15.dp else 18.dp, vertical = if (compact) 10.dp else 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp)) {
                        RegistrationFieldHeader(Icons.Outlined.Person, "Full Name", "Enter your full name", compact)
                        TextField(
                            value = fullName,
                            onValueChange = { fullName = it.take(40) },
                            modifier = Modifier.fillMaxWidth().height(if (compact) 48.dp else 54.dp),
                            placeholder = { Text("Enter your full name", fontSize = if (compact) 11.sp else 12.sp) },
                            leadingIcon = { Icon(Icons.Outlined.Person, null, tint = Muted, modifier = Modifier.size(18.dp)) },
                            singleLine = true,
                            shape = RoundedCornerShape(13.dp),
                            colors = signupFieldColors()
                        )

                        RegistrationFieldHeader(Icons.Outlined.PhoneAndroid, "Mobile Number", "We'll send you an OTP to verify", compact)
                        TextField(
                            value = mobile,
                            onValueChange = { mobile = it.filter(Char::isDigit).take(10) },
                            modifier = Modifier.fillMaxWidth().height(if (compact) 48.dp else 54.dp),
                            placeholder = { Text("Enter your mobile number", fontSize = if (compact) 11.sp else 12.sp) },
                            leadingIcon = { Text("🇮🇳  +91", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            shape = RoundedCornerShape(13.dp),
                            colors = signupFieldColors()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, null, tint = Muted, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Your number is safe with us", color = Muted, fontSize = 8.sp)
                        }

                        RegistrationFieldHeader(Icons.Outlined.LocationOn, "Delivery Pincode", "Enter a valid 6-digit pincode", compact)
                        PincodeInput(pincode, compact) { pincode = it }
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.Info, null, tint = Muted, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "We'll verify service availability after OTP verification.",
                                color = Muted,
                                fontSize = if (compact) 8.sp else 9.sp,
                                lineHeight = 12.sp
                            )
                        }

                        TextField(
                            value = referralCode,
                            onValueChange = { referralCode = it.uppercase().filter(Char::isLetterOrDigit).take(10) },
                            modifier = Modifier.fillMaxWidth().height(if (compact) 46.dp else 52.dp),
                            placeholder = { Text("Referral code (optional)", fontSize = if (compact) 10.sp else 11.sp) },
                            leadingIcon = { Icon(Icons.Outlined.CardGiftcard, null, tint = Brand, modifier = Modifier.size(18.dp)) },
                            supportingText = if (compact) null else ({ Text("Rewards unlock after your first successful paid subscription.", fontSize = 7.sp) }),
                            singleLine = true,
                            shape = RoundedCornerShape(13.dp),
                            colors = signupFieldColors()
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Mist, shape = CircleShape) {
                                Icon(Icons.Outlined.MyLocation, null, tint = Brand, modifier = Modifier.padding(8.dp).size(18.dp))
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Use my current location", color = Ink, fontSize = if (compact) 11.sp else 12.sp, fontWeight = FontWeight.Bold)
                                Text("Detect only your pincode", color = Muted, fontSize = 8.sp)
                            }
                            Switch(
                                checked = useCurrentLocation,
                                onCheckedChange = { useCurrentLocation = it },
                                modifier = Modifier.scale(.78f),
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF42A62A))
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = { onContinue(fullName.trim(), mobile, pincode, referralCode.trim()) },
                            enabled = valid,
                            modifier = Modifier.fillMaxWidth().height(if (compact) 54.dp else 60.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand, disabledContainerColor = Border)
                        ) {
                            Text("Create Account", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.Center) {
                            Text("Already have an account? ", color = Muted, fontSize = 9.sp)
                            Text("Login", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onLogin).padding(horizontal = 3.dp, vertical = 2.dp))
                        }
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Outlined.GppGood, null, tint = Brand, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("By continuing, you agree to our ", color = Muted, fontSize = 7.sp)
                            Text("Terms & Privacy Policy", color = BrandDark, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignupHero(compact: Boolean) {
    Box(Modifier.fillMaxWidth().height(if (compact) 122.dp else 148.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(if (compact) 158.dp else 184.dp).height(if (compact) 88.dp else 104.dp)) {
                Image(
                    painter = painterResource(R.drawable.zomeal_logo),
                    contentDescription = "Zomeal",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text("Create your account", color = Ink, fontSize = if (compact) 18.sp else 21.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun RegistrationFieldHeader(icon: ImageVector, title: String, subtitle: String, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Mist, shape = CircleShape) {
            Icon(icon, null, tint = Color(0xFF51A72F), modifier = Modifier.padding(if (compact) 7.dp else 8.dp).size(if (compact) 16.dp else 18.dp))
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(title, color = Ink, fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Muted, fontSize = if (compact) 8.sp else 9.sp)
        }
    }
}

@Composable
private fun PincodeInput(value: String, compact: Boolean, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(6)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
        decorationBox = { innerTextField ->
            Box {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)) {
                    repeat(6) { index ->
                        Surface(
                            modifier = Modifier.weight(1f).height(if (compact) 42.dp else 48.dp),
                            color = Color.White,
                            shape = RoundedCornerShape(11.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (index == value.length) Brand.copy(alpha = .55f) else Border)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(value.getOrNull(index)?.toString() ?: "—", color = if (index < value.length) BrandDark else Border, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Box(Modifier.size(1.dp)) { innerTextField() }
            }
        }
    )
}

@Composable
private fun SignupThaliArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * .5f, size.height * .5f)
        drawCircle(Color(0xFFD6D1C3), size.minDimension * .48f, center)
        drawCircle(Color(0xFFF7F1E5), size.minDimension * .43f, center)
        val bowls = listOf(
            Triple(Offset(center.x - 35, center.y - 37), Color.White, 38f),
            Triple(Offset(center.x + 45, center.y - 15), Color(0xFFDC6730), 42f),
            Triple(Offset(center.x + 8, center.y + 50), Color(0xFFE4AC30), 33f)
        )
        bowls.forEach { (point, color, radius) ->
            drawCircle(Color(0xFFB4B0A7), radius + 5, point)
            drawCircle(color, radius, point)
        }
        repeat(10) { index ->
            drawCircle(Color(0xFFEAE4D6), 3f, Offset(center.x - 55 + index * 5f, center.y - 40 + (index % 3) * 5f))
        }
        drawCircle(Color(0xFF4B8C3D), 6f, Offset(center.x + 41, center.y - 18))
        drawCircle(Color(0xFFF0F6DF), 16f, Offset(center.x - 51, center.y + 45))
        drawCircle(Color(0xFFD85A45), 12f, Offset(center.x - 27, center.y + 55))
    }
}

@Composable
private fun SignupSectionHeading(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Mist, shape = CircleShape) { Icon(icon, null, tint = Color(0xFF72A92F), modifier = Modifier.padding(11.dp).size(19.dp)) }
        Spacer(Modifier.width(11.dp))
        Column {
            Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SignupLabel(label: String) { Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium) }

@Composable
private fun signupFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    focusedIndicatorColor = Brand,
    unfocusedIndicatorColor = Border
)

@Composable
private fun SignupBenefits() {
    val benefits = listOf(
        Triple(Icons.Outlined.VerifiedUser, "Safe & Secure", "Protected data"),
        Triple(Icons.Outlined.LocalShipping, "Timely Delivery", "On-time meals"),
        Triple(Icons.Outlined.SoupKitchen, "Healthy & Tasty", "Hygienic meals"),
        Triple(Icons.Outlined.AccountBalanceWallet, "Easy Payments", "Secure options")
    )
    Surface(color = Mist, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
            benefits.forEach { benefit ->
                Column(Modifier.weight(1f).padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = Color.White, shape = CircleShape) { Icon(benefit.first, null, tint = Brand, modifier = Modifier.padding(8.dp).size(17.dp)) }
                    Spacer(Modifier.height(5.dp))
                    Text(benefit.second, color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(benefit.third, color = Muted, fontSize = 7.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SignupBackgroundArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawCircle(Color(0xFFEAF4D9), size.width * .45f, Offset(size.width * .93f, size.height * .05f))
        drawOval(Color(0xFFE3F0D4), Offset(-size.width * .15f, size.height * .91f), androidx.compose.ui.geometry.Size(size.width * .8f, size.height * .16f))
        drawOval(Color(0xFFD2E9C1), Offset(size.width * .35f, size.height * .94f), androidx.compose.ui.geometry.Size(size.width * .85f, size.height * .12f))
    }
}

@Composable
private fun ServiceUnavailableScreen(
    pincode: String,
    onExplore: () -> Unit,
    onTryAnotherPincode: () -> Unit
) {
    var notificationRequested by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFBFDF9))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val compact = maxHeight < 760.dp
        SignupBackgroundArt(Modifier.matchParentSize())

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = if (compact) 13.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "zomeal",
                color = Brand,
                fontSize = if (compact) 27.sp else 31.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = if (compact) 8.dp else 14.dp, bottom = if (compact) 7.dp else 11.dp)
            )

            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = Color.White.copy(alpha = .98f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = if (compact) 17.dp else 21.dp, vertical = if (compact) 13.dp else 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(if (compact) 72.dp else 88.dp)
                                .background(
                                    Brush.radialGradient(listOf(Color(0xFFE6F4D9), Color(0xFFF5FAF0))),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.LocationOff,
                                contentDescription = null,
                                tint = Brand,
                                modifier = Modifier.size(if (compact) 35.dp else 42.dp)
                            )
                        }
                        Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                        Text(
                            "We're not in your area yet",
                            color = Ink,
                            fontSize = if (compact) 19.sp else 23.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "No active Zomeal provider currently delivers to",
                            color = Muted,
                            fontSize = if (compact) 9.sp else 10.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Surface(
                            modifier = Modifier.padding(top = 8.dp),
                            color = Mist,
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                        ) {
                            Row(Modifier.padding(horizontal = 13.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.LocationOn, null, tint = Brand, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(pincode.ifBlank { "Your pincode" }, color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(if (compact) 11.dp else 15.dp))
                        Text(
                            "You can still explore Zomeal",
                            color = BrandDark,
                            fontSize = if (compact) 13.sp else 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "Browse kitchens, compare packages and prepare a weekly menu. Add a serviceable delivery address when you're ready to subscribe.",
                            color = Muted,
                            fontSize = if (compact) 9.sp else 10.sp,
                            lineHeight = if (compact) 13.sp else 15.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 5.dp)
                        )
                        Spacer(Modifier.height(if (compact) 7.dp else 10.dp))
                        BrowsePossibilities(compact)
                        Spacer(Modifier.height(if (compact) 9.dp else 13.dp))
                        Surface(color = Color(0xFFFFF8E7), shape = RoundedCornerShape(13.dp)) {
                            Row(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Info, null, tint = Color(0xFFB67600), modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Browsing is open. Subscription and payment unlock after you add an address served by your selected provider.",
                                    color = Color(0xFF735318),
                                    fontSize = if (compact) 8.sp else 9.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = onExplore,
                            modifier = Modifier.fillMaxWidth().height(if (compact) 54.dp else 60.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand)
                        ) {
                            Icon(Icons.Outlined.TravelExplore, null, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(9.dp))
                            Text("Explore Zomeal", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.height(if (compact) 7.dp else 9.dp))
                        OutlinedButton(
                            onClick = onTryAnotherPincode,
                            modifier = Modifier.fillMaxWidth().height(if (compact) 46.dp else 50.dp),
                            shape = RoundedCornerShape(15.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Brand)
                        ) {
                            Icon(Icons.Outlined.EditLocationAlt, null, tint = Brand, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Try Another Pincode", color = BrandDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = { notificationRequested = true },
                            enabled = !notificationRequested,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                if (notificationRequested) Icons.Outlined.CheckCircle else Icons.Outlined.NotificationsActive,
                                null,
                                tint = Brand,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (notificationRequested) "We'll notify you when Zomeal arrives" else "Notify Me When Available",
                                color = BrandDark,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowsePossibilities(compact: Boolean) {
    val items = listOf(
        Triple(Icons.Outlined.Storefront, "Browse", "Kitchens"),
        Triple(Icons.Outlined.RestaurantMenu, "Compare", "Packages"),
        Triple(Icons.Outlined.DateRange, "Create", "Weekly Menu"),
        Triple(Icons.Outlined.BookmarkBorder, "Save", "For Later")
    )
    Surface(color = Mist, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 10.dp else 13.dp)) {
            items.forEach { item ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(item.first, null, tint = Brand, modifier = Modifier.size(if (compact) 19.dp else 22.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(item.second, color = Ink, fontSize = if (compact) 8.sp else 9.sp, fontWeight = FontWeight.Bold)
                    Text(item.third, color = Muted, fontSize = if (compact) 7.sp else 8.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun OtpVerificationScreen(mobile: String, onVerified: (String,(String?) -> Unit) -> Unit, onBack: () -> Unit) {
    var otp by rememberSaveable { mutableStateOf("") }
    var secondsRemaining by rememberSaveable { mutableIntStateOf(24) }
    var verifying by rememberSaveable { mutableStateOf(false) }
    var verificationError by rememberSaveable { mutableStateOf<String?>(null) }
    var consentRestartKey by rememberSaveable { mutableIntStateOf(0) }
    val maskedNumber = if (mobile.length >= 4) "${mobile.take(2)}XXXXXX${mobile.takeLast(2)}" else "98XXXXXX42"

    OtpSmsConsentListener(consentRestartKey) { code ->
        otp = code
        verificationError = null
    }

    BackHandler(onBack = onBack)
    LaunchedEffect(secondsRemaining) {
        if (secondsRemaining > 0) {
            delay(1_000)
            secondsRemaining -= 1
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFFBFDF9))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val compact = maxHeight < 760.dp
        SignupBackgroundArt(Modifier.matchParentSize())

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(start = 12.dp, top = 6.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White)
                .align(Alignment.TopStart)
        ) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = BrandDark, modifier = Modifier.size(18.dp))
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = if (compact) 12.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(if (compact) 158.dp else 184.dp)
                    .height(if (compact) 94.dp else 112.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.zomeal_logo),
                    contentDescription = "Zomeal",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = Color.White.copy(alpha = .98f),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = if (compact) 18.dp else 22.dp, vertical = if (compact) 14.dp else 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(color = Mist, shape = CircleShape) {
                            Icon(
                                Icons.Filled.VerifiedUser,
                                null,
                                tint = Brand,
                                modifier = Modifier.padding(if (compact) 11.dp else 13.dp).size(if (compact) 23.dp else 27.dp)
                            )
                        }
                        Spacer(Modifier.height(if (compact) 9.dp else 13.dp))
                        Text("Verify your number", color = Ink, fontSize = if (compact) 20.sp else 23.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(5.dp))
                        Text("We've sent a 6-digit OTP to", color = Muted, fontSize = if (compact) 10.sp else 11.sp)
                        Text("+91  $maskedNumber", color = BrandDark, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(if (compact) 15.dp else 20.dp))

                        BasicTextField(
                            value = otp,
                            onValueChange = { otp = it.filter(Char::isDigit).take(6) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                            decorationBox = { innerTextField ->
                                Box {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
                                        repeat(6) { index -> OtpCell(otp.getOrNull(index), index == otp.length, compact) }
                                    }
                                    Box(Modifier.size(1.dp)) { innerTextField() }
                                }
                            }
                        )
                        Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, null, tint = Brand, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("00:${secondsRemaining.toString().padStart(2, '0')}", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(" remaining", color = Muted, fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(if (compact) 13.dp else 17.dp))
                        Surface(color = Mist, shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(if (compact) 11.dp else 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.GppGood, null, tint = Brand, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(9.dp))
                                Column {
                                    Text("Your verification code is secure", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("Zomeal will never share it with anyone.", color = Muted, fontSize = 8.sp)
                                }
                            }
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Didn't receive the OTP?", color = Muted, fontSize = 10.sp)
                        TextButton(
                            onClick = { secondsRemaining = 30; otp = ""; consentRestartKey += 1 },
                            enabled = secondsRemaining == 0,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("Resend OTP", color = if (secondsRemaining == 0) BrandDark else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(if (compact) 6.dp else 9.dp))
                        Button(
                            onClick = { if(!verifying){
                                verifying=true
                                verificationError=null
                                onVerified(otp) { error ->
                                    if(error!=null){ verificationError=error; verifying=false }
                                }
                            } },
                            enabled = otp.length == 6 && !verifying,
                            modifier = Modifier.fillMaxWidth().height(if (compact) 54.dp else 60.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand, disabledContainerColor = Border)
                        ) {
                            Text(if(verifying)"Checking serviceability…" else "Verify & Continue", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            if(verifying)CircularProgressIndicator(Modifier.size(18.dp),color=Color.White,strokeWidth=2.dp) else Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                        verificationError?.let { message ->
                            Text(message,color=Color(0xFFD64545),fontSize=10.sp,lineHeight=14.sp,modifier=Modifier.fillMaxWidth())
                        }
                        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Icon(Icons.Outlined.Edit, null, tint = Brand, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Change phone number", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.OtpCell(digit: Char?, focused: Boolean, compact: Boolean = false) {
    Surface(
        modifier = Modifier.weight(1f).height(if (compact) 48.dp else 56.dp),
        color = Color.White,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(if (focused || digit != null) 1.5.dp else 1.dp, if (focused || digit != null) Brand.copy(alpha = .55f) else Border)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(digit?.toString() ?: "—", color = if (digit != null) BrandDark else Border, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ProviderListPreview() { ZomealTheme { ProviderListScreen() } }
