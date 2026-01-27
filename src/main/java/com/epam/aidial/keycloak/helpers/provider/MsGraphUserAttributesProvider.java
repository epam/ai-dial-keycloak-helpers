package com.epam.aidial.keycloak.helpers.provider;

import com.epam.aidial.keycloak.helpers.model.IdpType;
import com.epam.aidial.keycloak.helpers.model.UserAttributes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

/**
 * Service for interacting with Microsoft Graph API.
 * Used by Keycloak mappers to fetch user profile data.
 */
@Slf4j
public class MsGraphUserAttributesProvider implements UserAttributesProvider {
    private static final String GRAPH_API_BASE = "https://graph.microsoft.com/v1.0";
    private static final String USER_PROFILE_URL = "%s/me?$select=jobTitle,displayName";
    private static final String ME_PHOTO_URL = "%s/me/photos/48x48/$value";
    private static final String DATA_URI_FORMAT = "data:%s;base64,%s";
    private static final String DEFAULT_PHOTO_MIME_TYPE = "image/jpeg";

    private final ObjectMapper mapper = new ObjectMapper();

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
            throw new RuntimeException("Failed to fetch user attributes from Microsoft Graph", e);
        }
    }

    private HttpURLConnection createGraphConnection(String urlString, String accessToken) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        return conn;
    }

    private JsonNode fetchUserProfile(String accessToken) {
        String url = String.format(USER_PROFILE_URL, GRAPH_API_BASE);

        try {
            HttpURLConnection conn = createGraphConnection(url, accessToken);
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                log.debug("Successfully called Microsoft Graph API");
                return mapper.readTree(conn.getInputStream());
            }

            log.error("Microsoft Graph API returned HTTP {}", responseCode);
            throw new RuntimeException("Failed to fetch user profile: HTTP " + responseCode);

        } catch (IOException e) {
            log.error("Error calling Microsoft Graph API", e);
            throw new RuntimeException("Error fetching user profile from Microsoft Graph", e);
        }
    }

    @Override
    public String fetchPhotoAsBase64(String accessToken) {
        String url = String.format(ME_PHOTO_URL, GRAPH_API_BASE);
        log.debug("Fetching photo from: {}", url);

        try {
            HttpURLConnection conn = createGraphConnection(url, accessToken);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                return buildPhotoDataUri(conn);
            }

            if (responseCode == 404) {
                log.debug("No photo found for user");
            } else {
                log.warn("Failed to fetch photo: HTTP {}", responseCode);
            }
            return null;

        } catch (IOException e) {
            log.warn("Error fetching photo", e);
            return null;
        }
    }

    private String buildPhotoDataUri(HttpURLConnection conn) throws IOException {
        byte[] imageBytes = conn.getInputStream().readAllBytes();
        log.debug("Successfully fetched photo, size: {} bytes", imageBytes.length);

        String mimeType = conn.getContentType() != null ? conn.getContentType() : DEFAULT_PHOTO_MIME_TYPE;
        return String.format(DATA_URI_FORMAT, mimeType, Base64.getEncoder().encodeToString(imageBytes));
    }

    private String safeGetText(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }
}
