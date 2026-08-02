package com.rarchives.ripme.ripper.rippers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Offline tests for Instagram response parsing / login-wall detection.
 */
public class InstagramRipperParseTest {

    @Test
    void parsesValidJsonBody() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/testuser/"));
        JSONObject json = ripper.parseInstagramJsonBody(
                "{\"items\":[],\"status\":\"ok\"}", "unit test");
        assertEquals("ok", json.getString("status"));
    }

    @Test
    void detectsLoginRequiredJson() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/testuser/"));
        IOException ex = assertThrows(IOException.class,
                () -> ripper.parseInstagramJsonBody(
                        "{\"message\":\"login_required\",\"status\":\"fail\"}", "unit test"));
        assertTrue(ex.getMessage().toLowerCase().contains("firefox")
                || ex.getMessage().toLowerCase().contains("session"),
                ex.getMessage());
    }

    @Test
    void detectsHtmlLoginWallWithClearGuidance() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/testuser/"));
        String html = "<html><body>Please log in /accounts/login/?next=/testuser/</body></html>";
        IOException ex = assertThrows(IOException.class,
                () -> ripper.parseInstagramJsonBody(html, "feed API for testuser"));
        String message = ex.getMessage().toLowerCase();
        assertTrue(message.contains("html") || message.contains("login") || message.contains("session"),
                ex.getMessage());
        // Old misleading message must not return.
        assertTrue(!ex.getMessage().contains("couldn't extract JSON from HTML"), ex.getMessage());
    }

    @Test
    void extractsUserIdFromModernProfileHtml() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/testuser/"));
        String html = "<html><script>{\"profile_id\":\"25025320\",\"username\":\"testuser\"}</script></html>";
        assertEquals("25025320", ripper.parseUserIdFromProfileHtml(html, "testuser"));
    }

    @Test
    void extractsUserIdFromProfilePageToken() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/natgeo/"));
        String html = "window.__initialData = {\"logging_page_id\":\"profilePage_787132\"};";
        assertEquals("787132", ripper.parseUserIdFromProfileHtml(html, "natgeo"));
    }

    @Test
    void normalizeGraphqlTimelineThrowsOnNullUser() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/testuser/"));
        JSONObject json = new JSONObject("{\"data\":{\"user\":null}}");
        IOException ex = assertThrows(IOException.class,
                () -> ripper.normalizeGraphqlTimeline(json, false));
        String message = ex.getMessage().toLowerCase();
        assertTrue(message.contains("empty user data") || message.contains("sessionid"),
                ex.getMessage());
        assertTrue(message.contains("firefox") || message.contains("sessionid"),
                ex.getMessage());
    }

    @Test
    void normalizeGraphqlTimelinePassesThroughClassicTimeline() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/testuser/"));
        JSONObject json = new JSONObject(
                "{\"data\":{\"user\":{\"edge_owner_to_timeline_media\":{\"edges\":[],\"page_info\":{\"has_next_page\":false}}}}}");
        JSONObject normalized = ripper.normalizeGraphqlTimeline(json, false);
        assertTrue(normalized.getJSONObject("data").getJSONObject("user")
                .has("edge_owner_to_timeline_media"));
    }
}
