package cz.pizavo.omnisign.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.core.flatMap
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.ProfileConfig
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.config.enums.SignatureLevel
import cz.pizavo.omnisign.domain.model.error.localizableText
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.usecase.GetConfigUseCase
import cz.pizavo.omnisign.domain.usecase.ManageProfileUseCase
import cz.pizavo.omnisign.ui.model.PendingTrustedCert
import cz.pizavo.omnisign.ui.model.ProfileEditState
import cz.pizavo.omnisign.ui.model.ProfileError
import cz.pizavo.omnisign.ui.model.ProfileListState
import cz.pizavo.omnisign.ui.model.ProfilePanelMode
import cz.pizavo.omnisign.ui.model.TrustedCertAddError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel managing the profile list panel state.
 *
 * Loads profiles via [ManageProfileUseCase], tracks the currently active profile,
 * and exposes actions for selecting, deselecting, deleting, creating, and editing profiles.
 *
 * @param manageProfileUseCase Use case for CRUD operations on configuration profiles.
 * @param getConfigUseCase Use-case for reading the current application configuration.
 * @param credentialStore Optional OS credential store for persisting TSA passwords.
 * @param trustStore Trust store backing the profile's Trusted Certificates section: the app-managed
 *   one on the desktop, the server's read-only one on the web. `null` only when none is bound, and
 *   the section then renders unavailable.
 * @param ioDispatcher Dispatcher for the credential store calls, which can block until the user
 *   answers the keyring's unlock prompt.
 */
class ProfileViewModel(
    private val manageProfileUseCase: ManageProfileUseCase,
    private val getConfigUseCase: GetConfigUseCase,
    private val credentialStore: CredentialStore? = null,
    private val trustStore: TrustStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileListState())

    private val _initialEditState = MutableStateFlow<ProfileEditState?>(null)

    /** Observable profile list state. */
    val state: StateFlow<ProfileListState> = _state.asStateFlow()

    /**
     * Whether the current profile edit state differs from the originally loaded state.
     *
     * Returns `false` when not in edit mode or when the current edit state
     * matches the initial snapshot (ignoring transient UI fields).
     */
    val hasEditChanges: StateFlow<Boolean> = _state.map { listState ->
        val current = listState.editState
        val initial = _initialEditState.value
        current != null && initial != null && !current.contentEquals(initial)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        refresh()
    }

    /**
     * Reload the profile list and active profile from the configuration store.
     */
    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /**
     * Reload the profile list and active profile from the configuration store, as [refresh] does, and
     * show [error] once the list is reloaded, in place of the error a reload clears.
     *
     * @param error An error to show beside the reloaded list, such as the one a change that preceded the
     *   reload returned, or `null` to show none.
     */
    private suspend fun reload(error: ProfileError? = null) {
        _state.update { it.copy(loading = true, error = null) }

        val appConfig = getConfigUseCase().fold(
            ifLeft = { null },
            ifRight = { it },
        )
        val activeProfile = appConfig?.activeProfile
        val globalDisabledHash = appConfig?.global?.disabledHashAlgorithms ?: emptySet()
        val globalDisabledEnc = appConfig?.global?.disabledEncryptionAlgorithms ?: emptySet()
        val globalLevel = appConfig?.global?.defaultSignatureLevel ?: SignatureLevel.PADES_BASELINE_B

        manageProfileUseCase.list().fold(
            ifLeft = { listError ->
                _state.update { it.copy(loading = false, error = ProfileError.Domain(listError.localizableText())) }
            },
            ifRight = { profiles ->
                _state.update {
                    it.copy(
                        profiles = profiles.values.toList(),
                        activeProfile = activeProfile,
                        loading = false,
                        error = error,
                        globalDisabledHashAlgorithms = globalDisabledHash,
                        globalDisabledEncryptionAlgorithms = globalDisabledEnc,
                        globalSignatureLevel = globalLevel,
                    )
                }
            },
        )
    }

    /**
     * Activate the profile with the given [name], making it the default for operations.
     * If [name] matches the already-active profile, it is deselected instead.
     *
     * @param name Profile name to select or deselect.
     */
    fun toggleActive(name: String) {
        viewModelScope.launch {
            val target = if (_state.value.activeProfile == name) null else name
            manageProfileUseCase.setActive(target).fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = ProfileError.Domain(error.localizableText())) }
                },
                ifRight = {
                    _state.update { it.copy(activeProfile = target) }
                },
            )
        }
    }

    /**
     * Deselect the currently active profile, clearing it to `null`.
     */
    fun deselectActive() {
        viewModelScope.launch {
            manageProfileUseCase.setActive(null).fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = ProfileError.Domain(error.localizableText())) }
                },
                ifRight = {
                    _state.update { it.copy(activeProfile = null) }
                },
            )
        }
    }

    /**
     * Delete the profile with the given [name] and reload the list, after a failed removal as well: a
     * removal fails after the profile is gone when its trusted certificates cannot be dropped, and the
     * list then shows it gone beside the error.
     *
     * @param name Profile name to remove.
     */
    fun delete(name: String) {
        viewModelScope.launch {
            val error = manageProfileUseCase.remove(name).leftOrNull()
            reload(error?.let { ProfileError.Domain(it.localizableText()) })
        }
    }

    /**
     * Enter inline profile creation mode, showing the new-profile input row.
     */
    fun startCreate() {
        _state.update { it.copy(creatingNew = true, error = null) }
    }

    /**
     * Cancel inline profile creation, hiding the new-profile input row.
     */
    fun cancelCreate() {
        _state.update { it.copy(creatingNew = false, error = null) }
    }

    /**
     * Confirm creation of a new profile with the given [name].
     *
     * The name must not be blank. On success the creation row is hidden and the
     * profile list is refreshed.
     *
     * @param name The name for the new profile.
     */
    fun confirmCreate(name: String) {
        if (name.isBlank()) {
            _state.update { it.copy(error = ProfileError.NameRequired) }
            return
        }
        viewModelScope.launch {
            val profile = ProfileConfig(name = name.trim())
            manageProfileUseCase.upsert(profile).fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = ProfileError.Domain(error.localizableText())) }
                },
                ifRight = {
                    _state.update { it.copy(creatingNew = false) }
                    refresh()
                },
            )
        }
    }

    /**
     * Enter profile editing mode for the profile with the given [name].
     *
     * Loads the profile from the config store and switches the panel to
     * [ProfilePanelMode.Editing] with a pre-populated [ProfileEditState].
     *
     * @param name Profile name to edit.
     */
    fun startEdit(name: String) {
        viewModelScope.launch {
            manageProfileUseCase.get(name).fold(
                ifLeft = { error ->
                    _state.update { it.copy(error = ProfileError.Domain(error.localizableText())) }
                },
                ifRight = { profile ->
                    val hasStored = withContext(ioDispatcher) { hasStoredTsaPassword(profile) }
                    val trustedCerts = trustStore?.list(TrustScope.Profile(name))
                        ?.fold(ifLeft = { emptyList() }, ifRight = { it }).orEmpty()
                    val editState = ProfileEditState.from(profile, hasStored, trustedCertificates = trustedCerts)
                        .copy(trustedCertsAvailable = trustStore != null)
                    _initialEditState.value = editState
                    _state.update {
                        it.copy(
                            mode = ProfilePanelMode.Editing(name),
                            editState = editState,
                            error = null,
                        )
                    }
                },
            )
        }
    }

    /**
     * Cancel editing and return to the profile list view.
     */
    fun cancelEdit() {
        _initialEditState.value = null
        _state.update {
            it.copy(
                mode = ProfilePanelMode.Listing,
                editState = null,
                error = null,
            )
        }
    }

    /**
     * Apply a field-level transformation to the current [ProfileEditState].
     *
     * @param transform Function that receives the current edit state and returns the updated one.
     */
    fun updateEditState(transform: (ProfileEditState) -> ProfileEditState) {
        _state.update { current ->
            val editState = current.editState ?: return@update current
            current.copy(editState = transform(editState))
        }
    }

    /**
     * Parse and stage a trusted-certificate addition for the profile being edited (committed on [saveEdit]).
     *
     * The certificate is parsed via [cz.pizavo.omnisign.domain.repository.TrustStore.inspect] so the
     * staged row can show its subject and expiry. If its fingerprint is already trusted in this
     * profile (in the baseline minus pending removals, or already staged), nothing is staged and an
     * "already trusted" message is surfaced via [ProfileEditState.trustedCertAddError]. A parse
     * failure surfaces the same way. A no-op when not editing or when no trust store backend is present.
     *
     * @param bytes Raw certificate file content (PEM or DER).
     * @param type Trust role to grant when applied.
     * @param source Path the certificate was read from, recorded as provenance on save.
     */
    fun stageEditedProfileTrustedCert(bytes: ByteArray, type: TrustedCertificateType, source: String) {
        val store = trustStore ?: return
        viewModelScope.launch {
            store.inspect(bytes).fold(
                ifLeft = { error -> updateEditState { it.copy(trustedCertAddError = TrustedCertAddError.Domain(error.localizableText())) } },
                ifRight = { parsed ->
                    _state.update { current ->
                        val editState = current.editState ?: return@update current
                        val trustedFingerprints = editState.trustedCertificates
                            .filter { it.fingerprint !in editState.pendingTrustedCertRemovals }
                            .map { it.fingerprint } +
                            editState.pendingTrustedCertAdds.map { it.fingerprint }
                        val updated = if (parsed.fingerprint in trustedFingerprints) {
                            editState.copy(trustedCertAddError = TrustedCertAddError.AlreadyTrustedInProfile)
                        } else {
                            editState.copy(
                                pendingTrustedCertAdds = editState.pendingTrustedCertAdds + PendingTrustedCert(
                                    source = source,
                                    type = type,
                                    bytes = bytes,
                                    fingerprint = parsed.fingerprint,
                                    subjectDN = parsed.subjectDN,
                                    notAfter = parsed.notAfter,
                                ),
                                trustedCertAddError = null,
                            )
                        }
                        current.copy(editState = updated)
                    }
                },
            )
        }
    }

    /**
     * Persist the current edit state as a profile and return to the listing view.
     *
     * Stores a newly entered TSA password in the OS credential store first, which can wait for the
     * keyring to be unlocked. The configuration is then read again, so that what Settings, an import
     * or the CLI changed while the form was open, that wait included, is kept: the form is copied
     * onto the profile as it is stored now, or as it was loaded when it is no longer stored, with a
     * timestamp toggle left on Inherit resolved against the global level as it is now (see
     * [ProfileEditState.toProfileConfig]). The result is saved through [ManageProfileUseCase.upsert]
     * and the profile list is refreshed. A password the store does not keep becomes the error and
     * nothing is saved, so the profile never names a password that is not there, and a failed read
     * becomes the error too. An error from the save or from the trust store is kept as localizable
     * text, so the panel shows it in the UI's language.
     */
    fun saveEdit() {
        val editState = _state.value.editState ?: return
        _state.update { it.copy(editState = editState.copy(saving = true, error = null)) }
        viewModelScope.launch {
            withContext(ioDispatcher) { storeTsaPasswordIfNeeded(editState) }
                .flatMap { getConfigUseCase() }
                .flatMap { config ->
                    val stored = config.profiles[editState.profileName] ?: editState.loadedProfile
                    manageProfileUseCase.upsert(editState.toProfileConfig(config.global.defaultSignatureLevel, stored))
                }
                .fold(
                    ifLeft = { error ->
                        _state.update {
                            it.copy(
                                editState = editState.copy(
                                    saving = false,
                                    error = ProfileError.Domain(error.localizableText()),
                                ),
                            )
                        }
                    },
                    ifRight = {
                        val store = trustStore
                        val certError = if (store != null) {
                            applyStagedTrustedCertChanges(
                                store = store,
                                scope = TrustScope.Profile(editState.profileName),
                                removals = editState.pendingTrustedCertRemovals,
                                additions = editState.pendingTrustedCertAdds,
                            )
                        } else {
                            null
                        }
                        if (certError != null) {
                            _state.update {
                                it.copy(
                                    editState = editState.copy(
                                        saving = false,
                                        error = ProfileError.Domain(certError.localizableText()),
                                    ),
                                )
                            }
                        } else {
                            _initialEditState.value = null
                            _state.update {
                                it.copy(
                                    mode = ProfilePanelMode.Listing,
                                    editState = null,
                                )
                            }
                            refresh()
                        }
                    },
                )
        }
    }

    /**
     * Check whether a TSA password is already persisted for the given [profile].
     *
     * Blocks while the keyring asks to be unlocked, so callers run it on [ioDispatcher].
     */
    private fun hasStoredTsaPassword(profile: ProfileConfig): Boolean {
        val key = profile.timestampServer?.credentialKey ?: return false
        return credentialStore?.getPassword(TSA_CREDENTIAL_SERVICE, key) != null
    }

    /**
     * Persist the TSA password from [editState] into the OS credential store
     * when a new password was entered and a username is present.
     *
     * Blocks while the keyring asks to be unlocked, so callers run it on [ioDispatcher].
     *
     * @return `Unit` when there was nothing to store or the store kept the password, otherwise the
     *   store's error.
     */
    private fun storeTsaPasswordIfNeeded(editState: ProfileEditState): OperationResult<Unit> {
        val password = editState.timestampPassword.value.ifEmpty { return Unit.right() }
        val username = editState.timestampUsername.ifBlank { return Unit.right() }
        return credentialStore?.setPassword(TSA_CREDENTIAL_SERVICE, username, password)
            ?: Unit.right()
    }

    companion object {
        private const val TSA_CREDENTIAL_SERVICE = "omnisign-tsa"
    }
}
