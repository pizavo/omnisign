package cz.pizavo.omnisign.web.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.pizavo.omnisign.api.model.responses.LoginOptionsResponse
import cz.pizavo.omnisign.lumo.LumoTheme
import cz.pizavo.omnisign.lumo.components.Button
import cz.pizavo.omnisign.lumo.components.Text
import cz.pizavo.omnisign.lumo.components.progressindicators.CircularProgressIndicator
import cz.pizavo.omnisign.ui.platform.loadUiPreferences
import kotlinx.coroutines.launch
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.login_failed
import omnisign.composeapp.generated.resources.login_heading
import omnisign.composeapp.generated.resources.login_no_providers
import omnisign.composeapp.generated.resources.login_prompt
import org.jetbrains.compose.resources.stringResource

/**
 * The full-screen sign-in gate shown, before the app renders, whenever the server requires
 * authentication and no session could be established at boot.
 *
 * Lists the server's OIDC providers (fetched once on composition) as buttons; clicking one starts
 * the PKCE hand-off via [startLogin], which navigates away to the identity provider. Header-injection
 * providers are omitted — that flow is driven by a reverse proxy, not a browser click, so it has no
 * button to offer here. While the provider list loads a spinner shows; if the server exposes no
 * interactive provider, that is said plainly rather than leaving an empty panel.
 *
 * Owns its own theme resolution (persisted preference, else the system setting) because it renders
 * outside [cz.pizavo.omnisign.App], which is where the in-app theme state normally lives.
 *
 * @param authApi Auth API used to list providers and, via [startLogin], to begin a login.
 * @param serverBaseUrl Base URL of the server (empty for same-origin), used to build the redirect.
 * @param organizationName Deploy-time branding label shown above the heading, or a plain product
 *   name when unset.
 * @param loginFailed Whether this load followed a hand-off code that could not be redeemed, in which
 *   case a short explanation is shown above the providers.
 */
@Composable
fun LoginScreen(
    authApi: WebAuthApi,
    serverBaseUrl: String,
    organizationName: String?,
    loginFailed: Boolean,
) {
    val systemDark = isSystemInDarkTheme()
    val preferences = remember { loadUiPreferences() }
    val isDark = preferences.isDark ?: systemDark

    LumoTheme(isDarkTheme = isDark) {
        val scope = rememberCoroutineScope()
        var providers by remember { mutableStateOf<List<LoginOptionsResponse.ProviderInfo>?>(null) }
        LaunchedEffect(Unit) {
            providers = authApi.loginOptions().filter { it.type == OIDC_PROVIDER_TYPE }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(LumoTheme.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = LOGIN_CARD_MAX_WIDTH_DP.dp).padding(CARD_PADDING_DP.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP.dp),
            ) {
                Text(
                    text = organizationName ?: DEFAULT_BRAND,
                    color = LumoTheme.colors.textSecondary,
                    style = LumoTheme.typography.label1,
                )
                Text(
                    text = stringResource(Res.string.login_heading),
                    color = LumoTheme.colors.text,
                    style = LumoTheme.typography.h2,
                )
                Text(
                    text = stringResource(Res.string.login_prompt),
                    color = LumoTheme.colors.textSecondary,
                    style = LumoTheme.typography.body2,
                    textAlign = TextAlign.Center,
                )
                if (loginFailed) {
                    Text(
                        text = stringResource(Res.string.login_failed),
                        color = LumoTheme.colors.error,
                        style = LumoTheme.typography.body3,
                        textAlign = TextAlign.Center,
                    )
                }

                when (val current = providers) {
                    null -> CircularProgressIndicator(modifier = Modifier.size(SPINNER_SIZE_DP.dp))
                    else -> if (current.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.login_no_providers),
                            color = LumoTheme.colors.textSecondary,
                            style = LumoTheme.typography.body3,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        current.forEach { provider ->
                            Button(
                                text = provider.displayName,
                                onClick = { scope.launch { startLogin(serverBaseUrl, provider) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `type` value in [LoginOptionsResponse.ProviderInfo] for the OIDC providers this screen drives. */
private const val OIDC_PROVIDER_TYPE = "oidc"

/** Product name shown when the deployment set no [organizationName][LoginScreen] branding. */
private const val DEFAULT_BRAND = "OmniSign"

private const val LOGIN_CARD_MAX_WIDTH_DP = 360
private const val CARD_PADDING_DP = 24
private const val ITEM_SPACING_DP = 16
private const val SPINNER_SIZE_DP = 32
