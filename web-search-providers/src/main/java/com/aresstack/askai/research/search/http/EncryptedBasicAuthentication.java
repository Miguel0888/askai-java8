package com.aresstack.askai.research.search.http;

import com.aresstack.askai.research.search.security.EncryptedSecret;
import com.aresstack.askai.research.search.security.SecretArrays;
import com.aresstack.askai.research.search.security.SecretValueService;
import org.asynchttpclient.BoundRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class EncryptedBasicAuthentication
        implements RequestAuthentication {

    private final String username;
    private final EncryptedSecret encryptedPassword;
    private final SecretValueService secretValueService;

    public EncryptedBasicAuthentication(
            String username,
            EncryptedSecret encryptedPassword,
            SecretValueService secretValueService) {

        this.username = requireText(
                username,
                "username");
        this.encryptedPassword =
                requireSecret(encryptedPassword);
        this.secretValueService = requireNonNull(
                secretValueService,
                "secretValueService");
    }

    @Override
    public void apply(BoundRequestBuilder requestBuilder) {
        if (requestBuilder == null) {
            throw new IllegalArgumentException(
                    "requestBuilder must not be null");
        }

        char[] password =
                secretValueService.decrypt(encryptedPassword);

        byte[] credentials = null;

        try {
            String value =
                    username + ":" + new String(password);

            credentials = value.getBytes(
                    StandardCharsets.UTF_8);

            requestBuilder.setHeader(
                    "Authorization",
                    "Basic " + Base64.getEncoder()
                            .encodeToString(credentials));
        } finally {
            SecretArrays.clear(password);
            SecretArrays.clear(credentials);
        }
    }

    private static EncryptedSecret requireSecret(
            EncryptedSecret encryptedSecret) {

        if (encryptedSecret == null
                || !encryptedSecret.isPresent()) {

            throw new IllegalArgumentException(
                    "encryptedPassword must be present");
        }
        return encryptedSecret;
    }

    private static String requireText(
            String value,
            String propertyName) {

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    propertyName + " must not be empty");
        }
        return value;
    }

    private static <T> T requireNonNull(
            T value,
            String propertyName) {

        if (value == null) {
            throw new IllegalArgumentException(
                    propertyName + " must not be null");
        }
        return value;
    }
}
