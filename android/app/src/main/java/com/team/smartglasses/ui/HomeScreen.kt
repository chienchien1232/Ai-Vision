package com.team.smartglasses.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Man hinh chinh. Nguoi 3. */
@Composable
fun HomeScreen(
    onDescribe: () -> Unit,
    onTakePhoto: () -> Unit
) {
    Column {
        Text("Ai-Vision Smart Glasses")
        Button(onClick = onDescribe) { Text("Mô tả cảnh") }
        Button(onClick = onTakePhoto) { Text("Chụp ảnh") }
    }
}
