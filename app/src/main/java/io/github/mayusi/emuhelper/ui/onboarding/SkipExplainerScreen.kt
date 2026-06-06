package io.github.mayusi.emuhelper.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mayusi.emuhelper.ui.common.Dimens

@Composable
fun SkipExplainerScreen(
    onSignInInstead: () -> Unit,
    onSkipAnyway: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Heads up — downloads need a login",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Internet Archive only allows ROM downloads when you're signed in. Making an account is free and takes a minute at archive.org. You can browse and build lists without an account, but downloads will fail until you sign in. If you skip now, you can sign in anytime later using the ⋮ menu in the top-right corner of the home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onSignInInstead,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.ButtonMinHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Sign in", style = MaterialTheme.typography.titleMedium)
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onSkipAnyway,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.ButtonMinHeight),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Skip anyway", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
