package com.sethu.emergency.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sethu.emergency.ui.components.GlowingSosButton
import com.sethu.emergency.ui.components.RoleSelectionCard
import com.sethu.emergency.ui.components.SethuBottomNavigationBar
import com.sethu.emergency.ui.components.SethuIcons
import com.sethu.emergency.ui.theme.AccentGreen
import com.sethu.emergency.ui.theme.CardBackground
import com.sethu.emergency.ui.theme.CardBorder
import com.sethu.emergency.ui.theme.DarkBackground
import com.sethu.emergency.ui.theme.TextPrimary
import com.sethu.emergency.ui.theme.TextSecondary

enum class DeviceRole {
    SOS_SENDER,
    RELAY_DEVICE,
    COORDINATOR
}

@Composable
fun HomeScreen(
    selectedRole: DeviceRole,
    onRoleSelected: (DeviceRole) -> Unit,
    onNavigateToRole: (DeviceRole) -> Unit,
    onSosClick: () -> Unit,
    onTabSelected: (String) -> Unit
) {
    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            SethuBottomNavigationBar(
                currentTab = "Home",
                onTabSelected = onTabSelected
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Header with Logo & Brand
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = SethuIcons.Logo,
                    contentDescription = "Sethu Logo",
                    tint = AccentGreen,
                    modifier = Modifier.size(36.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "Sethu",
                        color = TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Emergency Network",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Top Info Card: Stay connected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardBackground)
                    .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF10281D)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = SethuIcons.Shield,
                            contentDescription = "Safety Shield",
                            tint = AccentGreen,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "Stay connected.",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Help each other. Save lives.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Role Selection Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = "Select Your Role",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Role Card 1: SOS Sender
            RoleSelectionCard(
                title = "SOS Sender",
                description = "Report an emergency and send to nearby devices",
                icon = SethuIcons.SendPlane,
                isSelected = selectedRole == DeviceRole.SOS_SENDER,
                onClick = {
                    onRoleSelected(DeviceRole.SOS_SENDER)
                    onNavigateToRole(DeviceRole.SOS_SENDER)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Role Card 2: Relay Device
            RoleSelectionCard(
                title = "Relay Device",
                description = "Forward emergency messages to other devices",
                icon = SethuIcons.RelayNodes,
                isSelected = selectedRole == DeviceRole.RELAY_DEVICE,
                onClick = {
                    onRoleSelected(DeviceRole.RELAY_DEVICE)
                    onNavigateToRole(DeviceRole.RELAY_DEVICE)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Role Card 3: Emergency Coordinator
            RoleSelectionCard(
                title = "Emergency Coordinator",
                description = "View and manage received reports",
                icon = SethuIcons.CoordinatorTeam,
                isSelected = selectedRole == DeviceRole.COORDINATOR,
                onClick = {
                    onRoleSelected(DeviceRole.COORDINATOR)
                    onNavigateToRole(DeviceRole.COORDINATOR)
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Glowing Red Central SOS Button
            GlowingSosButton(
                onClick = onSosClick
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
