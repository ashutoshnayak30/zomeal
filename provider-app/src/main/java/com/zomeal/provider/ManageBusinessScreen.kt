package com.zomeal.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MBrand = Color(0xFF087F43)
private val MInk = Color(0xFF14221B)
private val MMuted = Color(0xFF68736D)
private val MMist = Color(0xFFF0F7F2)

@Composable
fun ManageBusinessScreen(
    message: String?,
    loading: Boolean,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onEditPackages: () -> Unit,
    onEditMenus: () -> Unit,
    onEditPhotos: () -> Unit,
    onSubmitAll: () -> Unit
) {
    val enabledPackages = buildList {
        if (ProviderDraft.lunchEnabled) add("Lunch ₹${ProviderDraft.lunchPrice}")
        if (ProviderDraft.dinnerEnabled) add("Dinner ₹${ProviderDraft.dinnerPrice}")
        if (ProviderDraft.bothEnabled) add("Lunch + Dinner ₹${ProviderDraft.bothPrice}")
    }
    val totalDishes = ProviderDraft.menus.sumOf { day ->
        day.lunch.count { it.name.isNotBlank() } + day.dinner.count { it.name.isNotBlank() }
    }
    val dishPhotos = ProviderDraft.menus.sumOf { day ->
        (day.lunch + day.dinner).count { it.name.isNotBlank() && it.photo != null }
    }
    val businessPhotos = listOf(ProviderDraft.profilePhoto, ProviderDraft.kitchenPhoto, ProviderDraft.mealPhoto).count { it != null }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = Color(0xFFF7FAF7),
        topBar = {
            Surface(shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Dashboard", tint = MBrand) }
                    Column(Modifier.weight(1f)) {
                        Text("Manage business", color = MInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Profile, packages, menus and photos", color = MMuted, fontSize = 11.sp)
                    }
                    Text("zomeal", color = MBrand, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MBrand).padding(18.dp)) {
                    Text(ProviderDraft.businessName.ifBlank { "Your provider profile" }, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${ProviderDraft.category} · ${ProviderDraft.city}", color = Color.White.copy(alpha = .82f), fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Changes to packages, menus and photographs are reviewed before customers see them. Your current approved listing stays live meanwhile.", color = Color.White.copy(alpha = .9f), fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            message?.let { text -> item { ManageNotice(text) } }
            if (loading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MBrand) }
            item {
                ManageCard(
                    icon = Icons.Outlined.Storefront,
                    title = "Provider profile",
                    summary = ProviderDraft.contactName.ifBlank { "Contact details incomplete" },
                    detail = listOf(ProviderDraft.address, ProviderDraft.city, ProviderDraft.state).filter { it.isNotBlank() }.joinToString(", "),
                    action = "Edit profile",
                    onClick = onEditProfile
                )
            }
            item {
                ManageCard(
                    icon = Icons.Outlined.Inventory2,
                    title = "Packages & prices",
                    summary = "${enabledPackages.size} package${if (enabledPackages.size == 1) "" else "s"}",
                    detail = enabledPackages.joinToString("  ·  ").ifBlank { "No active package selected" },
                    action = "Manage packages",
                    onClick = onEditPackages
                )
            }
            item {
                ManageCard(
                    icon = Icons.Outlined.RestaurantMenu,
                    title = "Seven-day menu",
                    summary = "$totalDishes main-course entries",
                    detail = "Monday–Sunday · Lunch and dinner · $dishPhotos dish photos selected",
                    action = "View & edit menu",
                    onClick = onEditMenus
                )
            }
            item {
                ManageCard(
                    icon = Icons.Outlined.AddAPhoto,
                    title = "Business photos & delivery",
                    summary = "$businessPhotos of 3 business photos selected",
                    detail = "Provider profile, kitchen, complete meal and delivery contact",
                    action = "Manage photos",
                    onClick = onEditPhotos
                )
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(MMist).padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhoneAndroid, null, tint = MBrand, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp)); Text("Verified account number", color = MInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Text("+91 ${ProviderDraft.ownerPhone}", color = MInk, fontSize = 13.sp)
                    Text("For account safety, changing the verified login number requires a separate OTP verification flow. Delivery contact numbers can be edited under Photos & delivery.", color = MMuted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSubmitAll,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MBrand),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Icon(Icons.Outlined.CloudUpload, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (loading) "Submitting…" else "Submit all changes for review", fontWeight = FontWeight.Bold)
                    }
                    Text("Profile, packages, all seven menu days and new photos will be sent together as one admin request.", color = MMuted, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
        }
    }
}

@Composable
private fun ManageCard(icon: ImageVector, title: String, summary: String, detail: String, action: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(MMist), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MBrand) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = MInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(summary, color = MBrand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MBrand)
        }
        Text(detail.ifBlank { "Information not added yet" }, color = MMuted, fontSize = 11.sp, lineHeight = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Edit, null, tint = MBrand, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp)); Text(action, color = MBrand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ManageNotice(text: String) {
    val failed = text.startsWith("Could not", ignoreCase = true) || text.startsWith("Changes need correction", ignoreCase = true)
    val color = if (failed) Color(0xFFB3261E) else MBrand
    val background = if (failed) Color(0xFFFFEDEA) else Color(0xFFE7F5EA)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(background).padding(13.dp), verticalAlignment = Alignment.Top) {
        Icon(if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, tint = color, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp)); Text(text, color = color, fontSize = 11.sp, lineHeight = 16.sp)
    }
}
