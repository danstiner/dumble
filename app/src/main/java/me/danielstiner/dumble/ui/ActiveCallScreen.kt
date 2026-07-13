package me.danielstiner.dumble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.ui.theme.DumbleTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    statusText: String,
    statsText: String,
    onHangUp: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Dumble") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text(statusText, style = MaterialTheme.typography.headlineSmall)
            Text(statsText, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onHangUp,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Hang Up") }
        }
    }
}

@Preview
@Composable
private fun ActiveCallScreenPreview() {
    DumbleTheme {
        ActiveCallScreen(
            statusText = "In Call",
            statsText = "state=Synchronized mode=UDP\nudpRtt=11.5ms jit=1.4ms",
            onHangUp = {},
        )
    }
}
