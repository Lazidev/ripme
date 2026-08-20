package com.rarchives.ripme.ripper.rippers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import org.json.JSONArray;
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

    @Test
    void popularUrlUsesKeywordAsAlbumFolder() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/popular/ahegotyan"));
        assertTrue(ripper.isPopularUrl(ripper.getURL()));
        assertEquals("ahegotyan", ripper.getGID(ripper.getURL()));
        assertEquals("ahegotyan", ripper.getPopularKeyword(ripper.getURL()));
    }

    @Test
    void popularUrlWithTrailingSlashAndQueryStillUsesKeyword() throws Exception {
        InstagramRipper ripper = new InstagramRipper(
                new URL("https://www.instagram.com/popular/ahegotyan/?hl=en"));
        assertEquals("ahegotyan", ripper.getGID(ripper.getURL()));
    }

    @Test
    void profileUrlIsNotTreatedAsPopularSearch() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/ahegotyan/"));
        assertTrue(!ripper.isPopularUrl(ripper.getURL()));
        assertEquals("ahegotyan", ripper.getGID(ripper.getURL()));
    }

    @Test
    void convertKeywordSearchToTimelineExtractsGridMediaAndPagination() throws Exception {
        JSONObject image = new JSONObject();
        image.put("media_type", 1);
        JSONObject imageVersions = new JSONObject();
        imageVersions.put("candidates", new JSONArray()
                .put(new JSONObject().put("url", "https://example.com/grid.jpg")));
        image.put("image_versions2", imageVersions);

        JSONObject carouselChild = new JSONObject();
        carouselChild.put("media_type", 1);
        JSONObject childVersions = new JSONObject();
        childVersions.put("candidates", new JSONArray()
                .put(new JSONObject().put("url", "https://example.com/slide.jpg")));
        carouselChild.put("image_versions2", childVersions);
        JSONObject carousel = new JSONObject();
        carousel.put("media_type", 8);
        carousel.put("carousel_media", new JSONArray().put(carouselChild));

        JSONObject grid = new JSONObject();
        grid.put("__typename", "XDTTopSerpMediaGridUnit");
        grid.put("items", new JSONArray().put(image).put(carousel));

        JSONObject serp = new JSONObject();
        serp.put("edges", new JSONArray()
                .put(new JSONObject().put("node", new JSONObject().put("__typename", "XDTTopSerpHeaderUnit")))
                .put(new JSONObject().put("node", grid)));
        JSONObject pageInfo = new JSONObject();
        pageInfo.put("has_next_page", true);
        pageInfo.put("end_cursor", "cursor123");
        serp.put("page_info", pageInfo);

        JSONObject json = new JSONObject();
        json.put("data", new JSONObject().put("xdt_fbsearch__top_serp_graphql", serp));

        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/popular/ahegotyan"));
        JSONObject timeline = ripper.convertKeywordSearchToTimeline(json);
        List<String> urls = ripper.getURLsFromJSON(timeline);

        assertEquals(2, urls.size());
        assertEquals("https://example.com/grid.jpg", urls.get(0));
        assertEquals("https://example.com/slide.jpg", urls.get(1));
        JSONObject outPage = timeline.getJSONObject("data").getJSONObject("user")
                .getJSONObject("edge_owner_to_timeline_media").getJSONObject("page_info");
        assertTrue(outPage.getBoolean("has_next_page"));
        assertEquals("cursor123", outPage.getString("end_cursor"));
    }
}
