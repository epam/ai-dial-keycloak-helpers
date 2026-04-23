package com.epam.aidial.keycloak.helpers.provider;

import com.epam.aidial.keycloak.helpers.exception.GraphApiException;
import com.epam.aidial.keycloak.helpers.model.IdpType;
import com.epam.aidial.keycloak.helpers.model.UserAttributes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * Service for interacting with Microsoft Graph API.
 * Used by Keycloak mappers to fetch user profile data.
 */
@Slf4j
public class MsGraphUserAttributesProvider implements UserAttributesProvider {
    private static final String GRAPH_API_BASE = "https://graph.microsoft.com/v1.0";
    private static final String USER_PROFILE_URL = GRAPH_API_BASE + "/me?$select=jobTitle,displayName";
    private static final String ME_PHOTO_URL = GRAPH_API_BASE + "/me/photos/48x48/$value";
    private static final String DATA_URI_FORMAT = "data:%s;base64,%s";
    private static final String DEFAULT_PHOTO_MIME_TYPE = "image/jpeg";

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public MsGraphUserAttributesProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public IdpType getIdpType() {
        return IdpType.MICROSOFT;
    }

    @Override
    public UserAttributes getUserAttributes(String accessToken) {
        try {
            log.debug("Fetching user profile from Microsoft Graph API");
            JsonNode profile = fetchUserProfile(accessToken);

            UserAttributes userAttributes = UserAttributes.builder()
                    .name(safeGetText(profile, "displayName"))
                    .jobTitle(safeGetText(profile, "jobTitle"))
                    .build();

            log.debug("Successfully fetched user profile: name={}, jobTitle={}",
                    userAttributes.getName(), userAttributes.getJobTitle());

            return userAttributes;

        } catch (Exception e) {
            log.error("Failed to fetch user attributes from Microsoft Graph", e);
            throw new GraphApiException("Failed to fetch user attributes from Microsoft Graph", e);
        }
    }

    private JsonNode fetchUserProfile(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(USER_PROFILE_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            int statusCode = response.statusCode();
            if (statusCode == 200) {
                log.debug("Successfully called Microsoft Graph API");
                try (InputStream body = response.body()) {
                    return mapper.readTree(body);
                }
            }

            log.error("Microsoft Graph API returned HTTP {}", statusCode);
            throw new GraphApiException("Failed to fetch user profile: HTTP " + statusCode);

        } catch (IOException e) {
            log.error("Error calling Microsoft Graph API", e);
            throw new GraphApiException("Error fetching user profile from Microsoft Graph", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphApiException("Interrupted while fetching user profile from Microsoft Graph", e);
        }
    }

    @Override
    public String fetchPhotoAsBase64(String accessToken) {
        log.debug("Fetching photo from: {}", ME_PHOTO_URL);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ME_PHOTO_URL))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            int statusCode = response.statusCode();
            if (statusCode == 200) {
                return buildPhotoDataUri(response);
            }

            if (statusCode == 404) {
                log.debug("No photo found for user");
            } else {
                log.warn("Failed to fetch photo: HTTP {}", statusCode);
            }
            return null;

        } catch (IOException e) {
            log.warn("Error fetching photo", e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while fetching photo", e);
            return null;
        }
    }

    private String buildPhotoDataUri(HttpResponse<InputStream> response) throws IOException {
        try (InputStream body = response.body()) {
            byte[] imageBytes = body.readAllBytes();
            log.debug("Successfully fetched photo, size: {} bytes", imageBytes.length);

            String mimeType = response.headers().firstValue("Content-Type")
                    .orElse(DEFAULT_PHOTO_MIME_TYPE);
            return String.format(DATA_URI_FORMAT, mimeType, Base64.getEncoder().encodeToString(imageBytes));
        }
    }

    private String safeGetText(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }
}
