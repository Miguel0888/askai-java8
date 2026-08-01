package com.aresstack.askai.research.search.config;

import com.aresstack.askai.research.search.dataforseo.DataForSeoSearchConfiguration;
import com.aresstack.askai.research.search.security.AesGcmSecretCipher;
import com.aresstack.askai.research.search.security.FileSecretKeyProvider;
import com.aresstack.askai.research.search.security.SecretValueService;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The DataForSEO edit draft: it loads once, edits the working copy directly (no reload on provider
 * switch), preserves the stored secret unless a new password is typed, and persists through the real
 * provider service — with the corrected depth default (10, max 200).
 */
public class DataForSeoSettingsDraftTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private ProviderConfigurationService service() throws Exception {
        Path dir = folder.newFolder("providers").toPath();
        ProviderConfigurationPaths paths = new ProviderConfigurationPaths(dir);
        SecretValueService secrets = new SecretValueService(
                new AesGcmSecretCipher(new FileSecretKeyProvider(paths.getSecretKeyFile())));
        return new ProviderConfigurationService(paths, new GsonProviderConfigurationStore(), secrets);
    }

    @Test
    public void theCorrectedDefaultDepthIsTenNotHundred() throws Exception {
        DataForSeoSettingsDraft draft = DataForSeoSettingsDraft.load(service());
        assertEquals("Organic Live Advanced defaults to depth 10", 10,
                draft.configuration().getDepth());
    }

    @Test
    public void editsSurviveWithoutReloadAndPersistOnSave() throws Exception {
        ProviderConfigurationService service = service();
        DataForSeoSettingsDraft draft = DataForSeoSettingsDraft.load(service);
        draft.configuration().setUsername("me@example.com");
        draft.configuration().setDepth(30);
        draft.setNewPassword("s3cret".toCharArray());
        draft.save();

        DataForSeoSearchConfiguration reloaded = service.loadOrCreateDataForSeo();
        assertEquals("me@example.com", reloaded.getUsername());
        assertEquals(30, reloaded.getDepth());
        assertNotNull("the typed password was encrypted and stored", reloaded.getPassword());
    }

    @Test
    public void aStoredSecretIsPreservedWhenNoNewPasswordIsTyped() throws Exception {
        ProviderConfigurationService service = service();
        // First save WITH a password.
        DataForSeoSettingsDraft first = DataForSeoSettingsDraft.load(service);
        first.configuration().setUsername("me@example.com");
        first.setNewPassword("original".toCharArray());
        first.save();

        // Re-open: the draft knows a credential exists but never sees the secret; change only the depth.
        DataForSeoSettingsDraft second = DataForSeoSettingsDraft.load(service);
        assertTrue("the UI would show 'Saved credential'", second.hasStoredPassword());
        assertFalse(second.hasPendingPassword());
        second.configuration().setDepth(50);
        second.save();

        DataForSeoSearchConfiguration reloaded = service.loadOrCreateDataForSeo();
        assertNotNull("the original secret is still there", reloaded.getPassword());
        assertEquals(50, reloaded.getDepth());
    }

    @Test
    public void aFreshDraftHasNoStoredPassword() throws Exception {
        DataForSeoSettingsDraft draft = DataForSeoSettingsDraft.load(service());
        assertFalse(draft.hasStoredPassword());
        assertNull(draft.configuration().getPassword());
    }
}
