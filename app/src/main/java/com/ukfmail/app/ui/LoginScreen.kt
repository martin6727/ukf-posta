package com.ukfmail.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ukfmail.app.mail.BadLoginException
import java.io.IOException
import kotlinx.coroutines.launch

/** [signIn] checks the login against the server and throws [BadLoginException] or IOException. */
@Composable
fun LoginScreen(message: String?, signIn: suspend (user: String, password: String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(message) }
    val scope = rememberCoroutineScope()
    val dark = isDark()

    fun submit() {
        if (busy || user.isBlank() || pass.isEmpty()) return
        busy = true; error = null
        scope.launch {
            try {
                signIn(user.trim(), pass)
            } catch (e: BadLoginException) {
                error = "Nesprávne meno alebo heslo."
            } catch (e: IOException) {
                error = e.message ?: "Nedá sa spojiť so serverom."
            } catch (e: Exception) {
                error = "Niečo sa pokazilo: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AnimatedGlow(Modifier.fillMaxSize(), strength = 1.5f)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            AppMark(92.dp)
            Spacer(Modifier.height(28.dp))
            Text(
                "UKF Pošta",
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
                style = TextStyle(brush = Brush.linearGradient(listOf(Indigo, Violet, Pink))),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Študentský e-mail UKF v Nitre,\nkonečne pohodlne v mobile.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(36.dp))

            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.55f else 0.72f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = if (dark) 0.08f else 0.7f)),
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = user, onValueChange = { user = it.trim() },
                        label = { Text("Prihlasovacie meno") },
                        supportingText = { Text("ID študenta z AiS2 (je na preukaze pod fotkou)") },
                        leadingIcon = { Icon(Icons.Outlined.Badge, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it },
                        label = { Text("Heslo") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { showPass = !showPass }) {
                                Icon(
                                    if (showPass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    if (showPass) "Skryť heslo" else "Ukázať heslo",
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    GradientButton(
                        onClick = { submit() },
                        enabled = !busy && user.isNotBlank() && pass.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        else Text("Prihlásiť sa", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Rovnaké meno a heslo ako do AiS2 a ostatných systémov UKF. Heslo zostáva zašifrované len v tomto telefóne.",
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
