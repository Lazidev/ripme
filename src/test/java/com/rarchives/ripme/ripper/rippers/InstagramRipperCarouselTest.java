package com.rarchives.ripme.ripper.rippers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Verifies multi-image (carousel) posts from Instagram's private feed API
 * expand to every child URL, not just the cover image.
 */
public class InstagramRipperCarouselTest {

    @Test
    void extractsAllImagesFromCarouselFeedItem() throws Exception {
        String fixture;
        try (InputStream in = getClass().getResourceAsStream("/instagram_carousel_item.json")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        JSONObject carouselItem = new JSONObject(fixture);
        assertEquals(8, carouselItem.getInt("media_type"));
        assertEquals(3, carouselItem.getJSONArray("carousel_media").length());

        JSONObject feed = new JSONObject();
        feed.put("items", new JSONArray().put(carouselItem));
        feed.put("more_available", false);

        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/testuser/"));
        JSONObject timeline = ripper.convertFeedToTimeline(feed);
        List<String> urls = ripper.getURLsFromJSON(timeline);

        assertEquals(3, urls.size(), "Carousel posts should download every slide, not only the first");
        for (String url : urls) {
            assertTrue(url.startsWith("https://"), "Expected https media URL, got: " + url);
        }
    }

    @Test
    void stillExtractsSingleImagePosts() throws Exception {
        JSONObject imageItem = new JSONObject();
        imageItem.put("media_type", 1);
        JSONObject imageVersions = new JSONObject();
        JSONArray candidates = new JSONArray();
        candidates.put(new JSONObject().put("url", "https://example.com/single.jpg"));
        imageVersions.put("candidates", candidates);
        imageItem.put("image_versions2", imageVersions);

        JSONObject feed = new JSONObject();
        feed.put("items", new JSONArray().put(imageItem));
        feed.put("more_available", false);

        InstagramRipper ripper = new InstagramRipper(new URL("https://www.instagram.com/testuser/"));
        List<String> urls = ripper.getURLsFromJSON(ripper.convertFeedToTimeline(feed));

        assertEquals(1, urls.size());
        assertEquals("https://example.com/single.jpg", urls.get(0));
    }
}
