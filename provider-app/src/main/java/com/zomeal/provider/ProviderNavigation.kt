package com.zomeal.provider

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow

@Composable
internal fun ProviderNavigation(selected: String, onDashboard: () -> Unit, onOrders: () -> Unit, onEarnings: () -> Unit, onProfile: () -> Unit, onCosts: () -> Unit) {
    val labels = listOf("Dashboard", "Orders", "Earnings", "Kitchen Costs", "Profile")
    val icons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.ReceiptLong, Icons.Outlined.AccountBalanceWallet, Icons.Outlined.Calculate, Icons.Outlined.AccountCircle)
    val actions = listOf(onDashboard, onOrders, onEarnings, onCosts, onProfile)
    val shortLabels = listOf("Home", "Orders", "Earnings", "Costs", "Profile")
    NavigationBar(containerColor = Color.White, modifier = Modifier.height(64.dp), windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) {
        labels.forEachIndexed { index, label ->
            NavigationBarItem(selected == label, actions[index], { Icon(icons[index], label, Modifier.size(20.dp)) },
                label = { Text(shortLabels[index], fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF087F43), selectedTextColor = Color(0xFF087F43), indicatorColor = Color(0xFFF0F7F2)))
        }
    }
}
