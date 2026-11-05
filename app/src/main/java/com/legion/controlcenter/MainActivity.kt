package com.legion.controlcenter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRuntimePermissions()

        setContent {
            MaterialTheme {
                SetupDashboardScreen()
            }
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    @Composable
    private fun SetupDashboardScreen() {
        val prefs = remember { getSharedPreferences("legion_prefs", Context.MODE_PRIVATE) }
        var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0205))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.raven_avatar),
                contentDescription = "Raven Avatar",
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color(0xFFFF1744), CircleShape)
            )

            Text(
                "LEGION CONTROL CENTER & RAVEN AI",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp
            )
            Text(
                "Lenovo Legion Y700 Edition • Gemini 3.5 Flash-Lite",
                color = Color(0xFFFF5252),
                fontSize = 12.sp
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    prefs.edit().putString("gemini_api_key", it.trim()).apply()
                },
                label = { Text("Gemini API Key (لتشغيل ذكاء Raven)", color = Color(0xFFFF8A80)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFF1744),
                    unfocusedBorderColor = Color(0x66FF5252)
                )
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (!Settings.canDrawOverlays(this@MainActivity)) {
                            Toast.makeText(this@MainActivity, "فعّل صلاحية الظهور فوق التطبيقات أولاً", Toast.LENGTH_LONG).show()
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        } else {
                            val intent = Intent(this@MainActivity, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_SHOW_PANEL
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                            else startService(intent)
                            finish()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text("فتح اللوحة الآن", fontWeight = FontWeight.Bold, color = Color.White)
                }

                Button(
                    onClick = {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            val intent = Intent(this@MainActivity, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_OPEN_RAVEN
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                            else startService(intent)
                            finish()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF880E4F)),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text("التحدث مع Raven 🎙️", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            PermissionCard(
                title = "1. الظهور فوق التطبيقات (Overlay Permission)",
                desc = "ضروري لظهور اللوحة والشخصية ومراقب الفريمات فوق الألعاب.",
                onClick = {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            )

            PermissionCard(
                title = "2. تعديل إعدادات النظام (Write System Settings)",
                desc = "ضروري لتحكم السلايدر في السطوع، الدوران التلقائي، ومعدل التحديث 144Hz.",
                onClick = {
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                }
            )

            PermissionCard(
                title = "3. خدمة إمكانية الوصول (Accessibility Service)",
                desc = "ضروري لأزرار لقطة الشاشة، تقسيم الشاشة، والـ Macros. (لو رمادية في أندرويد 13+ افتح App Info وفعل Allow restricted settings).",
                onClick = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )

            PermissionCard(
                title = "4. قراءة الميديا والإشعارات (Notification Listener)",
                desc = "ضروري لعرض اسم الأغنية وصورة الألبوم والتحكم في المشغل.",
                onClick = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            )

            PermissionCard(
                title = "5. صلاحية المايكروفون + App Info (Restricted Settings)",
                desc = "اضغط هنا لفتح معلومات التطبيق وتفعيل Allow restricted settings والمايكروفون.",
                onClick = {
                    requestRuntimePermissions()
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            )
        }
    }

    @Composable
    private fun PermissionCard(title: String, desc: String, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1A050A))
                .border(1.dp, Color(0xFFFF1744), RoundedCornerShape(16.dp))
                .clickable { onClick() }
                .padding(14.dp)
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(desc, color = Color.LightGray, fontSize = 11.5.sp)
        }
    }
}
