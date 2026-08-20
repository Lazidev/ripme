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

    @Test
    void convertKeywordSearchToTimelineUnwrapsNestedMediaAndRestShape() throws Exception {
        JSONObject image = new JSONObject();
        image.put("media_type", 1);
        image.put("pk", "111");
        image.put("code", "abc");
        JSONObject imageVersions = new JSONObject();
        imageVersions.put("candidates", new JSONArray()
                .put(new JSONObject().put("url", "https://example.com/wrapped.jpg")));
        image.put("image_versions2", imageVersions);

        JSONObject wrappedGrid = new JSONObject();
        wrappedGrid.put("__typename", "XDTTopSerpSomethingElse");
        wrappedGrid.put("items", new JSONArray().put(new JSONObject().put("media", image)));

        JSONObject restMedia = new JSONObject();
        restMedia.put("media_type", 1);
        restMedia.put("pk", "222");
        restMedia.put("code", "def");
        restMedia.put("image_versions2", new JSONObject().put("candidates", new JSONArray()
                .put(new JSONObject().put("url", "https://example.com/rest.jpg"))));

        JSONObject json = new JSONObject();
        JSONObject serp = new JSONObject();
        serp.put("edges", new JSONArray().put(new JSONObject().put("node", wrappedGrid)));
        json.put("data", new JSONObject().put("xdt_fbsearch__top_serp_graphql", serp));
        json.put("media_grid", new JSONObject().put("sections", new JSONArray()
                .put(new JSONObject().put("layout_content",
                        new JSONObject().put("fill_items", new JSONArray()
                                .put(new JSONObject().put("media", restMedia)))))));

        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/popular/testkw"));
        JSONObject timeline = ripper.convertKeywordSearchToTimeline(json);
        List<String> urls = ripper.getURLsFromJSON(timeline);
        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://example.com/wrapped.jpg"));
        assertTrue(urls.contains("https://example.com/rest.jpg"));
    }

    @Test
    void extractsGraphqlTokensFromPopularPageHtml() throws Exception {
        String html = "<html><script>["
                + "[\"DTSGInitialData\",[],{\"token\":\"NAf_testToken:123:456\"},258],"
                + "[\"LSD\",[],{\"token\":\"lsdTestToken\"},323],"
                + "[\"SiteData\",[],{\"client_revision\":1045582935},141]"
                + "],\"actorID\":\"17841400000000000\"</script></html>";
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/popular/testkw"));
        InstagramRipper.InstagramGraphqlTokens tokens = ripper.parseGraphqlTokens(html);
        assertEquals("lsdTestToken", tokens.lsd);
        assertEquals("NAf_testToken:123:456", tokens.fbDtsg);
        assertEquals("17841400000000000", tokens.actorId);
        assertEquals("1045582935", tokens.clientRevision);
    }

    @Test
    void extractsKeywordSearchDocIdAndQueryFromHtml() throws Exception {
        String html = "<script>{\"params\":{\"id\":\"26586987494245638\",\"name\":\"PolarisKeywordSearchExplorePageRelayQuery\"},"
                + "\"variables\":{\"query\":\"Little Slavic\",\"search_session_id\":\"abc\"}}</script>";
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/popular/testkw"));
        InstagramRipper.InstagramGraphqlTokens tokens = ripper.parseGraphqlTokens(html);
        assertEquals("26586987494245638", tokens.docId);
        assertEquals("Little Slavic", tokens.pageQuery);
    }

    @Test
    void keywordSearchVariablesIncludeFirstAndAfter() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/popular/testkw"));
        JSONObject first = ripper.buildKeywordSearchVariables("testkw", null);
        assertEquals("testkw", first.getString("query"));
        assertEquals(12, first.getInt("first"));
        assertTrue(first.isNull("after"));

        JSONObject next = ripper.buildKeywordSearchVariables("testkw", "cursor123");
        assertEquals("cursor123", next.getString("after"));
        assertEquals("cursor123", next.getString("cursor"));
    }

    @Test
    void keywordSearchFormIncludesLsdDtsgAndJazoest() throws Exception {
        InstagramRipper.InstagramGraphqlTokens tokens = new InstagramRipper.InstagramGraphqlTokens();
        tokens.lsd = "lsdTestToken";
        tokens.fbDtsg = "abc";
        tokens.actorId = "17841400000000000";
        tokens.clientRevision = "1045582935";

        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/popular/testkw"));
        java.util.Map<String, String> form = ripper.buildKeywordSearchForm(tokens, "{\"query\":\"testkw\"}");

        assertEquals("lsdTestToken", form.get("lsd"));
        assertEquals("abc", form.get("fb_dtsg"));
        assertEquals(InstagramRipper.computeJazoest("abc"), form.get("jazoest"));
        assertEquals("2" + (int) ('a' + 'b' + 'c'), form.get("jazoest"));
        assertEquals("17841400000000000", form.get("av"));
        assertEquals("0", form.get("__user"));
        assertEquals("7", form.get("__comet_req"));
        assertEquals("RelayModern", form.get("fb_api_caller_class"));
        assertEquals("PolarisKeywordSearchExplorePageRelayQuery", form.get("fb_api_req_friendly_name"));
        assertEquals("37324993597144881", form.get("doc_id"));
        assertEquals("1045582935", form.get("__rev"));
        assertEquals("{\"query\":\"testkw\"}", form.get("variables"));
    }

    @Test
    void htmlResponseIncludesBodySnippet() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/popular/testkw"));
        IOException ex = assertThrows(IOException.class,
                () -> ripper.parseInstagramJsonBody(
                        "<!DOCTYPE html><html><body>blocked</body></html>",
                        "keyword search for testkw"));
        assertTrue(ex.getMessage().contains("HTML instead of JSON"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Response starts:"), ex.getMessage());
    }

    @Test
    void parsesGraphqlBodyWithFacebookAntiHijackPrefix() throws Exception {
        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/testuser/"));
        JSONObject json = ripper.parseInstagramJsonBody(
                "for (;;);{\"data\":{\"ok\":true},\"status\":\"ok\"}", "unit test");
        assertEquals("ok", json.getString("status"));
        assertTrue(json.getJSONObject("data").getBoolean("ok"));
    }
}
