package me.danielstiner.dumble.ui.about

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.R

/** Resource ids live here, not in Attribution.kt, so the attribution data stays JVM-testable. */
@RawRes
private fun License.rawResId(): Int = when (this) {
    License.APACHE_2_0 -> R.raw.license_apache_2_0
    License.APACHE_2_0_LLVM -> R.raw.license_apache_2_0_llvm_exception
    License.BSD_3_CLAUSE -> R.raw.license_bsd_3_clause
    License.MIT -> R.raw.license_mit
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val resources = LocalContext.current.resources
    // ~14 KB of license text; read once rather than on every recomposition.
    val texts = remember {
        License.entries.associateWith { license ->
            resources.openRawResource(license.rawResId()).bufferedReader().use { it.readText() }
        }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("About") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Dumble $versionName", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Copyright The Dumble Authors. Licensed under the Apache License, Version 2.0.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Dumble is an independent client for Mumble servers and is not endorsed by or " +
                    "affiliated with the Mumble project.",
                style = MaterialTheme.typography.bodySmall,
            )

            License.entries.forEach { license ->
                Text(license.displayName, style = MaterialTheme.typography.titleMedium)
                attributionsFor(license).forEach { attribution ->
                    Text("• ${attribution.description}", style = MaterialTheme.typography.bodySmall)
                }
                Text(texts.getValue(license), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
