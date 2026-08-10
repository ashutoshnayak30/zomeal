package com.zomeal.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import kotlinx.coroutines.delay

private val Brand = Color(0xFF078A45)
private val BrandDark = Color(0xFF006B38)
private val Lime = Color(0xFFB7DA45)
private val Ink = Color(0xFF10231B)
private val Muted = Color(0xFF66716B)
private val Mist = Color(0xFFF2F8F4)
private val Border = Color(0xFFDCE8E0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZomealTheme { ProviderListScreen() } }
    }
}

@Composable
private fun ZomealTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Brand, background = Color.White, surface = Color.White),
        typography = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        ),
        content = content
    )
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
    val accent: Color
)

private val providers = listOf(
    Provider("Swaad Ghar", "Khandagiri, Bhubaneswar", "Pure Veg", DietFilter.VEG, 4.6, 128, 4999, Color(0xFFFFE0A7), Color(0xFFD37B19)),
    Provider("Odia Tiffin Service", "Jagamara, Bhubaneswar", "Veg & Non-Veg", DietFilter.BOTH, 4.4, 96, 5499, Color(0xFFE8D2A4), Color(0xFFB65D22)),
    Provider("Ma' Home Kitchen", "Patia, Bhubaneswar", "Pure Veg", DietFilter.VEG, 4.7, 152, 4799, Color(0xFFD5E9D1), Color(0xFF4E944C)),
    Provider("Delight Non-Veg Meals", "Nayapalli, Bhubaneswar", "Non-Veg", DietFilter.NON_VEG, 4.5, 87, 5999, Color(0xFFF3C4A5), Color(0xFFA44021))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderListScreen() {
    var signupComplete by rememberSaveable { mutableStateOf(false) }
    var awaitingOtp by rememberSaveable { mutableStateOf(false) }
    var pendingMobile by rememberSaveable { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DietFilter.ALL) }
    var sortByRating by remember { mutableStateOf(false) }
    var selectedNav by remember { mutableIntStateOf(0) }
    var selectedProvider by remember { mutableStateOf<Provider?>(null) }
    var activeProvider by remember { mutableStateOf<Provider?>(null) }

    if (!signupComplete) {
        if (awaitingOtp) {
            OtpVerificationScreen(
                mobile = pendingMobile,
                onVerified = { signupComplete = true },
                onBack = { awaitingOtp = false }
            )
        } else {
            SignupScreen(onContinue = { mobile -> pendingMobile = mobile; awaitingOtp = true })
        }
        return
    }

    activeProvider?.let { provider ->
        ActiveSubscriberHome(provider)
        return
    }

    BackHandler(enabled = selectedProvider != null) { selectedProvider = null }
    selectedProvider?.let { provider ->
        ProviderDetailsScreen(
            provider = provider,
            onBack = { selectedProvider = null },
            onActivated = { activeProvider = provider; selectedProvider = null }
        )
        return
    }

    val visibleProviders = remember(query, filter, sortByRating) {
        providers.filter {
            (query.isBlank() || it.name.contains(query, true) || it.locality.contains(query, true) || it.diet.contains(query, true)) &&
                when (filter) {
                    DietFilter.ALL -> true
                    DietFilter.TOP -> it.rating >= 4.6
                    else -> it.category == filter || it.category == DietFilter.BOTH
                }
        }.let { list -> if (sortByRating) list.sortedByDescending { it.rating } else list }
    }

    Scaffold(
        containerColor = Color.White,
        bottomBar = { ZomealBottomBar(selectedNav) { selectedNav = it } }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { DiscoveryHeader(query, onQueryChange = { query = it }) }
            item { PromoBanner() }
            item {
                ProviderSectionHeader(
                    count = visibleProviders.size,
                    sortByRating = sortByRating,
                    onSort = { sortByRating = !sortByRating }
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(DietFilter.entries) { option ->
                        DietChip(option, option == filter) { filter = option }
                    }
                }
            }
            if (visibleProviders.isEmpty()) {
                item { EmptyProviders(onClear = { query = ""; filter = DietFilter.ALL }) }
            } else {
                items(visibleProviders, key = { it.name }) { provider ->
                    ProviderCard(provider, onClick = { selectedProvider = provider })
                }
            }
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
private fun ProviderSectionHeader(count: Int, sortByRating: Boolean, onSort: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Providers in your area", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
            Text(if (count == 0) "No matching providers" else "Showing providers delivering to 751030", color = Muted, fontSize = 13.sp)
        }
        Surface(
            modifier = Modifier.clickable(onClick = onSort), color = Mist, shape = RoundedCornerShape(18.dp)
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SwapVert, null, tint = Brand, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (sortByRating) "Top first" else "Sort", color = Brand, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DietChip(option: DietFilter, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (selected) Brand else Mist,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Row(Modifier.padding(horizontal = 17.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (option.emoji.isNotEmpty()) {
                Text(option.emoji, color = if (option == DietFilter.NON_VEG) Color(0xFFC94C2D) else if (option == DietFilter.TOP) Color(0xFFFFB400) else Color(0xFF55B627), fontSize = 13.sp)
                Spacer(Modifier.width(7.dp))
            }
            Text(option.label, color = if (selected) Color.White else Ink, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
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
                ProviderFoodArt(provider.accent, Modifier.fillMaxSize())
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
                        Text("3 Meals / Day", color = Muted, fontSize = 10.sp)
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
private fun RatingPill(rating: Double, reviews: Int) {
    Surface(color = Mist, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, null, tint = Color(0xFFFFB400), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text(rating.toString(), color = BrandDark, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(" ($reviews)", color = Muted, fontSize = 8.sp)
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
    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Icon(if (selected == index) item.second else item.third, item.first) },
                label = { Text(item.first, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Brand, selectedTextColor = Brand,
                    indicatorColor = Color.Transparent, unselectedIconColor = Muted, unselectedTextColor = Muted
                )
            )
        }
    }
}

private data class MealPackage(
    val title: String,
    val meals: String,
    val price: String,
    val icon: ImageVector,
    val popular: Boolean = false
)

@Composable
private fun ProviderDetailsScreen(provider: Provider, onBack: () -> Unit, onActivated: () -> Unit) {
    val packages = remember {
        listOf(
            MealPackage("Lunch Only", "1 meal / day", "₹3,499", Icons.Outlined.LightMode),
            MealPackage("Lunch & Dinner", "2 meals / day", "₹6,499", Icons.Outlined.WbTwilight, true),
            MealPackage("Dinner Only", "1 meal / day", "₹3,299", Icons.Outlined.DarkMode)
        )
    }
    var selectedPackage by remember { mutableIntStateOf(1) }
    var menuPackage by remember { mutableStateOf<MealPackage?>(null) }

    BackHandler(enabled = menuPackage != null) { menuPackage = null }
    menuPackage?.let { plan ->
        WeeklyMenuScreen(provider = provider, plan = plan, onBack = { menuPackage = null }, onGoHome = onActivated)
        return
    }

    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 10.dp) {
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
            item { TrustSummary() }
            item { PackageHeader() }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(packages.size) { index ->
                        PackageCard(
                            mealPackage = packages[index],
                            selected = selectedPackage == index,
                            onSelect = { selectedPackage = index }
                        )
                    }
                }
            }
            item { BenefitsStrip() }
            item { AboutProvider(provider) }
            item { QualityBadges() }
            item { DeliveryCard() }
        }
    }
}

@Composable
private fun ProviderDetailsTopBar(onBack: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(104.dp).background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(950f, 300f))
        )
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(start = 12.dp, top = 16.dp).size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).align(Alignment.CenterStart)
        ) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(19.dp)) }
        Text("zomeal", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center))
        Row(Modifier.padding(end = 12.dp, top = 16.dp).align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallHeaderAction(Icons.Outlined.Share, "Share")
            SmallHeaderAction(Icons.Outlined.FavoriteBorder, "Favorite")
        }
    }
}

@Composable
private fun SmallHeaderAction(icon: ImageVector, label: String) {
    IconButton(onClick = { }, modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f))) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun ProviderIdentity(provider: Provider) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(provider.name, color = Ink, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black)
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
private fun TrustSummary() {
    val stats = listOf(
        Triple(Icons.Outlined.WorkspacePremium, "5+", "Years in service"),
        Triple(Icons.Outlined.Groups, "450+", "Happy members"),
        Triple(Icons.Outlined.TakeoutDining, "Tiffin", "Steel delivery"),
        Triple(Icons.Outlined.VerifiedUser, "Verified", "FSSAI licensed")
    )
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            stats.forEachIndexed { index, item ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(item.first, null, tint = Brand, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(7.dp))
                    Text(item.second, color = BrandDark, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text(item.third, color = Ink, fontSize = 9.sp, maxLines = 1)
                }
                if (index < stats.lastIndex) Box(Modifier.width(1.dp).height(54.dp).background(Border))
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
private fun PackageCard(mealPackage: MealPackage, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        modifier = Modifier.width(150.dp).height(220.dp).clickable(onClick = onSelect),
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
            Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(if (mealPackage.popular) 24.dp else 12.dp))
                Icon(mealPackage.icon, null, tint = if (mealPackage.title.contains("Dinner")) Color(0xFF34547A) else Color(0xFFFFB300), modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(9.dp))
                Text(mealPackage.title, color = if (selected) BrandDark else Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(mealPackage.meals, color = Muted, fontSize = 10.sp)
                Spacer(Modifier.height(13.dp))
                Text(mealPackage.price, color = BrandDark, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("/ month", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onSelect,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Brand else Color.Transparent, contentColor = if (selected) Color.White else BrandDark),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Brand)
                ) { Text(if (selected) "Selected" else "Select", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
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
            Text("Serving healthy, home-style meals since 2019. Our focus is taste, hygiene and your satisfaction.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(.75f).height(104.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFDCEAD8))) {
            ProviderPortrait(Modifier.fillMaxSize())
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
    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(badges) { badge ->
            Surface(color = Mist, shape = RoundedCornerShape(15.dp)) {
                Row(Modifier.width(138.dp).padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun DeliveryCard() {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Mist, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.height(112.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.2f)) {
                Text("Meal Delivery", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(7.dp))
                Text("Meals are delivered daily in stainless-steel tiffins for freshness and hygiene.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
            TiffinArt(Modifier.weight(.8f).fillMaxHeight())
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

private data class MenuChoice(val name: String, val base: Color, val garnish: Color)

private val lunchChoices = listOf(
    MenuChoice("Paneer Butter Masala", Color(0xFFD96A2B), Color(0xFFF4D184)),
    MenuChoice("Dal Tadka", Color(0xFFE4AD2E), Color(0xFF7A9B3A))
)

private val dinnerChoices = listOf(
    MenuChoice("Seasonal Mix Veg", Color(0xFFB75B35), Color(0xFF4E9B51)),
    MenuChoice("Egg Tadka", Color(0xFFC94E2F), Color(0xFFFFD56A))
)

@Composable
private fun WeeklyMenuScreen(provider: Provider, plan: MealPackage, onBack: () -> Unit, onGoHome: () -> Unit) {
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val dates = listOf("21", "22", "23", "24", "25", "26", "27")
    var selectedDay by remember { mutableIntStateOf(0) }
    val lunchSelections = remember { mutableStateMapOf<Int, String>().apply { days.indices.forEach { put(it, lunchChoices.first().name) } } }
    val dinnerSelections = remember { mutableStateMapOf<Int, String>().apply { days.indices.forEach { put(it, dinnerChoices.first().name) } } }
    val lunchCarbs = remember { mutableStateMapOf<Int, String>().apply { days.indices.forEach { put(it, "Rice") } } }
    val dinnerCarbs = remember { mutableStateMapOf<Int, String>().apply { days.indices.forEach { put(it, "Roti") } } }
    val showLunch = plan.title != "Dinner Only"
    val showDinner = plan.title != "Lunch Only"
    var showReview by remember { mutableStateOf(false) }

    BackHandler(enabled = showReview) { showReview = false }
    if (showReview) {
        ReviewPlanScreen(
            provider = provider,
            plan = plan,
            lunchSelections = lunchSelections,
            dinnerSelections = dinnerSelections,
            onBack = { showReview = false },
            onGoHome = onGoHome
        )
        return
    }

    Scaffold(
        containerColor = Color.White,
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 10.dp) {
                Button(
                    onClick = { showReview = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF56843E))
                ) {
                    Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Review Weekly Plan", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { WeeklyMenuHeader(onBack) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(days.size) { index ->
                        DayCard(days[index], dates[index], selectedDay == index) { selectedDay = index }
                    }
                }
            }
            if (showLunch) {
                item {
                    MealSlotEditor(
                        title = "Lunch",
                        icon = Icons.Outlined.LightMode,
                        accent = Color(0xFF5B873E),
                        choices = lunchChoices,
                        selectedChoice = lunchSelections[selectedDay] ?: lunchChoices.first().name,
                        onChoice = { lunchSelections[selectedDay] = it },
                        carbOptions = listOf("Rice", "Roti"),
                        selectedCarb = lunchCarbs[selectedDay] ?: "Rice",
                        onCarb = { lunchCarbs[selectedDay] = it },
                        included = listOf("Dal", "Aloo bhaja", "Bharta", "Achar")
                    )
                }
            }
            if (showDinner) {
                item {
                    MealSlotEditor(
                        title = "Dinner",
                        icon = Icons.Outlined.DarkMode,
                        accent = Color(0xFF8050B7),
                        choices = dinnerChoices,
                        selectedChoice = dinnerSelections[selectedDay] ?: dinnerChoices.first().name,
                        onChoice = { dinnerSelections[selectedDay] = it },
                        carbOptions = listOf("Roti", "Paratha", "Puri"),
                        selectedCarb = dinnerCarbs[selectedDay] ?: "Roti",
                        onCarb = { dinnerCarbs[selectedDay] = it },
                        included = listOf("Dal", "Aloo bhaja", "Bharta", "Salad")
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklyMenuHeader(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = Ink, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Weekly Menu Set", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            Text("Set your menu for the week", color = Muted, fontSize = 12.sp)
        }
        Icon(Icons.Outlined.CalendarMonth, null, tint = BrandDark, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DayCard(day: String, date: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(58.dp).height(72.dp).clickable(onClick = onClick),
        color = if (selected) Color(0xFF668F48) else Color.White,
        contentColor = if (selected) Color.White else Muted,
        shape = RoundedCornerShape(16.dp),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Border),
        shadowElevation = if (selected) 3.dp else 0.dp
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(day, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Text(date, fontSize = 12.sp)
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    choices.forEach { choice ->
                        MenuChoiceCard(
                            choice = choice,
                            selected = selectedChoice == choice.name,
                            accent = accent,
                            onClick = { onChoice(choice.name) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text("Carb  ·  Choose 1", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(included) { item -> IncludedSide(item, accent) }
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
                DishArt(choice, Modifier.fillMaxWidth().weight(1f))
                Text(choice.name, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun IncludedSide(label: String, accent: Color) {
    Surface(color = Color.White, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Row(Modifier.widthIn(min = 92.dp).padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
    onGoHome: () -> Unit
) {
    val basePrice = plan.price.filter { it.isDigit() }.toIntOrNull() ?: 0
    val platformFee = (basePrice * .03).toInt()
    val discount = 300
    val total = basePrice + platformFee - discount
    val showLunch = plan.title != "Dinner Only"
    val showDinner = plan.title != "Lunch Only"
    var showAllDays by remember { mutableStateOf(false) }
    var showPayment by remember { mutableStateOf(false) }

    BackHandler(enabled = showPayment) { showPayment = false }
    if (showPayment) {
        PaymentScreen(
            provider = provider,
            plan = plan,
            basePrice = basePrice,
            platformFee = platformFee,
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
            Surface(color = Color.White, shadowElevation = 10.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(112.dp)) {
                        Text("Total amount", color = Muted, fontSize = 10.sp)
                        Text(formatRupees(total), color = BrandDark, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Text("Inclusive of taxes", color = Muted, fontSize = 9.sp)
                    }
                    Button(
                        onClick = { showPayment = true },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(17.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand)
                    ) {
                        Text("Proceed to Payment", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
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
            item { ReviewHeader(onBack) }
            item { ReviewProviderCard(provider) }
            item { ReviewSectionTitle("Your Selected Plan") }
            item { SelectedPlanCard(plan) }
            item {
                WeeklyPreviewCard(
                    showLunch = showLunch,
                    showDinner = showDinner,
                    lunchSelections = lunchSelections,
                    dinnerSelections = dinnerSelections,
                    expanded = showAllDays,
                    onToggle = { showAllDays = !showAllDays }
                )
            }
            item { AddressReviewCard() }
            item { DeliveryInformationCard(provider.name, showLunch, showDinner) }
            item { PriceDetailsCard(plan, basePrice, platformFee, discount, total) }
            item { PoliciesCard() }
        }
    }
}

private fun formatRupees(value: Int) = "₹${"%,d".format(value)}"

@Composable
private fun ReviewHeader(onBack: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(148.dp).background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(950f, 380f)),
            RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
        )
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(start = 12.dp, top = 20.dp).size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).align(Alignment.CenterStart)
        ) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(19.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Review Your Plan", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            Text("Everything look good?", color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
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
            ProviderFoodArt(provider.accent, Modifier.fillMaxSize())
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
    dinnerSelections: Map<Int, String>,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your Weekly Menu Preview", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onToggle) { Text(if (expanded) "Show less" else "View full menu", color = BrandDark, fontSize = 10.sp) }
        }
        Surface(color = Mist, shape = RoundedCornerShape(18.dp)) {
            Column {
                val visibleDays = if (expanded) days.indices.toList() else days.indices.take(5)
                LazyRow(contentPadding = PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(visibleDays) { index ->
                        Column(Modifier.width(68.dp).padding(vertical = 14.dp, horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(days[index], color = if (index == 0) BrandDark else Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (showLunch) Icon(Icons.Outlined.LightMode, null, tint = Color(0xFFFFB300), modifier = Modifier.size(14.dp))
                                if (showLunch && showDinner) Text(" + ", color = Muted, fontSize = 9.sp)
                                if (showDinner) Icon(Icons.Outlined.DarkMode, null, tint = Color(0xFF34547A), modifier = Modifier.size(14.dp))
                            }
                            Text(
                                listOfNotNull(
                                    lunchSelections[index]?.takeIf { showLunch },
                                    dinnerSelections[index]?.takeIf { showDinner }
                                ).joinToString(" + "),
                                color = Muted,
                                fontSize = 8.sp,
                                lineHeight = 11.sp,
                                maxLines = 3
                            )
                        }
                    }
                }
                TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) "Collapse" else "View all days", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = BrandDark, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun AddressReviewCard() {
    ReviewInfoSurface {
        IconCircle(Icons.Outlined.LocationOn)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Delivery Address", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text("Home", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Plot No. 123, Khandagiri, Bhubaneswar, Odisha - 751030", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
        OutlinedButton(onClick = { }, modifier = Modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 13.dp), shape = RoundedCornerShape(15.dp)) {
            Text("Change", color = BrandDark, fontSize = 10.sp)
        }
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
private fun PriceDetailsCard(plan: MealPackage, base: Int, fee: Int, discount: Int, total: Int) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Price Details", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            PriceRow("Monthly plan (${plan.title})", formatRupees(base))
            PriceRow("Platform fee", formatRupees(fee))
            PriceRow("Discount", "− ${formatRupees(discount)}", BrandDark)
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
        Row {
            PolicyFact(Icons.Outlined.Edit, "Edit before", "cut-off time", Modifier.weight(1f))
            PolicyFact(Icons.Outlined.PauseCircle, "Pause eligible", "meals anytime", Modifier.weight(1f))
            PolicyFact(Icons.Outlined.HealthAndSafety, "Secure & hygienic", "home-style meals", Modifier.weight(1f))
        }
        TextButton(onClick = { }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("View cancellation & pause policy", color = BrandDark, fontSize = 10.sp)
            Icon(Icons.Filled.ChevronRight, null, tint = BrandDark, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun PolicyFact(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        IconCircle(icon)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(title, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(subtitle, color = Muted, fontSize = 8.sp, maxLines = 1)
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

@Composable
private fun PaymentScreen(
    provider: Provider,
    plan: MealPackage,
    basePrice: Int,
    platformFee: Int,
    discount: Int,
    total: Int,
    onBack: () -> Unit,
    onGoHome: () -> Unit
) {
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

    if (paymentComplete) {
        PaymentSuccessScreen(provider = provider, plan = plan, total = total, onGoHome = onGoHome)
        return
    }

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 10.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(112.dp)) {
                            Text("Total amount", color = Muted, fontSize = 10.sp)
                            Text(formatRupees(total), color = BrandDark, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            Text("Inclusive of taxes", color = Muted, fontSize = 9.sp)
                        }
                        Button(
                            onClick = { paymentComplete = true },
                            modifier = Modifier.weight(1f).height(54.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand)
                        ) {
                            Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Pay ${formatRupees(total)} Securely", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { PaymentHeader(onBack) }
            item { PaymentPlanSummary(provider, plan) }
            item { PaymentAmountCard(plan, basePrice, platformFee, discount, total) }
            item { Text("Choose Payment Method", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 20.dp)) }
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
        }
    }
}

@Composable
private fun PaymentHeader(onBack: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(138.dp).background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(950f, 360f)),
            RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
        )
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.padding(start = 12.dp, top = 18.dp).size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = .18f)).align(Alignment.CenterStart)
        ) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(19.dp)) }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Payment", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            Text("Complete your secure payment", color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
        }
        Row(Modifier.padding(end = 16.dp, top = 24.dp).align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GppGood, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(5.dp))
            Column {
                Text("100%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Secure", color = Color.White, fontSize = 10.sp)
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

@Composable
private fun PaymentSuccessScreen(provider: Provider, plan: MealPackage, total: Int, onGoHome: () -> Unit) {
    val firstSlot = if (plan.title == "Dinner Only") "Dinner" else "Lunch"
    val firstMeal = if (firstSlot == "Dinner") "Seasonal Mix Veg" else "Paneer Butter Masala"

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFFAFCFA)),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { SuccessHero() }
        item { SuccessProviderCard(provider) }
        item { SuccessPlanDetails(plan) }
        item { PaymentConfirmedCard(total) }
        item { FirstMealCard(firstSlot, firstMeal) }
        item { WhatsNextSection() }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onGoHome,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand)
                ) {
                    Icon(Icons.Outlined.Home, null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Go to Home", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                }
                TextButton(onClick = { }) { Text("View My Plan", color = BrandDark, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun SuccessHero() {
    Box(
        Modifier.fillMaxWidth().height(306.dp).background(
            Brush.linearGradient(listOf(BrandDark, Brand, Lime), start = Offset.Zero, end = Offset(950f, 620f)),
            RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
        )
    ) {
        ConfettiArt(Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Color.White, shape = CircleShape, shadowElevation = 10.dp) {
                Icon(Icons.Filled.Check, null, tint = Brand, modifier = Modifier.padding(22.dp).size(43.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("You're all set!", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Black)
            Text("Your meal plan is now active", color = Color.White.copy(alpha = .92f), fontSize = 15.sp)
            Spacer(Modifier.height(7.dp))
            Text("Welcome to the Zomeal family!", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(provider.tint)) {
                ProviderFoodArt(provider.accent, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    RatingPill(provider.rating, provider.reviews)
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, null, tint = Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(provider.locality, color = Muted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(8.dp))
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
private fun SuccessPlanDetails(plan: MealPackage) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(top = 15.dp, bottom = 12.dp)) {
            Text("Your Plan Details", color = BrandDark, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReviewPlanFact(Icons.Outlined.CalendarMonth, "Monthly package", "30 days plan", Modifier.weight(1f))
                ReviewDivider()
                ReviewPlanFact(plan.icon, plan.title, plan.meals, Modifier.weight(1f))
                ReviewDivider()
                ReviewPlanFact(Icons.Outlined.EventAvailable, "Start date", "24 Aug 2026", Modifier.weight(1f))
                ReviewDivider()
                ReviewPlanFact(Icons.Outlined.Event, "End date", "22 Sep 2026", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PaymentConfirmedCard(total: Int) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = Mist, shape = RoundedCornerShape(17.dp)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Brand, shape = CircleShape) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.padding(11.dp).size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Payment Successful!", color = BrandDark, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text("We've received your payment of ${formatRupees(total)}.", color = Muted, fontSize = 10.sp)
                Text("Your plan is confirmed and ready to begin.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun FirstMealCard(slot: String, meal: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("Your First Meal", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(9.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(19.dp), shadowElevation = 3.dp) {
            Row(Modifier.height(152.dp)) {
                Column(Modifier.weight(1.08f).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (slot == "Lunch") Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null, tint = if (slot == "Lunch") Color(0xFFFFB300) else Color(0xFF34547A), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(slot, color = BrandDark, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(meal, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Rice · Dal · Salad", color = Muted, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Event, null, tint = Brand, modifier = Modifier.size(13.dp))
                        Text(" 24 Aug 2026", color = Muted, fontSize = 9.sp)
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Outlined.Schedule, null, tint = Muted, modifier = Modifier.size(13.dp))
                        Text(if (slot == "Lunch") " 12–2 PM" else " 7–9 PM", color = Muted, fontSize = 9.sp)
                    }
                }
                Box(Modifier.weight(.92f).fillMaxHeight().background(Color(0xFFFFE2B8))) {
                    DishArt(lunchChoices.first(), Modifier.fillMaxSize())
                }
            }
        }
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

@Composable
private fun ActiveSubscriberHome(provider: Provider) {
    var lunchActive by remember { mutableStateOf(true) }
    var dinnerActive by remember { mutableStateOf(true) }
    var selectedNav by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = Color(0xFFFAFCFA),
        bottomBar = { ZomealBottomBar(selectedNav) { selectedNav = it } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { SubscriberHeader(provider) }
            item { TodayMenuHeader() }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        DailyMealCard(
                            slot = "Lunch",
                            meal = "Paneer Butter Masala",
                            sides = "Rice · Dal · Salad · Achar",
                            accent = Color(0xFF16834A),
                            choice = lunchChoices.first(),
                            calories = 542,
                            protein = 18,
                            carbs = 72,
                            fat = 18,
                            active = lunchActive,
                            onActiveChange = { lunchActive = it },
                            modifier = Modifier.weight(1f)
                        )
                        DailyMealCard(
                            slot = "Dinner",
                            meal = "Seasonal Mix Veg",
                            sides = "Roti · Dal · Salad · Achar",
                            accent = Color(0xFF6546A8),
                            choice = dinnerChoices.first(),
                            calories = 456,
                            protein = 14,
                            carbs = 64,
                            fat = 16,
                            active = dinnerActive,
                            onActiveChange = { dinnerActive = it },
                            modifier = Modifier.weight(1f)
                        )
                }
            }
            item { NextMealCard(provider.name) }
            item { NutritionOverview() }
            item { SubscriberQuickActions() }
        }
    }
}

@Composable
private fun SubscriberHeader(provider: Provider) {
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
        Row(Modifier.padding(end = 16.dp, top = 24.dp).align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box {
                HomeHeaderAction(Icons.Outlined.Notifications, "Notifications")
                Surface(color = Color(0xFFE53935), shape = CircleShape, modifier = Modifier.size(17.dp).align(Alignment.TopEnd)) {
                    Box(contentAlignment = Alignment.Center) { Text("3", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HomeHeaderAction(Icons.Outlined.AccountBalanceWallet, "Wallet")
                Text("₹1,250", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) { ActivePlanCard(provider) }
    }
}

@Composable
private fun HomeHeaderAction(icon: ImageVector, label: String) {
    IconButton(onClick = { }, modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = .17f))) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun ActivePlanCard(provider: Provider) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(21.dp), shadowElevation = 5.dp) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Brand, shape = CircleShape) { Icon(Icons.Outlined.CalendarMonth, null, tint = Color.White, modifier = Modifier.padding(13.dp).size(23.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Your Plan", color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(7.dp))
                    Surface(color = Mist, shape = RoundedCornerShape(8.dp)) { Text(provider.diet, color = BrandDark, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) }
                }
                Text("Monthly · Lunch + Dinner · 30 days", color = Muted, fontSize = 9.sp)
            }
            Box(Modifier.width(1.dp).height(50.dp).background(Border))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.width(94.dp)) {
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
private fun TodayMenuHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Today's Menu", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("Sunday, 24 Aug 2026", color = Muted, fontSize = 11.sp)
        }
        OutlinedButton(onClick = { }, modifier = Modifier.height(38.dp), shape = RoundedCornerShape(17.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
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
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
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
                Switch(checked = active, onCheckedChange = onActiveChange, modifier = Modifier.scale(.58f).width(34.dp), colors = SwitchDefaults.colors(checkedThumbColor = Brand, checkedTrackColor = Mist))
            }
            Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(13.dp)).background(choice.base.copy(alpha = .14f))) {
                DishArt(choice, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(7.dp))
            Text(meal, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sides, color = Muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CompactMacro("$calories", "kcal")
                CompactMacro("${protein}g", "protein")
                CompactMacro("${carbs}g", "carbs")
                CompactMacro("${fat}g", "fat")
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedButton(onClick = { onActiveChange(false) }, modifier = Modifier.weight(1f).height(34.dp), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Outlined.PauseCircle, null, tint = Color(0xFFD64545), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Cancel", color = Color(0xFFD64545), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { }, modifier = Modifier.weight(1.15f).height(34.dp), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(0.dp)) {
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
            OutlinedButton(onClick = { }, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 11.dp)) {
                Text("Track order", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NutritionOverview() {
    val nutrients = listOf(
        Triple("998", "Calories", .5f), Triple("32g", "Protein", .64f), Triple("136g", "Carbs", .48f), Triple("34g", "Fat", .54f)
    )
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(19.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text("Today's Nutrition Overview", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text("See details", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
private fun SubscriberQuickActions() {
    val actions = listOf(
        Triple(Icons.Outlined.PauseCircle, "Pause Plan", "Pause meals"),
        Triple(Icons.Outlined.CalendarMonth, "My Plan", "View details"),
        Triple(Icons.Outlined.ShoppingBag, "Order History", "Past orders"),
        Triple(Icons.Outlined.SupportAgent, "Support", "We're here")
    )
    Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(vertical = 15.dp)) {
            actions.forEach { action ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
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
private fun SignupScreen(onContinue: (String) -> Unit) {
    var mobile by rememberSaveable { mutableStateOf("") }
    var pincode by rememberSaveable { mutableStateOf("") }
    var locality by rememberSaveable { mutableStateOf("") }
    val valid = mobile.length == 10 && pincode.length == 6

    Box(Modifier.fillMaxSize().background(Color(0xFFFBFDF9))) {
        SignupBackgroundArt(Modifier.fillMaxSize())
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { SignupHero() }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    color = Color.White,
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 5.dp
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        SignupSectionHeading(Icons.Outlined.PhoneAndroid, "Enter your details", "We'll personalize your experience")
                        SignupLabel("Mobile Number")
                        TextField(
                            value = mobile,
                            onValueChange = { mobile = it.filter(Char::isDigit).take(10) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Enter your mobile number", fontSize = 12.sp) },
                            leadingIcon = { Text("IN  +91", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = signupFieldColors()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, null, tint = Muted, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("We'll send an OTP to verify your number", color = Muted, fontSize = 9.sp)
                        }
                        HorizontalDivider(color = Border)
                        SignupSectionHeading(Icons.Outlined.LocationOn, "Where do we deliver?", "This helps us show meals near you")
                        SignupLabel("Pincode")
                        TextField(
                            value = pincode,
                            onValueChange = { pincode = it.filter(Char::isDigit).take(6) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Enter your pincode", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Outlined.LocationOn, null, tint = Brand, modifier = Modifier.size(17.dp)) },
                            trailingIcon = {
                                TextButton(onClick = { pincode = "751030"; locality = "Khandagiri, Bhubaneswar" }) {
                                    Icon(Icons.Outlined.MyLocation, null, tint = Brand, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Detect location", color = BrandDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = signupFieldColors()
                        )
                        Text("We'll show providers delivering to this area", color = Muted, fontSize = 9.sp)
                        SignupLabel("Locality / Area  (Optional)")
                        TextField(
                            value = locality,
                            onValueChange = { locality = it.take(50) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Enter your locality or area", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Outlined.Apartment, null, tint = Brand, modifier = Modifier.size(17.dp)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = signupFieldColors()
                        )
                        Text("Helps us refine your meal recommendations", color = Muted, fontSize = 9.sp)
                        SignupBenefits()
                        Button(
                            onClick = { onContinue(mobile) },
                            enabled = valid,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand, disabledContainerColor = Border)
                        ) {
                            Text("Continue", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            Text("Already have an account? ", color = Muted, fontSize = 10.sp)
                            Text("Login", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onContinue(if (mobile.length == 10) mobile else "9876543242") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignupHero() {
    Box(Modifier.fillMaxWidth().height(360.dp)) {
        IconButton(onClick = { }, modifier = Modifier.padding(start = 18.dp, top = 20.dp).size(42.dp).clip(CircleShape).background(Color.White).align(Alignment.TopStart)) {
            Icon(Icons.Filled.ArrowBack, "Back", tint = BrandDark, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.padding(start = 24.dp, top = 98.dp).width(220.dp)) {
            Text("zomeal", color = Brand, fontSize = 43.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(18.dp))
            Text("Let's get you started!", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text("Create your account to discover monthly meals in your area.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        SignupThaliArt(Modifier.size(230.dp).align(Alignment.CenterEnd).offset(x = 62.dp, y = 8.dp))
    }
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
private fun OtpVerificationScreen(mobile: String, onVerified: () -> Unit, onBack: () -> Unit) {
    var otp by rememberSaveable { mutableStateOf("") }
    var secondsRemaining by rememberSaveable { mutableIntStateOf(24) }
    val maskedNumber = if (mobile.length >= 4) "${mobile.take(2)}XXXXXX${mobile.takeLast(2)}" else "98XXXXXX42"

    BackHandler(onBack = onBack)
    LaunchedEffect(secondsRemaining) {
        if (secondsRemaining > 0) {
            delay(1_000)
            secondsRemaining -= 1
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFFBFDF9))) {
        SignupBackgroundArt(Modifier.fillMaxSize())
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(430.dp)) {
                    IconButton(onClick = onBack, modifier = Modifier.padding(start = 18.dp, top = 20.dp).size(42.dp).clip(CircleShape).background(Color.White).align(Alignment.TopStart)) {
                        Icon(Icons.Filled.ArrowBack, "Change phone number", tint = BrandDark, modifier = Modifier.size(19.dp))
                    }
                    Text("zomeal", color = Brand, fontSize = 42.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
                    SignupThaliArt(Modifier.size(255.dp).align(Alignment.BottomCenter).offset(y = 25.dp))
                }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    color = Color.White,
                    shape = RoundedCornerShape(28.dp),
                    shadowElevation = 5.dp
                ) {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(color = Mist, shape = CircleShape) {
                            Icon(Icons.Filled.VerifiedUser, null, tint = Brand, modifier = Modifier.padding(16.dp).size(28.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Verify your number", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(9.dp))
                        Text("We've sent a 6-digit OTP to", color = Muted, fontSize = 12.sp)
                        Text("+91  $maskedNumber", color = BrandDark, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(22.dp))
                        BasicTextField(
                            value = otp,
                            onValueChange = { otp = it.filter(Char::isDigit).take(6) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                            decorationBox = { innerTextField ->
                                Box {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        repeat(6) { index -> OtpCell(otp.getOrNull(index), index == otp.length) }
                                    }
                                    Box(Modifier.size(1.dp)) { innerTextField() }
                                }
                            }
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Schedule, null, tint = Brand, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("00:${secondsRemaining.toString().padStart(2, '0')}", color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(" remaining", color = Muted, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(20.dp))
                        Surface(color = Mist, shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.GppGood, null, tint = Brand, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(9.dp))
                                Column {
                                    Text("Your verification code is 100% secure.", color = BrandDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("Zomeal will never share it with anyone.", color = Muted, fontSize = 9.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Didn't receive OTP?", color = Muted, fontSize = 11.sp)
                        TextButton(
                            onClick = { secondsRemaining = 30; otp = "" },
                            enabled = secondsRemaining == 0
                        ) { Text("Resend OTP", color = if (secondsRemaining == 0) BrandDark else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onVerified,
                            enabled = otp.length == 6,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Brand, disabledContainerColor = Border)
                        ) {
                            Text("Verify & Continue", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                        TextButton(onClick = onBack) {
                            Icon(Icons.Outlined.Edit, null, tint = Brand, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Change phone number", color = BrandDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.OtpCell(digit: Char?, focused: Boolean) {
    Surface(
        modifier = Modifier.weight(1f).aspectRatio(.72f),
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
