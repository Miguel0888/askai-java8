package com.aresstack.askai.research.search.config;

import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;
import com.aresstack.askai.research.search.security.SecretArrays;

/**
 * One EDIT DRAFT of the DataForSEO provider settings, held for the lifetime of an open settings dialog: the
 * dialog loads it ONCE from the {@link ProviderConfigurationService}, the UI mutates the wrapped
 * {@link DataForSeoSearchConfiguration} directly (so switching provider cards never reloads or discards it),
 * and {@link #save} persists it through the SAME service on Save. Cancel simply drops the draft.
 *
 * <p>Password handling follows the spec: the stored secret is NEVER decrypted into the UI. The draft only
 * knows whether a credential EXISTS; a new password is applied only when the user actually typed one, and
 * is encrypted through the existing {@link SecretValueService} on save — otherwise the stored secret is
 * kept untouched.</p>
 */
public final class DataForSeoSettingsDraft {

    private final ProviderConfigurationService service;
    private final DataForSeoSearchConfiguration configuration;
    private final boolean hadStoredPassword;
    private char[] newPassword; // null → keep the stored secret; non-null → encrypt + replace on save

    private DataForSeoSettingsDraft(ProviderConfigurationService service,
                                    DataForSeoSearchConfiguration configuration) {
        this.service = service;
        this.configuration = configuration;
        this.hadStoredPassword = configuration.getPassword() != null;
    }

    /** Load the draft once from the persisted provider file (creating defaults if none exists). */
    public static DataForSeoSettingsDraft load(ProviderConfigurationService service) {
        return new DataForSeoSettingsDraft(service, service.loadOrCreateDataForSeo());
    }

    /** The live working copy the UI edits directly (depth, engine, location, advanced fields, …). */
    public DataForSeoSearchConfiguration configuration() {
        return configuration;
    }

    /** True when a credential is already stored — the UI shows "Saved credential", never the secret. */
    public boolean hasStoredPassword() {
        return hadStoredPassword || newPassword != null;
    }

    /** Record a newly typed password (kept only in memory until save); empty/null clears the pending one. */
    public void setNewPassword(char[] password) {
        if (newPassword != null) {
            SecretArrays.clear(newPassword);
        }
        newPassword = password == null || password.length == 0 ? null : password.clone();
    }

    public boolean hasPendingPassword() {
        return newPassword != null;
    }

    /**
     * Persist through the provider service: a pending password is encrypted and replaces the stored secret;
     * without one the stored secret is preserved. Validation happens inside the service (depth 1..200, …).
     */
    public void save() {
        if (newPassword != null) {
            service.saveDataForSeo(configuration, newPassword.clone()); // service clears its copy
        } else {
            service.saveDataForSeo(configuration); // keeps the existing encrypted secret untouched
        }
    }

    /** Wipe the in-memory password copy (call on dialog close/cancel). */
    public void dispose() {
        if (newPassword != null) {
            SecretArrays.clear(newPassword);
            newPassword = null;
        }
    }
}
