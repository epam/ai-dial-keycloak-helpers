package com.epam.aidial.keycloak.helpers.provider;

import com.epam.aidial.keycloak.helpers.exception.GraphApiException;
import com.epam.aidial.keycloak.helpers.model.IdpType;
import com.epam.aidial.keycloak.helpers.model.UserAttributes;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MsGraphUserAttributesProviderTest {

    private static final ArgumentMatcher<HttpRequest> PROFILE_REQUEST =
            req -> req != null && req.uri().getPath().equals("/v1.0/me");
    private static final ArgumentMatcher<HttpRequest> PHOTO_REQUEST =
            req -> req != null && req.uri().getPath().startsWith("/v1.0/me/photos");

    private HttpClient httpClient;
    private MsGraphUserAttributesProvider provider;

    @Before
    public void setUp() {
        httpClient = mock(HttpClient.class);
        provider = new MsGraphUserAttributesProvider(httpClient);
    }

    @Test
    public void getIdpTypeReturnsMicrosoft() {
        assertEquals(IdpType.MICROSOFT, provider.getIdpType());
    }

    @Test
    public void getUserAttributesReturnsProfileData() throws Exception {
        String json = "{\"displayName\":\"John Doe\",\"jobTitle\":\"Engineer\"}";
        HttpResponse<InputStream> profileResponse = mockResponse(200, json);
        doReturn(profileResponse).when(httpClient).send(argThat(PROFILE_REQUEST), any());

        UserAttributes attrs = provider.getUserAttributes("test-token");

        assertEquals("John Doe", attrs.getName());
        assertEquals("Engineer", attrs.getJobTitle());
    }

    @Test
    public void getUserAttributesHandlesNullFields() throws Exception {
        String json = "{\"displayName\":null,\"jobTitle\":null}";
        HttpResponse<InputStream> profileResponse = mockResponse(200, json);
        doReturn(profileResponse).when(httpClient).send(argThat(PROFILE_REQUEST), any());

        UserAttributes attrs = provider.getUserAttributes("test-token");

        assertNull(attrs.getName());
        assertNull(attrs.getJobTitle());
    }

    @Test(expected = GraphApiException.class)
    public void getUserAttributesThrowsOnHttpError() throws Exception {
        HttpResponse<InputStream> response = mockResponse(401, "");
        doReturn(response).when(httpClient).send(argThat(PROFILE_REQUEST), any());

        provider.getUserAttributes("bad-token");
    }

    @Test(expected = GraphApiException.class)
    public void getUserAttributesThrowsOnIoException() throws Exception {
        doThrow(new IOException("connection refused")).when(httpClient).send(argThat(PROFILE_REQUEST), any());

        provider.getUserAttributes("test-token");
    }

    @Test
    public void getUserAttributesThrowsOnInterruptedExceptionAndRestoresFlag() throws Exception {
        doThrow(new InterruptedException("interrupted")).when(httpClient).send(argThat(PROFILE_REQUEST), any());

        try {
            provider.getUserAttributes("test-token");
        } catch (GraphApiException e) {
            assertTrue("Interrupt flag should be restored", Thread.currentThread().isInterrupted());
            return;
        } finally {
            // Clear the interrupt flag to avoid affecting other tests
            Thread.interrupted();
        }
        throw new AssertionError("Expected GraphApiException to be thrown");
    }

    @Test
    public void fetchPhotoAsBase64ReturnsDataUri() throws Exception {
        byte[] imageBytes = new byte[]{1, 2, 3, 4};
        HttpResponse<InputStream> response = mockResponse(200, imageBytes, "image/png");
        doReturn(response).when(httpClient).send(argThat(PHOTO_REQUEST), any());

        String result = provider.fetchPhotoAsBase64("test-token");

        String expectedBase64 = Base64.getEncoder().encodeToString(imageBytes);
        assertEquals("data:image/png;base64," + expectedBase64, result);
    }

    @Test
    public void fetchPhotoAsBase64DefaultsMimeType() throws Exception {
        byte[] imageBytes = new byte[]{1, 2, 3};
        HttpResponse<InputStream> response = mockResponse(200, imageBytes, null);
        doReturn(response).when(httpClient).send(argThat(PHOTO_REQUEST), any());

        String result = provider.fetchPhotoAsBase64("test-token");

        String expectedBase64 = Base64.getEncoder().encodeToString(imageBytes);
        assertEquals("data:image/jpeg;base64," + expectedBase64, result);
    }

    @Test
    public void fetchPhotoAsBase64ReturnsNullOn404() throws Exception {
        HttpResponse<InputStream> response = mockResponse(404, "");
        doReturn(response).when(httpClient).send(argThat(PHOTO_REQUEST), any());

        assertNull(provider.fetchPhotoAsBase64("test-token"));
    }

    @Test
    public void fetchPhotoAsBase64ReturnsNullOnServerError() throws Exception {
        HttpResponse<InputStream> response = mockResponse(500, "");
        doReturn(response).when(httpClient).send(argThat(PHOTO_REQUEST), any());

        assertNull(provider.fetchPhotoAsBase64("test-token"));
    }

    @Test
    public void fetchPhotoAsBase64ReturnsNullOnIoException() throws Exception {
        doThrow(new IOException("timeout")).when(httpClient).send(argThat(PHOTO_REQUEST), any());

        assertNull(provider.fetchPhotoAsBase64("test-token"));
    }

    @Test
    public void fetchPhotoAsBase64ReturnsNullOnInterruptedExceptionAndRestoresFlag() throws Exception {
        doThrow(new InterruptedException("interrupted")).when(httpClient).send(argThat(PHOTO_REQUEST), any());

        try {
            assertNull(provider.fetchPhotoAsBase64("test-token"));
            assertTrue("Interrupt flag should be restored", Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> mockResponse(int statusCode, String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> mockResponse(int statusCode, byte[] body, String contentType) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        HttpHeaders headers;
        if (contentType != null) {
            headers = HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (k, v) -> true);
        } else {
            headers = HttpHeaders.of(Map.of(), (k, v) -> true);
        }
        when(response.headers()).thenReturn(headers);
        return response;
    }
}