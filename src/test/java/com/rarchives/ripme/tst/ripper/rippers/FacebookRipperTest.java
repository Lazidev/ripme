package com.rarchives.ripme.tst.ripper.rippers;

import com.rarchives.ripme.ripper.rippers.FacebookRipper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FacebookRipperTest {

    @Test
    public void testPhotoTabUrlsShareCommonAlbumFolder() throws Exception {
        assertEquals("stephanie.bell.7121_photos",
                new TestableFacebookRipper(new URL("https://www.facebook.com/stephanie.bell.7121/photos_by")).getGID(
                        new URL("https://www.facebook.com/stephanie.bell.7121/photos_by")));
        assertEquals("stephanie.bell.7121_photos",
                new TestableFacebookRipper(new URL("https://www.facebook.com/stephanie.bell.7121/photos_of")).getGID(
                        new URL("https://www.facebook.com/stephanie.bell.7121/photos_of")));
        assertEquals("stephanie.bell.7121_photos",
                new TestableFacebookRipper(new URL("https://www.facebook.com/stephanie.bell.7121/photos")).getGID(
                        new URL("https://www.facebook.com/stephanie.bell.7121/photos")));
        assertEquals("profile.php_photos",
                new TestableFacebookRipper(new URL("https://www.facebook.com/profile.php?id=123&sk=photos")).getGID(
                        new URL("https://www.facebook.com/profile.php?id=123&sk=photos")));
    }

    @Test
    public void testNonPhotoUrlsKeepDistinctAlbumFolders() throws Exception {
        assertEquals("example_posts_1",
                new TestableFacebookRipper(new URL("https://www.facebook.com/example/posts/1")).getGID(
                        new URL("https://www.facebook.com/example/posts/1")));
        assertEquals("example",
                new TestableFacebookRipper(new URL("https://www.facebook.com/example")).getGID(
                        new URL("https://www.facebook.com/example")));
    }

    @Test
    public void testPhotoDiscoveryCountsDownloadsFromHistory(@TempDir Path tempDir) throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        String history = "["
                + "{\"url\":\"https://www.facebook.com/example/photos\",\"count\":111},"
                + "{\"url\":\"https://www.facebook.com/example/photos_by\",\"count\":86}"
                + "]";
        Files.writeString(configDir.resolve("history.json"), history, StandardCharsets.UTF_8);

        CountingFacebookRipper ripper = new CountingFacebookRipper(
                new URL("https://www.facebook.com/example/photos"), configDir);
        assertEquals(197, ripper.countExistingImageFiles(),
                "Discovery budget should sum history across all photo tabs for the profile");
    }

    private static class CountingFacebookRipper extends TestableFacebookRipper {
        private final Path configDir;

        CountingFacebookRipper(URL url, Path configDir) throws java.io.IOException {
            super(url);
            this.configDir = configDir;
        }

        @Override
        protected boolean isPhotoListingPage() {
            return true;
        }

        @Override
        protected int countProfilePhotosFromHistory() {
            Path historyFile = configDir.resolve("history.json");
            if (!Files.isRegularFile(historyFile)) {
                return 0;
            }
            String profileGid = getGID(this.url);
            int total = 0;
            try {
                org.json.JSONArray entries = new org.json.JSONArray(
                        Files.readString(historyFile, StandardCharsets.UTF_8));
                for (int i = 0; i < entries.length(); i++) {
                    org.json.JSONObject entry = entries.getJSONObject(i);
                    String historyUrl = entry.optString("url", "");
                    if (historyUrl.isBlank()) {
                        continue;
                    }
                    if (!profileGid.equals(getGID(new URL(historyUrl)))) {
                        continue;
                    }
                    total += entry.optInt("count", 0);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return total;
        }

        @Override
        protected int countExistingImageFiles() {
            return super.countExistingImageFiles();
        }
    }

    private static class TestableFacebookRipper extends FacebookRipper {
        TestableFacebookRipper(URL url) throws java.io.IOException {
            super(url);
        }

        List<String> extract(Document doc) throws java.io.UnsupportedEncodingException {
            return super.getURLsFromPage(doc);
        }

        // Keep tests hermetic by default: never touch the network unless a subclass supplies pages.
        @Override
        protected Document fetchPhotoPage(String fbid) {
            return null;
        }

        @Override
        protected String executeGraphqlQuery(String friendlyName, String lsd, Map<String, String> formData) {
            return null;
        }
    }

    @Test
    public void testExtractedMediaUrlsDecodeHtmlAmpersands() throws Exception {
        String html = "<html><head>"
                + "<meta property=\"og:image\" content=\"https://scontent.xx.fbcdn.net/file.jpg?foo=1&amp;bar=2\">"
                + "</head><body></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/example/posts/1");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/example/posts/1"));
        List<String> urls = ripper.extract(doc);

        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/file.jpg?foo=1&bar=2"));
    }

    @Test
    public void testExtractsImagesFromEscapedScriptJson() throws Exception {
        // Facebook stores media inside <script> JSON with escaped slashes/unicode; these must be found.
        String html = "<html><body><script type=\"application/json\">"
                + "{\"image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/photo1.jpg?oh=abc\\u0026oe=def\"}}"
                + "{\"image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/photo2.png?stp=xyz\"}}"
                + "</script></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/example/posts/1");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/example/posts/1"));
        List<String> urls = ripper.extract(doc);

        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/photo1.jpg?oh=abc&oe=def"));
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/photo2.png?stp=xyz"));
    }

    @Test
    public void testReelPrefersHdVideoSource() throws Exception {
        String html = "<html><body><script type=\"application/json\">"
                + "{\"playable_url\":\"https:\\/\\/video.xx.fbcdn.net\\/v\\/sd.mp4?efg=1\","
                + "\"playable_url_quality_hd\":\"https:\\/\\/video.xx.fbcdn.net\\/v\\/hd.mp4?abc=1\"}"
                + "</script></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/reel/123456789");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/reel/123456789"));
        List<String> urls = ripper.extract(doc);

        assertTrue(urls.contains("https://video.xx.fbcdn.net/v/hd.mp4?abc=1"));
        // HD must be ordered ahead of SD.
        assertTrue(urls.indexOf("https://video.xx.fbcdn.net/v/hd.mp4?abc=1")
                < urls.indexOf("https://video.xx.fbcdn.net/v/sd.mp4?efg=1"));
    }

    @Test
    public void testJunkUiAssetsAreFiltered() throws Exception {
        String html = "<html><head>"
                + "<meta property=\"og:image\" content=\"https://scontent.xx.fbcdn.net/real.jpg\">"
                + "</head><body><script>"
                + "\"https:\\/\\/static.xx.fbcdn.net\\/rsrc.php\\/icon.png\""
                + "</script></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/example/posts/1");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/example/posts/1"));
        List<String> urls = ripper.extract(doc);

        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/real.jpg"));
        assertTrue(urls.stream().noneMatch(u -> u.contains("rsrc.php")));
    }

    @Test
    public void testImageSizeVariantsCollapseToLargest() throws Exception {
        // Same photo served at several sizes must collapse to a single, largest variant.
        String base = "https://scontent.xx.fbcdn.net/v/t39.30808-1/123_456_n.jpg";
        String html = "<html><body><script>"
                + "\"" + base + "?stp=cp0_dst-jpg_s80x80_tt6&_nc_cat=1\""
                + "\"" + base + "?stp=dst-jpg_s480x480_tt6&_nc_cat=1\""
                + "\"" + base + "?stp=dst-jpg_s320x320_tt6&_nc_cat=1\""
                + "</script></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/example/posts/1");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/example/posts/1"));
        List<String> urls = ripper.extract(doc);

        assertEquals(1, urls.size(), "All size-variants of one photo should collapse to a single URL");
        assertTrue(urls.get(0).contains("s480x480"), "The largest signed CDN variant should be kept intact");
    }

    @Test
    public void testReelRenditionsCollapseToOnePerVideoId() throws Exception {
        // One reel exposed as several renditions plus an audio-only track must collapse to a
        // single file: the highest-resolution rendition.
        String hd = videoUrl("dash.mp4", "{\"vencode_tag\":\"dash_vp9-basic-gen2_1080p\",\"video_id\":42,\"bitrate\":2000000}");
        String sd = videoUrl("dash2.mp4", "{\"vencode_tag\":\"dash_vp9-basic-gen2_360p\",\"video_id\":42,\"bitrate\":300000}");
        String prog = videoUrl("prog.mp4", "{\"vencode_tag\":\"progressive_h264-basic-gen2_360p\",\"video_id\":42,\"bitrate\":400000}");
        String audio = videoUrl("audio.mp4", "{\"vencode_tag\":\"dash_ln_heaac_vbr3_audio\",\"video_id\":42,\"bitrate\":50000}");

        String html = "<html><body><script>"
                + "\"" + hd + "\"\"" + sd + "\"\"" + prog + "\"\"" + audio + "\""
                + "</script></body></html>";
        Document doc = Jsoup.parse(html, "https://www.facebook.com/reel/42");

        TestableFacebookRipper ripper = new TestableFacebookRipper(new URL("https://www.facebook.com/reel/42"));
        List<String> urls = ripper.extract(doc);

        assertEquals(1, urls.size(), "One reel should produce exactly one downloadable file");
        assertTrue(urls.get(0).contains("dash.mp4"), "The highest-resolution (1080p) rendition should be preferred");
    }

    @Test
    public void testPhotoListingCrawlsPermalinksForFullResolution() throws Exception {
        // A /photos listing only embeds tiny thumbnails for most photos. The ripper must follow each
        // photo permalink (fbid) and pull the full-resolution image from that photo's page.
        String listingHtml = "<html><body><script>"
                + "\"https:\\/\\/www.facebook.com\\/photo\\/?fbid=700001&set=a.1\""
                + "\"https:\\/\\/www.facebook.com\\/photo\\/?fbid=700002&set=a.1\""
                // Only thumbnails for those photos are present on the listing itself.
                + "\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.30808-1\\/700001_n.jpg?stp=cp0_dst-jpg_s74x74_tt6&_nc_cat=1\""
                + "\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.30808-1\\/700002_n.jpg?stp=cp0_dst-jpg_s74x74_tt6&_nc_cat=1\""
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, Document> photoPages = new HashMap<>();
        photoPages.put("700001", Jsoup.parse(
                "<html><head><meta property=\"og:image\" "
                        + "content=\"https://scontent.xx.fbcdn.net/v/t39.30808-1/700001_n.jpg?_nc_cat=1\">"
                        + "</head><body></body></html>",
                "https://www.facebook.com/photo/?fbid=700001"));
        photoPages.put("700002", Jsoup.parse(
                "<html><head><meta property=\"og:image\" "
                        + "content=\"https://scontent.xx.fbcdn.net/v/t39.30808-1/700002_n.jpg?_nc_cat=1\">"
                        + "</head><body></body></html>",
                "https://www.facebook.com/photo/?fbid=700002"));

        CrawlingFacebookRipper ripper =
                new CrawlingFacebookRipper(new URL("https://www.facebook.com/example/photos"), photoPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(2, urls.size(), "Each photo should resolve to one full-resolution image");
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/t39.30808-1/700001_n.jpg?_nc_cat=1"));
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/t39.30808-1/700002_n.jpg?_nc_cat=1"));
        assertTrue(urls.stream().noneMatch(u -> u.contains("s74x74")), "Thumbnails must not be downloaded");
    }

    @Test
    public void testPhotoListingFallsBackWhenGraphqlPaginationFails() throws Exception {
        String listingHtml = "<html><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"YXBwX2NvbGxlY3Rpb246VEVTVENPTExFQ1RJT04=\",\"name\":\"Test's Photos\","
                + "\"url\":\"https:\\/\\/www.facebook.com\\/example\\/photos_by\""
                + "\"__typename\":\"TimelineAppCollectionPhotosRenderer\","
                + "\"page_info\":{\"end_cursor\":\"C1\",\"has_next_page\":true}"
                + "\"https:\\/\\/www.facebook.com\\/photo\\/?fbid=800001&set=a.1\""
                + "\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/t39.30808-1\\/800001_n.jpg?stp=cp0_dst-jpg_s74x74_tt6&_nc_cat=1\""
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, Document> photoPages = new HashMap<>();
        photoPages.put("800001", Jsoup.parse(
                "<html><head><meta property=\"og:image\" "
                        + "content=\"https://scontent.xx.fbcdn.net/v/t39.30808-1/800001_n.jpg?_nc_cat=1\">"
                        + "</head><body></body></html>",
                "https://www.facebook.com/photo/?fbid=800001"));

        FailingGraphqlFacebookRipper ripper =
                new FailingGraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"), photoPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(1, urls.size(), "Should fall back to permalink crawl when GraphQL pagination fails");
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/t39.30808-1/800001_n.jpg?_nc_cat=1"));
    }

    @Test
    public void testPhotoListingFallsBackFromPhotosOfToPhotosBy() throws Exception {
        String photosOfId = "YXBwX2NvbGxlY3Rpb246UEhPVE9TT0Y=";
        String photosById = "YXBwX2NvbGxlY3Rpb246UEhPVE9TQlk=";
        String listingHtml = "<html><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"all_collections\":{\"nodes\":[{\"tab_key\":\"photos_of\",\"id\":\"" + photosOfId + "\"},"
                + "{\"tab_key\":\"photos_by\",\"id\":\"" + photosById + "\"}]}"
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, String> graphqlByCollection = new HashMap<>();
        graphqlByCollection.put(photosOfId, "{\"errors\":[{\"message\":\"collection unavailable\"}]}");
        graphqlByCollection.put(photosById,
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/by1.jpg\"}}}}"
                        + "],\"page_info\":{\"end_cursor\":\"C2\",\"has_next_page\":false}}}}}");

        CollectionAwareGraphqlFacebookRipper ripper =
                new CollectionAwareGraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"),
                        graphqlByCollection);
        List<String> urls = ripper.extract(listing);

        assertEquals(1, urls.size(), "Should fall back to photos_by when photos_of pagination fails");
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/by1.jpg"));
    }

    @Test
    public void testPhotoListingPaginatesCometTabKeyCollectionWithoutCursor() throws Exception {
        // Current Comet /photos pages expose collection ids via tab_key and often omit end_cursor in HTML.
        String collectionId = "YXBwX2NvbGxlY3Rpb246VEVTVENPTExFQ1RJT04=";
        String listingHtml = "<html><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"section_type\":\"PHOTOS\",\"tab_key\":\"photos\",\"url\":\"https:\\/\\/www.facebook.com\\/example\\/photos\","
                + "\"all_collections\":{\"nodes\":[{\"tab_key\":\"photos_of\",\"id\":\"" + collectionId + "\"},"
                + "{\"tab_key\":\"photos_by\",\"id\":\"YXBwX2NvbGxlY3Rpb246V1JPTkdPQkxFQ1RJT04=\"}]}"
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, String> graphqlPages = new HashMap<>();
        graphqlPages.put(null,
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/p1.jpg\"}}}}"
                        + "],\"page_info\":{\"end_cursor\":\"C2\",\"has_next_page\":false}}}}}");

        NullCursorGraphqlFacebookRipper ripper =
                new NullCursorGraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"), graphqlPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(1, urls.size(), "Should paginate using photos_of tab_key even without an HTML cursor");
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/p1.jpg"));
    }

    @Test
    public void testGraphqlPaginationUsesViewerImageNotThumbnail() throws Exception {
        // GraphQL responses embed both viewer_image (full-res) and thumbnail (s206x206) for each
        // photo. Pagination must use viewer_image only; thumbnails would be filtered out (<320px).
        String base = "https://scontent.xx.fbcdn.net/v/t51.82787-15/622604539_18555344569004556_n.jpg";
        String listingHtml = "<html><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"YXBwX2NvbGxlY3Rpb246VEVTVENPTExFQ1RJT04=\",\"name\":\"Test's Photos\","
                + "\"url\":\"https:\\/\\/www.facebook.com\\/example\\/photos_of\""
                + "\"__typename\":\"TimelineAppCollectionPhotosRenderer\","
                + "\"page_info\":{\"end_cursor\":\"C1\",\"has_next_page\":true}"
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, String> graphqlPages = new HashMap<>();
        graphqlPages.put("C1",
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{"
                        + "\"viewer_image\":{\"uri\":\"" + base + "?oh=viewer1\"},"
                        + "\"thumbnail\":\"" + base + "?stp=dst-jpg_s206x206&oh=thumb1\""
                        + "}}},"
                        + "{\"node\":{\"node\":{"
                        + "\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/photo2_n.jpg?oh=viewer2\"},"
                        + "\"thumbnail\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/photo2_n.jpg?stp=dst-jpg_s206x206\""
                        + "}}}"
                        + "],\"page_info\":{\"end_cursor\":\"C2\",\"has_next_page\":false}}}}}");

        GraphqlFacebookRipper ripper =
                new GraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"), graphqlPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(2, urls.size(), "Each paginated photo should resolve to one full-resolution viewer_image");
        assertTrue(urls.contains(base + "?oh=viewer1"));
        assertTrue(urls.contains("https://scontent.xx.fbcdn.net/v/photo2_n.jpg?oh=viewer2"));
        assertTrue(urls.stream().noneMatch(u -> u.contains("s206x206")), "Thumbnails must not be downloaded");
    }

    @Test
    public void testPhotoListingPaginatesEntireAlbumViaGraphql() throws Exception {
        // A /photos listing embeds the tokens needed to replay Facebook's GraphQL pagination. The
        // ripper must walk every page (following end_cursor/has_next_page) and collect each photo's
        // full-resolution viewer_image, merged with the first batch already rendered in the page.
        String listingHtml = "<html><head>"
                + "<meta property=\"og:image\" content=\"https://scontent.xx.fbcdn.net/v/p0.jpg\">"
                + "</head><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"YXBwX2NvbGxlY3Rpb246VEVTVENPTExFQ1RJT04=\",\"name\":\"Test's Photos\","
                + "\"url\":\"https:\\/\\/www.facebook.com\\/example\\/photos_by\""
                + "\"__typename\":\"TimelineAppCollectionPhotosRenderer\","
                + "\"page_info\":{\"end_cursor\":\"C1\",\"has_next_page\":true}"
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, String> graphqlPages = new HashMap<>();
        graphqlPages.put("C1",
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/p1.jpg?stp=cp6_tt6\"}}}},"
                        + "{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/p2.jpg?stp=cp6_tt6\"}}}}"
                        + "],\"page_info\":{\"end_cursor\":\"C2\",\"has_next_page\":true}}}}}");
        graphqlPages.put("C2",
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/p3.jpg?stp=cp6_tt6\"}}}}"
                        + "],\"page_info\":{\"end_cursor\":\"C3\",\"has_next_page\":false}}}}}");

        GraphqlFacebookRipper ripper =
                new GraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"), graphqlPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(3, urls.size(), "Every paginated photo should be collected (listing HTML thumbnails are ignored)");
        assertTrue(urls.stream().anyMatch(u -> u.contains("p1.jpg")));
        assertTrue(urls.stream().anyMatch(u -> u.contains("p2.jpg")));
        assertTrue(urls.stream().anyMatch(u -> u.contains("p3.jpg")));
    }

    @Test
    public void testCometThumbnailUrlsAreFilteredFromDownload() throws Exception {
        String thumb = "https://scontent.xx.fbcdn.net/v/t39.30808-1/464182999_8469015733196988_n.jpg"
                + "?stp=cp0_dst-jpg_tt6&cstp=mx746x748&ctp=s80x80"
                + "&_nc_cat=103&oh=00_AQCM0g4qNjFs5WqQ&oe=6A6D01F9";
        String listingHtml = "<html><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"YXBwX2NvbGxlY3Rpb246VEVTVENPTExFQ1RJT04=\",\"name\":\"Test's Photos\","
                + "\"url\":\"https:\\/\\/www.facebook.com\\/example\\/photos_of\""
                + "\"__typename\":\"TimelineAppCollectionPhotosRenderer\","
                + "\"page_info\":{\"end_cursor\":null,\"has_next_page\":true}"
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, String> graphqlPages = new HashMap<>();
        graphqlPages.put(null,
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{"
                        + "\"viewer_image\":{\"uri\":\"" + thumb.replace("/", "\\/") + "\"}"
                        + "}}}]"
                        + ",\"page_info\":{\"has_next_page\":false}}}}}");

        NullCursorGraphqlFacebookRipper ripper =
                new NullCursorGraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"), graphqlPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(0, urls.size(), "Grid thumbnail viewer_image URLs must be filtered, not rewritten");
    }

    @Test
    public void testSignedCdnUrlsArePreservedForDownload() throws Exception {
        String full = "https://scontent.xx.fbcdn.net/v/t39.30808-1/464182999_8469015733196988_n.jpg"
                + "?stp=dst-jpg_s1080x1080_tt6&_nc_cat=103&oh=00_AQCM0g4qNjFs5WqQ&oe=6A6D01F9";
        String listingHtml = "<html><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"YXBwX2NvbGxlY3Rpb246VEVTVENPTExFQ1RJT04=\",\"name\":\"Test's Photos\","
                + "\"url\":\"https:\\/\\/www.facebook.com\\/example\\/photos_of\""
                + "\"__typename\":\"TimelineAppCollectionPhotosRenderer\","
                + "\"page_info\":{\"end_cursor\":null,\"has_next_page\":true}"
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, String> graphqlPages = new HashMap<>();
        graphqlPages.put(null,
                "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                        + "{\"node\":{\"node\":{"
                        + "\"viewer_image\":{\"uri\":\"" + full.replace("/", "\\/") + "\"}"
                        + "}}}]"
                        + ",\"page_info\":{\"has_next_page\":false}}}}}");

        NullCursorGraphqlFacebookRipper ripper =
                new NullCursorGraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"), graphqlPages);
        List<String> urls = ripper.extract(listing);

        assertEquals(1, urls.size());
        assertEquals(full, urls.get(0), "Signed CDN URLs must be kept intact for download");
    }

    @Test
    public void testPhotoPaginationRespectsDiscoveryBudget() throws Exception {
        String listingHtml = "<html><body><script type=\"application/json\">"
                + "[\"DTSGInitialData\",[],{\"token\":\"DTSGTOKEN123\"}]"
                + "[\"LSD\",[],{\"token\":\"LSDTOKEN456\"}]"
                + "\"USER_ID\":\"123456\""
                + "\"YXBwX2NvbGxlY3Rpb246VEVTVENPTExFQ1RJT04=\",\"name\":\"Test's Photos\","
                + "\"url\":\"https:\\/\\/www.facebook.com\\/example\\/photos_of\""
                + "\"__typename\":\"TimelineAppCollectionPhotosRenderer\","
                + "\"page_info\":{\"end_cursor\":\"C1\",\"has_next_page\":true}"
                + "</script></body></html>";
        Document listing = Jsoup.parse(listingHtml, "https://www.facebook.com/example/photos");

        Map<String, String> graphqlPages = new HashMap<>();
        for (int page = 1; page <= 5; page++) {
            String cursorKey = page == 1 ? "C1" : "C" + page;
            String nextCursor = "C" + (page + 1);
            boolean hasNext = page < 5;
            StringBuilder edges = new StringBuilder();
            for (int photo = 1; photo <= 8; photo++) {
                int id = (page - 1) * 8 + photo;
                if (edges.length() > 0) {
                    edges.append(',');
                }
                edges.append("{\"node\":{\"node\":{\"viewer_image\":{\"uri\":\"https:\\/\\/scontent.xx.fbcdn.net\\/v\\/p")
                        .append(id).append(".jpg\"}}}}");
            }
            graphqlPages.put(cursorKey,
                    "{\"data\":{\"node\":{\"pageItems\":{\"edges\":["
                            + edges
                            + "],\"page_info\":{\"end_cursor\":\"" + nextCursor
                            + "\",\"has_next_page\":" + hasNext + "}}}}}");
        }

        BudgetedGraphqlFacebookRipper ripper =
                new BudgetedGraphqlFacebookRipper(new URL("https://www.facebook.com/example/photos"),
                        graphqlPages, 16);
        List<String> urls = ripper.extract(listing);

        assertEquals(16, urls.size(), "Pagination should stop once the discovery budget is reached");
    }

    private static class BudgetedGraphqlFacebookRipper extends GraphqlFacebookRipper {
        private final int budget;

        BudgetedGraphqlFacebookRipper(URL url, Map<String, String> graphqlPages, int budget)
                throws java.io.IOException {
            super(url, graphqlPages);
            this.budget = budget;
        }

        @Override
        protected int getPhotoDiscoveryBudget() {
            return budget;
        }
    }

    private static class CrawlingFacebookRipper extends TestableFacebookRipper {
        private final Map<String, Document> photoPages;

        CrawlingFacebookRipper(URL url, Map<String, Document> photoPages) throws java.io.IOException {
            super(url);
            this.photoPages = photoPages;
        }

        @Override
        protected Document fetchPhotoPage(String fbid) {
            return photoPages.get(fbid);
        }
    }

    private static class FailingGraphqlFacebookRipper extends CrawlingFacebookRipper {
        FailingGraphqlFacebookRipper(URL url, Map<String, Document> photoPages) throws java.io.IOException {
            super(url, photoPages);
        }

        @Override
        protected String executeGraphqlQuery(String friendlyName, String lsd, Map<String, String> formData) {
            return "{\"errors\":[{\"message\":\"Invalid doc_id\"}]}";
        }
    }

    private static class GraphqlFacebookRipper extends TestableFacebookRipper {
        private static final java.util.regex.Pattern CURSOR =
                java.util.regex.Pattern.compile("\"cursor\":\"([^\"]+)\"");
        private final Map<String, String> graphqlPages;

        GraphqlFacebookRipper(URL url, Map<String, String> graphqlPages) throws java.io.IOException {
            super(url);
            this.graphqlPages = graphqlPages;
        }

        @Override
        protected String executeGraphqlQuery(String friendlyName, String lsd, Map<String, String> formData) {
            String variables = formData.getOrDefault("variables", "");
            java.util.regex.Matcher m = CURSOR.matcher(variables);
            String key = m.find() ? m.group(1) : null;
            if (key == null && variables.contains("\"cursor\":null")) {
                key = null;
            }
            return graphqlPages.get(key);
        }
    }

    private static class NullCursorGraphqlFacebookRipper extends GraphqlFacebookRipper {
        NullCursorGraphqlFacebookRipper(URL url, Map<String, String> graphqlPages) throws java.io.IOException {
            super(url, graphqlPages);
        }
    }

    private static class CollectionAwareGraphqlFacebookRipper extends TestableFacebookRipper {
        private static final java.util.regex.Pattern COLLECTION_ID =
                java.util.regex.Pattern.compile("\"id\":\"(YXBwX2NvbGxlY3Rpb246[^\"]+)\"");
        private final Map<String, String> responsesByCollection;

        CollectionAwareGraphqlFacebookRipper(URL url, Map<String, String> responsesByCollection)
                throws java.io.IOException {
            super(url);
            this.responsesByCollection = responsesByCollection;
        }

        @Override
        protected String executeGraphqlQuery(String friendlyName, String lsd, Map<String, String> formData) {
            java.util.regex.Matcher m = COLLECTION_ID.matcher(formData.getOrDefault("variables", ""));
            return m.find() ? responsesByCollection.get(m.group(1)) : null;
        }
    }

    private static String videoUrl(String name, String efgJson) {
        String efg = URLEncoder.encode(
                Base64.getEncoder().encodeToString(efgJson.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        return "https://video.xx.fbcdn.net/o1/v/t2/f2/m367/" + name + "?_nc_cat=1&efg=" + efg + "&ccb=17-1";
    }
}
