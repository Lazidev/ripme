package com.rarchives.ripme.ripper.rippers;

import static java.lang.String.format;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.Jsoup;
import org.jsoup.HttpStatusException;
import java.sql.Connection;
import java.nio.file.*;

import com.rarchives.ripme.ripper.AbstractJSONRipper;
import com.rarchives.ripme.ripper.AbstractRipper;
import com.rarchives.ripme.ui.RipStatusMessage;
import com.rarchives.ripme.utils.DownloadLimitTracker;
import com.rarchives.ripme.utils.FirefoxCookieUtils;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.RipUtils;
import com.rarchives.ripme.utils.Utils;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class InstagramRipper extends AbstractJSONRipper {
    private static final Logger logger = LogManager.getLogger(InstagramRipper.class);
    private static final int WAIT_TIME = 2000;
    private static final int TIMEOUT = 20000;
    private static final int MAX_RATE_LIMIT_RETRIES = 6;
    private static final String INSTAGRAM_APP_ID = "936619743392459";
    /** Instagram rejects truncated or non-browser user agents with 429/403. */
    private static final String INSTAGRAM_USER_AGENT = AbstractRipper.USER_AGENT;
    /**
     * Logged-out Polaris profile timeline query (returns classic
     * {@code edge_owner_to_timeline_media} edges).
     */
    private static final String GRAPHQL_DOC_ID_PROFILE_TIMELINE = "7950326061742207";
    /**
     * Logged-in Polaris user timeline query (returns
     * {@code xdt_api__v1__feed__user_timeline_graphql_connection} with private-API media nodes).
     */
    private static final String GRAPHQL_DOC_ID_FEED_TIMELINE = "7898261790222653";
    /**
     * Keyword search explore page ({@code /popular/{query}}), used by
     * {@code PolarisKeywordSearchExplorePageRelayQuery}.
     */
    private static final String GRAPHQL_DOC_ID_KEYWORD_SEARCH = "37324993597144881";
    private static final String GRAPHQL_FRIENDLY_NAME_KEYWORD_SEARCH = "PolarisKeywordSearchExplorePageRelayQuery";
    private String csrftoken = null;
    
    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LogManager.getLogger(InstagramRipper.class).warn("SQLite JDBC driver not found. Firefox cookie authentication will not be available.");
        }
    }
    
    private String idString;
    private String cachedUserId = null;
    private Map<String, String> cookies = new HashMap<>();
    private boolean hasNextPage = true;
    private String endCursor = null;
    private String popularSearchSessionId = null;
    private final int maxDownloads = Utils.getConfigInteger("maxdownloads", -1);
    private final DownloadLimitTracker downloadLimitTracker = new DownloadLimitTracker(maxDownloads);
    private volatile boolean maxDownloadLimitReached = false;

    public InstagramRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    protected String getDomain() {
        return "instagram.com";
    }

    @Override
    protected boolean usesCustomDownloadLimitTracking() {
        return true;
    }

    @Override
    public String getHost() {
        return "instagram";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        if (isPopularUrl(url)) {
            return getPopularKeyword(url);
        }
        // Reels rip into the same album folder as the profile's posts.
        return getUsername(url);
    }

    /** Extracts just the account handle, ignoring any trailing tab like {@code /reels/}. */
    private String getUsername(URL url) throws MalformedURLException {
        Pattern pattern = Pattern.compile("https?://(?:www\\.)?instagram\\.com/(?<username>[^/?#]+)");
        Matcher matcher = pattern.matcher(url.toExternalForm());
        if (matcher.find()) {
            return matcher.group("username");
        }
        throw new MalformedURLException("Expected format: https://www.instagram.com/username/");
    }

    /**
     * True when the URL is Instagram's keyword search page
     * ({@code /popular/{query}}), not a user named {@code popular}.
     */
    boolean isPopularUrl(URL url) {
        return url.toExternalForm()
                .matches("(?i)https?://(?:www\\.)?instagram\\.com/popular/[^/?#]+/?([?#].*)?");
    }

    /** Keyword from {@code /popular/{query}}, used as the album folder name. */
    String getPopularKeyword(URL url) throws MalformedURLException {
        Pattern pattern = Pattern.compile("(?i)https?://(?:www\\.)?instagram\\.com/popular/(?<keyword>[^/?#]+)");
        Matcher matcher = pattern.matcher(url.toExternalForm());
        if (matcher.find()) {
            return URLDecoder.decode(matcher.group("keyword"), StandardCharsets.UTF_8);
        }
        throw new MalformedURLException("Expected format: https://www.instagram.com/popular/keyword/");
    }

    /** True when the URL points at a profile's reels tab (e.g. {@code /username/reels/}). */
    private boolean isReelsUrl(URL url) {
        return url.toExternalForm()
                .matches("(?i)https?://(?:www\\.)?instagram\\.com/[^/?#]+/reels/?(?:[?#].*)?");
    }

    @Override
    protected void downloadURL(URL url, int index) {
        boolean countTowardsLimit = true;
        if (downloadLimitTracker.isEnabled()) {
            try {
                Path existingPath = getFilePath(url, "", getPrefix(index), null, null);
                if (Files.exists(existingPath)) {
                    if (!Utils.getConfigBoolean("file.overwrite", false)) {
                        logger.debug("Skipping existing file due to max download limit: {}", existingPath);
                        super.downloadExists(url, existingPath);
                        return;
                    }
                    countTowardsLimit = false;
                }
            } catch (IOException e) {
                logger.warn("Unable to determine existing file path for {}: {}", url, e.getMessage());
            }
        }

        if (!downloadLimitTracker.tryAcquire(url, countTowardsLimit)) {
            if (downloadLimitTracker.isLimitReached()) {
                maxDownloadLimitReached = true;
                hasNextPage = false;
                if (downloadLimitTracker.shouldNotifyLimitReached()) {
                    String message = "Reached max download limit of " + maxDownloads + ". Stopping.";
                    logger.info(message);
                    sendUpdate(RipStatusMessage.STATUS.DOWNLOAD_COMPLETE_HISTORY, message);
                }
            } else {
                logger.debug("Max download limit of {} currently allocated, deferring {}", maxDownloads, url);
            }
            return;
        }

        boolean added = addURLToDownload(url, getPrefix(index));
        if (added) {
            if (Utils.getConfigBoolean("urls_only.save", false)) {
                handleSuccessfulDownload(url);
            }
        } else {
            downloadLimitTracker.onFailure(url);
        }
    }

    @Override
    protected String getPrefix(int index) {
        return String.format("%03d_", index);
    }

    private String getFirefoxCookiesPath() {
        String userHome = System.getProperty("user.home");
        Path cookiesPath;
        
        if (System.getProperty("os.name").startsWith("Windows")) {
            cookiesPath = Paths.get(userHome, "AppData", "Roaming", "Mozilla", "Firefox", "Profiles");
        } else if (System.getProperty("os.name").startsWith("Mac")) {
            cookiesPath = Paths.get(userHome, "Library", "Application Support", "Firefox", "Profiles");
        } else {
            cookiesPath = Paths.get(userHome, ".mozilla", "firefox");
        }
        
        if (!Files.exists(cookiesPath)) {
            return null;
        }
        
        try {
            return Files.walk(cookiesPath)
                .filter(path -> path.getFileName().toString().equals("cookies.sqlite"))
                .findFirst()
                .map(Path::toString)
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, String> extractCookiesFromQuery(PreparedStatement stmt) throws SQLException {
        Map<String, String> extractedCookies = new HashMap<>();
        ResultSet rs = null;
        try {
            rs = stmt.executeQuery();
            while (rs.next()) {
                String name = rs.getString("name");
                String value = rs.getString("value");
                extractedCookies.put(name, value);
                
                if ("csrftoken".equals(name)) {
                    this.csrftoken = value;
                }
            }
            
            return extractedCookies;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    logger.warn("Error closing ResultSet: " + e.getMessage());
                }
            }
        }
    }    @Override
    protected JSONObject getFirstPage() throws IOException {
        if (isPopularUrl(url)) {
            String keyword = getPopularKeyword(url);
            logger.info("Ripping Instagram popular search: {}", keyword);
            extractFirefoxCookies();
            JSONObject timeline = convertKeywordSearchToTimeline(fetchKeywordSearchPage(keyword, null));
            JSONArray edges = timeline.getJSONObject("data")
                    .getJSONObject("user")
                    .getJSONObject("edge_owner_to_timeline_media")
                    .optJSONArray("edges");
            if (edges == null || edges.length() == 0) {
                throw new IOException("No images found in Instagram popular search for '" + keyword + "'.");
            }
            return timeline;
        }

        String username = getUsername(url);
        boolean reels = isReelsUrl(url);
        logger.info("Ripping Instagram {}: {}", reels ? "reels" : "profile", username);

        extractFirefoxCookies();
        if (!hasCookie("sessionid")) {
            logger.warn("No Instagram sessionid cookie found. Public profiles may still work via "
                    + "web_profile_info; private profiles and most feed pagination require a Firefox login.");
        }

        if (reels) {
            if (!hasCookie("sessionid")) {
                throw loginRequiredException("Reels rips require a logged-in Instagram session.");
            }
            JSONObject clips = getClipsUserPage(username, null);
            validateTimelineResponse(clips, username);
            return clips;
        }

        IOException lastFailure = null;

        // Prefer the private feed API when logged in (50 items/page, full media URLs).
        if (hasCookie("sessionid")) {
            try {
                JSONObject feedPage = getFeedUserPage(username, null);
                validateTimelineResponse(feedPage, username);
                return feedPage;
            } catch (IOException e) {
                lastFailure = e;
                logger.warn("Feed API first page failed for {}: {}. Trying GraphQL fallback.",
                        username, e.getMessage());
            }

            // Same GraphQL path used for pagination — often still works when feed returns HTML
            // and web_profile_info is bot-blocked with empty 429s.
            try {
                JSONObject graphqlPage = fetchGraphqlTimelinePage(username, null);
                validateTimelineResponse(graphqlPage, username);
                return graphqlPage;
            } catch (IOException e) {
                lastFailure = e;
                logger.warn("GraphQL first page failed for {}: {}. Falling back to web_profile_info.",
                        username, e.getMessage());
            }
        }

        try {
            JSONObject profilePage = fetchWebProfileInfoPage(username);
            validateTimelineResponse(profilePage, username);
            return profilePage;
        } catch (IOException e) {
            lastFailure = e;
            logger.warn("web_profile_info first page failed for {}: {}", username, e.getMessage());
        }

        // Without sessionid, GraphQL usually returns {"user": null} after a 429/block —
        // fail fast with actionable guidance instead of crashing later.
        if (!hasCookie("sessionid")) {
            if (looksLikeRateLimitOrBlock(lastFailure)) {
                throw loginRequiredException(
                        "Could not load Instagram profile '" + username + "' "
                                + "(web_profile_info blocked/429 and no sessionid cookie). "
                                + "Instagram returned empty user data — log into Firefox and fully quit "
                                + "so sessionid is available.");
            }
            try {
                JSONObject graphqlPage = fetchGraphqlTimelinePage(username, null);
                validateTimelineResponse(graphqlPage, username);
                return graphqlPage;
            } catch (IOException e) {
                lastFailure = e;
                logger.warn("GraphQL first page failed for {}: {}", username, e.getMessage());
            }
            throw loginRequiredException(
                    "Could not load Instagram profile '" + username + "'. "
                            + "Instagram returned empty user data — log into Firefox and fully quit "
                            + "so sessionid is available. "
                            + "Or set cookies.instagram.com in ripme.json with sessionid=...; csrftoken=...");
        }
        throw new IOException("Failed to get Instagram media for '" + username + "'. "
                + "Your Firefox session may be expired or Instagram is blocking the request. "
                + "Re-login in Firefox, quit the browser, and try again.", lastFailure);
    }

    private void validateTimelineResponse(JSONObject json, String username) throws IOException {
        if (json == null) {
            throw new IOException("Failed to get user data from Instagram for " + username + ".");
        }
        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            throw new IOException("Failed to get user data from Instagram for " + username + ".");
        }
        JSONObject user = data.optJSONObject("user");
        if (user == null) {
            throw loginRequiredException("Instagram returned empty user data for '" + username + "'.");
        }
        if (!user.has("edge_owner_to_timeline_media")) {
            if (user.optBoolean("is_private", false) && !hasCookie("sessionid")) {
                throw loginRequiredException("Profile '" + username + "' is private.");
            }
            throw new IOException("No media data found for user '" + username
                    + "'. The account may be private, blocked, or have no posts.");
        }
    }

    private boolean looksLikeRateLimitOrBlock(IOException e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String message = e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("429")
                || message.contains("rate limit")
                || message.contains("login")
                || message.contains("html instead of json")
                || message.contains("checkpoint");
    }

    private IOException loginRequiredException(String detail) {
        return new IOException(detail + " Log into Instagram in Firefox, fully quit Firefox so cookies "
                + "are flushed to disk, then retry. Alternatively set cookies.instagram.com in ripme.json "
                + "(must include sessionid and csrftoken).");
    }

    /**
     * Fetches a page of profile posts via {@code /api/v1/feed/user/{id}/} and normalizes
     * it to the GraphQL timeline shape expected by {@link #getURLsFromJSON(JSONObject)}.
     */
    private JSONObject getFeedUserPage(String username, String endCursor) throws IOException {
        String userId = getUserID(username);
        if (userId == null || userId.isEmpty()) {
            throw new IOException("Failed to get user ID for " + username);
        }
        StringBuilder urlBuilder = new StringBuilder(String.format(
                "https://www.instagram.com/api/v1/feed/user/%s/?count=50", userId));
        if (endCursor != null && !endCursor.isEmpty()) {
            urlBuilder.append("&max_id=").append(endCursor);
        }
        String requestUrl = urlBuilder.toString();
        logger.debug("Fetching feed API URL: {}", requestUrl);

        Http feedRequest = Http.url(requestUrl)
                .userAgent(INSTAGRAM_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-IG-App-ID", INSTAGRAM_APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-ASBD-ID", "129477")
                .header("X-IG-WWW-Claim", cookies.getOrDefault("ig_www_claim", "0"))
                .header("X-CSRFToken", cookies.getOrDefault("csrftoken", ""))
                .header("Origin", "https://www.instagram.com")
                .header("DNT", "1")
                .header("Connection", "keep-alive")
                .header("Referer", "https://www.instagram.com/" + username + "/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .cookies(cookies);
        applyOptionalInstagramHeaders(feedRequest);
        Response response = feedRequest.ignoreContentType().ignoreHttpErrors().response();

        int statusCode = response.statusCode();
        String body = response.body();
        logger.debug("Instagram feed API {} -> status {} (len={})", requestUrl, statusCode,
                body != null ? body.length() : 0);

        if (statusCode == 429) {
            logger.warn("Instagram feed API rate limited {} body={}", requestUrl, summarizeBody(body));
            throw new IOException("Rate limited by Instagram. Please wait a few minutes before trying again.");
        }
        if (statusCode != 200) {
            logger.warn("Instagram feed API error {} -> status {} body={}", requestUrl, statusCode,
                    summarizeBody(body));
            throw new IOException("HTTP error " + statusCode + " while fetching feed for " + username);
        }

        JSONObject json = parseInstagramJsonBody(body, "feed API for " + username);
        if (json.has("items")) {
            return convertFeedToTimeline(json);
        }
        if (json.has("data")) {
            return json;
        }
        if (json.has("message")) {
            throw new IOException("Instagram API error: " + json.optString("message"));
        }
        throw new IOException("Invalid feed JSON response - missing items/data objects");
    }

    /**
     * Parses an Instagram API body, detecting login walls / HTML challenge pages that
     * historically produced the misleading "couldn't extract JSON from HTML" error.
     * Package-private for unit tests.
     */
    JSONObject parseInstagramJsonBody(String body, String actionDescription) throws IOException {
        if (body == null || body.trim().isEmpty()) {
            throw new IOException("Empty response from Instagram while " + actionDescription);
        }

        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(trimmed);
                String message = json.optString("message", "");
                String status = json.optString("status", "");
                String messageLower = message.toLowerCase(Locale.ROOT);
                if ("fail".equalsIgnoreCase(status)
                        && (messageLower.contains("login")
                        || messageLower.contains("checkpoint")
                        || "login_required".equalsIgnoreCase(message))) {
                    throw loginRequiredException("Instagram returned '" + message + "' while "
                            + actionDescription + ".");
                }
                return json;
            } catch (JSONException e) {
                throw new IOException("Failed to parse Instagram JSON while " + actionDescription
                        + ": " + e.getMessage(), e);
            }
        }

        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("login_required")
                || lower.contains("\"requirelogin\"")
                || lower.contains("require_login")
                || lower.contains("/accounts/login")
                || lower.contains("checkpoint_required")
                || lower.contains("challenge_required")) {
            throw loginRequiredException("Instagram returned a login/challenge page while "
                    + actionDescription + ".");
        }

        // Legacy embeddings (rarely present since ~2024).
        Pattern sharedData = Pattern.compile("window\\._sharedData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
        Matcher sharedMatcher = sharedData.matcher(body);
        if (sharedMatcher.find()) {
            try {
                return new JSONObject(sharedMatcher.group(1));
            } catch (JSONException e) {
                logger.debug("Found _sharedData but failed to parse it: {}", e.getMessage());
            }
        }

        throw new IOException("Instagram returned HTML instead of JSON while " + actionDescription
                + ". This usually means the session expired, cookies are missing sessionid, "
                + "or Instagram is blocking the request. Log into Instagram in Firefox, quit Firefox, and retry.");
    }

    /**
     * Loads profile metadata + first timeline page from
     * {@code /api/v1/users/web_profile_info/}. Caches the user id.
     */
    private JSONObject fetchWebProfileInfoPage(String username) throws IOException {
        String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);
        String profilePath = "/api/v1/users/web_profile_info/?username=" + encodedUsername;
        IOException lastException = null;

        // Anonymous-friendly host first (matches current Instaloader approach).
        try {
            Response response = executeInstagramApiRequest(
                    "https://i.instagram.com" + profilePath,
                    "https://www.instagram.com/" + username + "/",
                    "fetching web_profile_info for " + username,
                    hasCookie("sessionid"));
            if (response.statusCode() == 200) {
                JSONObject json = parseInstagramJsonBody(response.body(),
                        "web_profile_info for " + username);
                cacheUserIdFromProfileJson(json);
                return json;
            }
            lastException = new IOException("i.instagram.com web_profile_info HTTP " + response.statusCode());
        } catch (IOException e) {
            lastException = e;
            logger.debug("i.instagram.com web_profile_info failed for {}: {}", username, e.getMessage());
        }

        Response response = executeInstagramApiRequest(
                "https://www.instagram.com" + profilePath,
                "https://www.instagram.com/" + username + "/",
                "fetching web_profile_info for " + username,
                true);
        if (response.statusCode() != 200) {
            throw new IOException("web_profile_info HTTP " + response.statusCode()
                    + " for " + username, lastException);
        }
        JSONObject json = parseInstagramJsonBody(response.body(), "web_profile_info for " + username);
        cacheUserIdFromProfileJson(json);
        return json;
    }

    private void cacheUserIdFromProfileJson(JSONObject json) {
        if (json == null || !json.has("data")) {
            return;
        }
        JSONObject user = json.getJSONObject("data").optJSONObject("user");
        if (user == null) {
            return;
        }
        String id = user.optString("id", "");
        if (id.isEmpty()) {
            id = user.optString("pk", "");
        }
        if (!id.isEmpty()) {
            cacheUserId(id);
        }
    }
    /**
     * Fetches a page of a user's reels via the private {@code /api/v1/clips/user/} endpoint.
     * Unlike the web reels-tab GraphQL query (which only returns cover images), this endpoint
     * returns full media objects including {@code video_versions}, so we can download the videos.
     * The response is normalized into the same shape {@link #getURLsFromJSON(JSONObject)} expects.
     */
    private JSONObject getClipsUserPage(String username, String maxId) throws IOException {
        String userId = getUserID(username);
        if (userId == null || userId.isEmpty()) {
            throw new IOException("Failed to get user ID for " + username);
        }

        String requestUrl = "https://www.instagram.com/api/v1/clips/user/";
        Map<String, String> data = new HashMap<>();
        data.put("target_user_id", userId);
        data.put("page_size", "50");
        data.put("include_feed_video", "true");
        if (maxId != null && !maxId.isEmpty()) {
            data.put("max_id", maxId);
        }
        logger.debug("Fetching reels for {} (maxId={})", username, maxId);

        try {
            Http request = Http.url(requestUrl)
                    .method(Method.POST)
                    .data(data)
                    .userAgent(INSTAGRAM_USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("X-IG-App-ID", INSTAGRAM_APP_ID)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("X-ASBD-ID", "129477")
                    .header("X-IG-WWW-Claim", cookies.getOrDefault("ig_www_claim", "0"))
                    .header("X-CSRFToken", cookies.getOrDefault("csrftoken", ""))
                    .header("Origin", "https://www.instagram.com")
                    .header("Referer", "https://www.instagram.com/" + username + "/reels/")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .cookies(cookies)
                    .ignoreContentType()
                    .ignoreHttpErrors();
            applyOptionalInstagramHeaders(request);
            Response response = request.response();

            int statusCode = response.statusCode();
            String jsonText = response.body();
            logger.debug("Instagram clips API {} -> status {} (len={})", requestUrl, statusCode,
                    jsonText != null ? jsonText.length() : 0);

            if (statusCode == 429) {
                logger.warn("Instagram clips API rate limited {} body={}", requestUrl, summarizeBody(jsonText));
                throw new IOException("Rate limited by Instagram. Please wait a few minutes before trying again.");
            }
            if (statusCode != 200) {
                logger.warn("Instagram clips API error {} -> status {} body={}", requestUrl, statusCode,
                        summarizeBody(jsonText));
                throw new IOException("HTTP error " + statusCode + " while fetching reels for " + username);
            }
            if (jsonText == null || jsonText.isEmpty()) {
                throw new IOException("Empty response from Instagram clips API");
            }

            JSONObject json = parseInstagramJsonBody(jsonText, "clips API for " + username);
            if (!json.has("items")) {
                if (json.has("message")) {
                    throw new IOException("Instagram API error: " + json.getString("message"));
                }
                throw new IOException("Invalid clips response - missing items array");
            }
            return convertClipsToTimeline(json);
        } catch (JSONException e) {
            logger.error("Error parsing clips response: " + e.getMessage());
            throw new IOException("Failed to parse Instagram clips response: " + e.getMessage());
        }
    }

    /**
     * Reshapes a {@code /api/v1/feed/user/} response into the GraphQL timeline structure.
     * Package-private for unit tests.
     */
    JSONObject convertFeedToTimeline(JSONObject feed) {
        JSONArray items = feed.getJSONArray("items");
        JSONArray edges = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject node = mediaToNode(items.getJSONObject(i));
            if (node == null) {
                continue;
            }
            JSONObject edge = new JSONObject();
            edge.put("node", node);
            edges.put(edge);
        }

        JSONObject timelineMedia = new JSONObject();
        timelineMedia.put("edges", edges);
        if (feed.has("more_available")) {
            JSONObject pageInfo = new JSONObject();
            pageInfo.put("has_next_page", feed.getBoolean("more_available"));
            if (feed.has("next_max_id") && !feed.isNull("next_max_id")) {
                pageInfo.put("end_cursor", feed.get("next_max_id").toString());
            }
            timelineMedia.put("page_info", pageInfo);
        }

        JSONObject user = new JSONObject();
        user.put("edge_owner_to_timeline_media", timelineMedia);
        JSONObject data = new JSONObject();
        data.put("user", user);
        JSONObject graphqlStyle = new JSONObject();
        graphqlStyle.put("data", data);
        return graphqlStyle;
    }

    /** Reshapes a {@code /clips/user/} response into the GraphQL timeline structure. */
    private JSONObject convertClipsToTimeline(JSONObject clips) {
        JSONArray items = clips.getJSONArray("items");
        JSONArray edges = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject media = items.getJSONObject(i).optJSONObject("media");
            if (media == null) {
                continue;
            }
            JSONObject node = mediaToNode(media);
            if (node != null) {
                JSONObject edge = new JSONObject();
                edge.put("node", node);
                edges.put(edge);
            }
        }

        boolean hasNext = false;
        String endCursorValue = null;
        if (clips.has("paging_info") && !clips.isNull("paging_info")) {
            JSONObject paging = clips.getJSONObject("paging_info");
            hasNext = paging.optBoolean("more_available", false);
            endCursorValue = paging.optString("max_id", null);
        }

        JSONObject pageInfo = new JSONObject();
        pageInfo.put("has_next_page", hasNext && endCursorValue != null && !endCursorValue.isEmpty());
        if (endCursorValue != null) {
            pageInfo.put("end_cursor", endCursorValue);
        }

        JSONObject timelineMedia = new JSONObject();
        timelineMedia.put("edges", edges);
        timelineMedia.put("page_info", pageInfo);

        JSONObject user = new JSONObject();
        user.put("edge_owner_to_timeline_media", timelineMedia);
        JSONObject dataObj = new JSONObject();
        dataObj.put("user", user);
        JSONObject result = new JSONObject();
        result.put("data", dataObj);
        return result;
    }

    /** Converts a single private-API media object into a GraphQL-style node. */
    private JSONObject mediaToNode(JSONObject media) {
        if (media.has("carousel_media") && !media.isNull("carousel_media")) {
            JSONArray carousel = media.getJSONArray("carousel_media");
            JSONArray childEdges = new JSONArray();
            for (int i = 0; i < carousel.length(); i++) {
                String childUrl = bestMediaUrl(carousel.getJSONObject(i));
                if (childUrl != null) {
                    JSONObject childNode = new JSONObject();
                    childNode.put("display_url", childUrl);
                    JSONObject childEdge = new JSONObject();
                    childEdge.put("node", childNode);
                    childEdges.put(childEdge);
                }
            }
            if (childEdges.length() == 0) {
                return null;
            }
            JSONObject node = new JSONObject();
            node.put("__typename", "GraphSidecar");
            JSONObject sidecar = new JSONObject();
            sidecar.put("edges", childEdges);
            node.put("edge_sidecar_to_children", sidecar);
            return node;
        }

        JSONObject node = new JSONObject();
        String video = videoUrl(media);
        if (video != null) {
            node.put("__typename", "GraphVideo");
            node.put("video_url", video);
            String image = imageUrl(media);
            node.put("display_url", image != null ? image : "");
            return node;
        }

        String image = imageUrl(media);
        if (image == null) {
            return null;
        }
        node.put("__typename", "GraphImage");
        node.put("display_url", image);
        return node;
    }

    private String bestMediaUrl(JSONObject media) {
        String video = videoUrl(media);
        return video != null ? video : imageUrl(media);
    }

    private String videoUrl(JSONObject media) {
        if (media.has("video_versions") && !media.isNull("video_versions")) {
            JSONArray versions = media.getJSONArray("video_versions");
            if (versions.length() > 0) {
                return versions.getJSONObject(0).optString("url", null);
            }
        }
        return null;
    }

    private String imageUrl(JSONObject media) {
        if (media.has("image_versions2") && !media.isNull("image_versions2")) {
            JSONObject imageVersions = media.getJSONObject("image_versions2");
            if (imageVersions.has("candidates")) {
                JSONArray candidates = imageVersions.getJSONArray("candidates");
                if (candidates.length() > 0) {
                    return candidates.getJSONObject(0).optString("url", null);
                }
            }
        }
        return null;
    }

    private String getUserID(String username) throws IOException {
        if (cachedUserId != null && !cachedUserId.isEmpty()) {
            return cachedUserId;
        }

        logger.debug("Getting user ID for username: " + username);

        IOException lastException = null;

        // web_profile_info is the most reliable current path (also used by Instaloader).
        try {
            String id = fetchUserIdFromProfile(username);
            if (id != null && !id.isEmpty()) {
                logger.info("Resolved user ID for {} via web_profile_info API", username);
                return cacheUserId(id);
            }
        } catch (IOException e) {
            lastException = e;
            logger.warn("web_profile_info lookup failed for {}: {}", username, e.getMessage());
        }

        try {
            String id = fetchUserIdFromTopSearch(username);
            if (id != null && !id.isEmpty()) {
                logger.info("Resolved user ID for {} via topsearch", username);
                return cacheUserId(id);
            }
        } catch (IOException e) {
            lastException = e;
            logger.warn("Topsearch lookup failed for {}: {}", username, e.getMessage());
        }

        try {
            String id = fetchUserIdFromProfileHtml(username);
            if (id != null && !id.isEmpty()) {
                logger.info("Resolved user ID for {} via profile page HTML", username);
                return cacheUserId(id);
            }
        } catch (IOException e) {
            lastException = e;
            logger.warn("Profile HTML lookup failed for {}: {}", username, e.getMessage());
        }

        throw new IOException("Could not fetch user ID for '" + username
                + "'. Log into Instagram in Firefox, fully quit Firefox, and retry "
                + "(sessionid cookie required for many lookups).", lastException);
    }

    private String cacheUserId(String userId) {
        cachedUserId = userId;
        return userId;
    }

    private Response executeInstagramApiRequest(String requestUrl, String referer, String actionDescription) throws IOException {
        return executeInstagramApiRequest(requestUrl, referer, actionDescription, true);
    }

    private Response executeInstagramApiRequest(String requestUrl, String referer, String actionDescription, boolean sendCookies) throws IOException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
            try {
                Http request = Http.url(requestUrl)
                        .retries(1)
                        .ignoreHttpErrors()
                        .userAgent(INSTAGRAM_USER_AGENT)
                        .header("Accept", "application/json, */*;q=0.1")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("X-IG-App-ID", INSTAGRAM_APP_ID)
                        .ignoreContentType();

                if (sendCookies) {
                    request.header("X-Requested-With", "XMLHttpRequest")
                            .header("X-ASBD-ID", "129477")
                            .header("X-IG-WWW-Claim", cookies.getOrDefault("ig_www_claim", "0"))
                            .header("X-CSRFToken", cookies.getOrDefault("csrftoken", ""))
                            .header("Origin", "https://www.instagram.com")
                            .header("Connection", "keep-alive")
                            .header("Sec-Fetch-Dest", "empty")
                            .header("Sec-Fetch-Mode", "cors")
                            .header("Sec-Fetch-Site", requestUrl.contains("i.instagram.com") ? "same-site" : "same-origin")
                            .cookies(cookies);
                    applyOptionalInstagramHeaders(request);
                } else {
                    request.header("Sec-Fetch-Dest", "empty")
                            .header("Sec-Fetch-Mode", "cors")
                            .header("Sec-Fetch-Site", "same-site");
                }

                if (referer != null && !referer.isEmpty()) {
                    request.header("Referer", referer);
                }

                Response response = request.response();
                int statusCode = response.statusCode();
                String responseBody = response.body();
                logger.debug("Instagram API {} -> status {} (len={})", requestUrl, statusCode, responseBody != null ? responseBody.length() : 0);
                if (statusCode >= 400) {
                    logInstagramApiError(requestUrl, statusCode, responseBody, response.headers());
                }

                if (statusCode == 429) {
                    boolean likelyBotBlock = responseBody == null || responseBody.trim().isEmpty();
                    if (likelyBotBlock) {
                        throw new InstagramBotBlockedException("Instagram blocked request while " + actionDescription);
                    }
                    throw new HttpStatusException("HTTP error fetching URL", 429, requestUrl);
                }

                return response;
            } catch (IOException e) {
                lastException = e;

                if (e instanceof InstagramBotBlockedException) {
                    throw new IOException(e.getMessage(), e);
                }

                boolean isRateLimit = e instanceof HttpStatusException && ((HttpStatusException) e).getStatusCode() == 429;
                long waitMillis = WAIT_TIME * (1L << (attempt - 1));

                if (isRateLimit) {
                    notifyRateLimited("Instagram HTTP 429 while " + actionDescription);
                }

                if (attempt < MAX_RATE_LIMIT_RETRIES && (isRateLimit || !(e instanceof HttpStatusException))) {
                    if (isRateLimit) {
                        logger.warn("Instagram returned 429 while {} (attempt {}/{}). Waiting {} ms before retry. "
                                + "If this persists on the first attempt, verify Firefox login cookies (sessionid) "
                                + "or set cookies.instagram.com in config.",
                                actionDescription, attempt, MAX_RATE_LIMIT_RETRIES, waitMillis);
                    } else {
                        logger.warn("Error {} (attempt {}/{}). Waiting {} ms before retry: {}", actionDescription, attempt, MAX_RATE_LIMIT_RETRIES, waitMillis, e.getMessage());
                    }
                    Utils.sleep(waitMillis);
                    continue;
                }

                if (isRateLimit) {
                    throw new IOException("Instagram rate limited request while " + actionDescription + ".", e);
                }

                throw new IOException("HTTP error while " + actionDescription + ": " + e.getMessage(), e);
            }
        }

        throw new IOException("Failed to " + actionDescription + " after " + MAX_RATE_LIMIT_RETRIES + " attempts", lastException);
    }


    private String summarizeBody(String body) {
        if (body == null) {
            return "<null>";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 300));
    }

    private void logInstagramApiError(String requestUrl, int statusCode, String body, Map<String, String> headers) {
        logger.warn("Instagram API error {} -> status {} body={}", requestUrl, statusCode, summarizeBody(body));
        if (statusCode != 429 || headers == null) {
            return;
        }

        String retryAfter = firstHeader(headers, "retry-after");
        String wwwClaim = firstHeader(headers, "ig-set-www-claim", "x-ig-set-www-claim");
        String bodySummary = summarizeBody(body);
        if (bodySummary.equals("<null>") || bodySummary.isEmpty()) {
            logger.warn("Empty 429 response is often bot detection or an invalid session, not a true rate limit. "
                    + "Retry-After={} ig-set-www-claim={} hasSessionid={} hasCsrftoken={}",
                    retryAfter, wwwClaim, hasCookie("sessionid"), hasCookie("csrftoken"));
        } else if (bodySummary.toLowerCase(Locale.ROOT).contains("useragent")
                || bodySummary.toLowerCase(Locale.ROOT).contains("checkpoint")
                || bodySummary.toLowerCase(Locale.ROOT).contains("login")) {
            logger.warn("Instagram rejected the request (auth/checkpoint), not a rate limit: {}", bodySummary);
        }
    }

    private String firstHeader(Map<String, String> headers, String... names) {
        for (String name : names) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private boolean hasCookie(String name) {
        String value = cookies.get(name);
        return value != null && !value.isEmpty();
    }

    private void applyOptionalInstagramHeaders(Http request) {
        String webSessionId = cookies.get("web_session_id");
        if (webSessionId != null && !webSessionId.isEmpty()) {
            request.header("X-Web-Session-Id", webSessionId);
        }
        String mid = cookies.get("mid");
        if (mid != null && !mid.isEmpty()) {
            request.header("X-MID", mid);
        }
    }

    private String fetchUserIdFromProfileHtml(String username) throws IOException {
        String profileUrl = "https://www.instagram.com/" + username + "/";
        Response response = Http.url(profileUrl)
                .userAgent(INSTAGRAM_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.instagram.com/")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Upgrade-Insecure-Requests", "1")
                .cookies(cookies)
                .ignoreContentType()
                .response();

        if (response.statusCode() != 200) {
            throw new IOException("Profile page HTTP " + response.statusCode());
        }

        return parseUserIdFromProfileHtml(response.body(), username);
    }

    /** Package-private for unit tests. */
    String parseUserIdFromProfileHtml(String html, String username) throws IOException {
        if (html == null || html.isEmpty()) {
            throw new IOException("Empty profile page response");
        }

        Pattern[] patterns = {
                Pattern.compile("\"username\"\\s*:\\s*\"" + Pattern.quote(username)
                        + "\"[^}]{0,800}?\"id\"\\s*:\\s*\"(\\d+)\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
                Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"[^}]{0,800}?\"username\"\\s*:\\s*\""
                        + Pattern.quote(username) + "\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
                Pattern.compile("\"profile_id\"\\s*:\\s*\"(\\d+)\""),
                Pattern.compile("\"user_id\"\\s*:\\s*\"(\\d+)\""),
                Pattern.compile("\"userID\"\\s*:\\s*\"(\\d+)\""),
                Pattern.compile("profilePage_(\\d+)"),
                Pattern.compile("\"pk\"\\s*:\\s*\"?(\\d+)\"?[^}]{0,400}?\"username\"\\s*:\\s*\""
                        + Pattern.quote(username) + "\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
                Pattern.compile("logging_page_id\"\\s*:\\s*\"profilePage_(\\d+)\""),
        };

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        Pattern sharedDataPattern = Pattern.compile("window\\._sharedData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
        Matcher sharedDataMatcher = sharedDataPattern.matcher(html);
        if (sharedDataMatcher.find()) {
            JSONObject sharedData = new JSONObject(sharedDataMatcher.group(1));
            if (sharedData.has("entry_data")
                    && sharedData.getJSONObject("entry_data").has("ProfilePage")) {
                JSONArray pages = sharedData.getJSONObject("entry_data").getJSONArray("ProfilePage");
                if (pages.length() > 0) {
                    JSONObject user = pages.getJSONObject(0).getJSONObject("graphql").getJSONObject("user");
                    if (user.has("id")) {
                        return user.get("id").toString();
                    }
                }
            }
        }

        String lower = html.toLowerCase(Locale.ROOT);
        if (lower.contains("/accounts/login") || lower.contains("login_required")
                || lower.contains("requirelogin")) {
            throw loginRequiredException("Profile HTML for '" + username + "' is a login wall.");
        }

        throw new IOException("Could not extract user ID from profile HTML for " + username);
    }

    private String fetchUserIdFromProfile(String username) throws IOException {
        String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8);
        String profilePath = "/api/v1/users/web_profile_info/?username=" + encodedUsername;

        IOException lastException = null;

        try {
            String mobileProfileUrl = "https://i.instagram.com" + profilePath;
            Response response = executeInstagramApiRequest(mobileProfileUrl, null,
                    "fetching profile info for " + username, false);
            if (response.statusCode() == 200) {
                return parseUserIdFromProfileResponse(response.body());
            }
            lastException = new IOException("Failed to get profile info from i.instagram.com: HTTP " + response.statusCode());
            logger.debug("i.instagram.com profile lookup for {} returned HTTP {}", username, response.statusCode());
        } catch (IOException e) {
            lastException = e;
            logger.debug("i.instagram.com profile lookup failed for {}: {}", username, e.getMessage());
        }

        String webProfileUrl = "https://www.instagram.com" + profilePath;
        Response response = executeInstagramApiRequest(webProfileUrl, "https://www.instagram.com/" + username + "/",
                "fetching profile info for " + username, true);

        if (response.statusCode() != 200) {
            throw new IOException("Failed to get profile info: HTTP " + response.statusCode(), lastException);
        }

        return parseUserIdFromProfileResponse(response.body());
    }

    private String parseUserIdFromProfileResponse(String jsonText) throws IOException {
        JSONObject json = parseInstagramJsonBody(jsonText, "parsing profile info");
        if (!json.has("data") || !json.getJSONObject("data").has("user")) {
            throw new IOException("Invalid profile response - no user data found");
        }

        JSONObject user = json.getJSONObject("data").getJSONObject("user");
        String id = user.optString("id", "");
        if (id.isEmpty()) {
            Object pk = user.opt("pk");
            if (pk != null) {
                id = pk.toString();
            }
        }
        if (id == null || id.isEmpty()) {
            throw new IOException("No user ID found in profile response");
        }

        return id;
    }

    private String fetchUserIdFromTopSearch(String username) throws IOException {
        String searchUrl = "https://www.instagram.com/api/v1/web/search/topsearch/?context=blended&include_reel=true&query=" + URLEncoder.encode(username, StandardCharsets.UTF_8);
        Response response = executeInstagramApiRequest(searchUrl, "https://www.instagram.com/", "searching for user " + username);

        if (response.statusCode() != 200) {
            throw new IOException("Failed to search for user: HTTP " + response.statusCode());
        }

        JSONObject json = new JSONObject(response.body());
        if (!json.has("users")) {
            throw new IOException("Invalid topsearch response - no users array");
        }

        JSONArray usersArray = json.getJSONArray("users");
        for (int i = 0; i < usersArray.length(); i++) {
            JSONObject entry = usersArray.getJSONObject(i);
            if (!entry.has("user")) {
                continue;
            }

            JSONObject userObject = entry.getJSONObject("user");
            String candidateUsername = userObject.optString("username", "");
            if (!candidateUsername.equalsIgnoreCase(username)) {
                continue;
            }

            String id = userObject.optString("id", "");
            if (id == null || id.isEmpty()) {
                id = userObject.optString("pk", "");
            }

            if (id != null && !id.isEmpty()) {
                return id;
            }
        }

        throw new IOException("User " + username + " not found in topsearch response");
    }

    private void extractFirefoxCookies() {
        mergeConfigCookies();

        if (!FirefoxCookieUtils.isSQLiteDriverAvailable()) {
            logger.warn("SQLite JDBC driver not found. Firefox cookie authentication will not be available.");
            logCookieDiagnostics();
            return;
        }

        Map<String, String> bestCookies = null;
        Path bestProfile = null;
        boolean bestHasSession = false;

        for (Path profilePath : FirefoxCookieUtils.discoverFirefoxProfiles()) {
            Map<String, String> profileCookies = FirefoxCookieUtils.readCookiesFromProfile(profilePath,
                    Arrays.asList("%instagram.com", "%.instagram.com"));
            if (profileCookies.isEmpty()) {
                continue;
            }

            boolean hasSession = profileCookies.containsKey("sessionid")
                    && profileCookies.get("sessionid") != null
                    && !profileCookies.get("sessionid").isEmpty();

            if (bestCookies == null
                    || (hasSession && !bestHasSession)
                    || (hasSession == bestHasSession && profileCookies.size() > bestCookies.size())) {
                bestCookies = profileCookies;
                bestProfile = profilePath;
                bestHasSession = hasSession;
            }

            // Prefer the first profile that has a real login session.
            if (hasSession) {
                break;
            }
        }

        if (bestCookies != null) {
            cookies.putAll(bestCookies);
            if (bestCookies.containsKey("csrftoken")) {
                this.csrftoken = bestCookies.get("csrftoken");
            }
            logger.info("Loaded {} Instagram cookies from Firefox profile {} (sessionid={})",
                    bestCookies.size(),
                    bestProfile != null ? bestProfile.getFileName() : "?",
                    bestHasSession);
        } else {
            logger.warn("No Instagram cookies found in Firefox profiles.");
        }

        // Config cookies win so users can override a stale Firefox session.
        mergeConfigCookies();
        logCookieDiagnostics();
    }

    private void mergeConfigCookies() {
        for (String domain : Arrays.asList("www.instagram.com", "instagram.com")) {
            String configCookies = Utils.getConfigString("cookies." + domain, "");
            if (configCookies == null || configCookies.isBlank()) {
                continue;
            }
            cookies.putAll(RipUtils.getCookiesFromString(configCookies.trim()));
            logger.info("Loaded Instagram cookies from config (cookies.{})", domain);
        }
    }

    private void logCookieDiagnostics() {
        logger.info("Instagram cookie check: sessionid={} csrftoken={} ds_user_id={} ({} cookies total)",
                hasCookie("sessionid"), hasCookie("csrftoken"), hasCookie("ds_user_id"), cookies.size());
        if (!hasCookie("sessionid")) {
            if (hasCookie("ds_user_id") || hasCookie("csrftoken")) {
                logger.warn("Found Instagram cookies without sessionid (stale/incomplete session, or "
                        + "Firefox still open so cookies.sqlite-wal was not flushed). "
                        + "Log into Instagram in Firefox and fully quit the browser, then retry.");
            } else {
                logger.warn("No sessionid cookie found. Log into Instagram in Firefox and fully quit the browser "
                        + "before ripping (especially private profiles / batch rips).");
            }
        }
        if (!hasCookie("csrftoken")) {
            logger.warn("No csrftoken cookie found. Open instagram.com in Firefox once to refresh cookies.");
        }
    }    
    @Override
    protected List<String> getURLsFromJSON(JSONObject json) {
        List<String> urls = new ArrayList<>();

        boolean limitActive = downloadLimitTracker.isEnabled();
        int remainingSlots = limitActive ? downloadLimitTracker.getAvailableSlots() : Integer.MAX_VALUE;

        if (downloadLimitTracker.isLimitReached()) {
            maxDownloadLimitReached = true;
            hasNextPage = false;
            return urls;
        }

        try {
            if (!json.has("data") || !json.getJSONObject("data").has("user")) {
                throw new RuntimeException("Invalid JSON response format - missing data or user object");
            }

            JSONObject user = json.getJSONObject("data").getJSONObject("user");
            if (!user.has("edge_owner_to_timeline_media")) {
                throw new RuntimeException("Invalid JSON response format - missing timeline media");
            }

            JSONObject timelineMedia = user.getJSONObject("edge_owner_to_timeline_media");
            JSONArray edges = timelineMedia.getJSONArray("edges");
            
            logger.debug("Found " + edges.length() + " media items");
            
            for (int i = 0; i < edges.length(); i++) {
                if (limitActive && remainingSlots <= 0) {
                    break;
                }
                JSONObject edge = edges.getJSONObject(i).getJSONObject("node");

                String typename = edge.getString("__typename");
                switch (typename) {
                    case "GraphImage":
                        // Single image
                        urls.add(edge.getString("display_url"));
                        if (limitActive) {
                            remainingSlots--;
                        }
                        break;
                    case "GraphSidecar":
                        // Multiple images
                        JSONArray sidecarEdges = edge.getJSONObject("edge_sidecar_to_children").getJSONArray("edges");
                        for (int j = 0; j < sidecarEdges.length(); j++) {
                            if (limitActive && remainingSlots <= 0) {
                                break;
                            }
                            JSONObject node = sidecarEdges.getJSONObject(j).getJSONObject("node");
                            urls.add(node.getString("display_url"));
                            if (limitActive) {
                                remainingSlots--;
                            }
                        }
                        break;
                    case "GraphVideo":
                        // Video
                        if (limitActive && remainingSlots <= 0) {
                            break;
                        }
                        if (edge.has("video_url")) {
                            urls.add(edge.getString("video_url"));
                        } else {
                            // Fallback to thumbnail if video URL is not available
                            urls.add(edge.getString("display_url"));
                        }
                        if (limitActive) {
                            remainingSlots--;
                        }
                        break;
                    default:
                        logger.warn("Unknown Instagram media type: " + typename);
                        break;
                }
            }
            
            // Handle pagination
            JSONObject pageInfo = timelineMedia.optJSONObject("page_info");
            if (pageInfo != null && pageInfo.optBoolean("has_next_page", false)
                    && pageInfo.has("end_cursor") && !pageInfo.isNull("end_cursor")) {
                this.endCursor = pageInfo.get("end_cursor").toString();
                this.hasNextPage = this.endCursor != null && !this.endCursor.isEmpty();
            } else {
                this.hasNextPage = false;
            }
            
        } catch (JSONException e) {
            logger.error("Error parsing JSON response: " + e.getMessage());
            logger.debug("JSON data: " + json.toString(2));
            throw new RuntimeException("Error parsing Instagram response", e);
        }

        return urls;
    }

    @Override
    protected JSONObject getNextPage(JSONObject json) throws IOException {
        if (downloadLimitTracker.isLimitReached()) {
            maxDownloadLimitReached = true;
            hasNextPage = false;
            return null;
        }

        if (!hasNextPage) {
            return null;
        }

        if (isPopularUrl(url)) {
            try {
                return convertKeywordSearchToTimeline(fetchKeywordSearchPage(getPopularKeyword(url), endCursor));
            } catch (IOException e) {
                logger.warn("Instagram popular search pagination stopped for {}: {}",
                        getPopularKeyword(url), e.getMessage());
                sendUpdate(RipStatusMessage.STATUS.DOWNLOAD_WARN,
                        "Instagram popular search pagination stopped: " + e.getMessage());
                hasNextPage = false;
                return null;
            }
        }

        String username = getUsername(url);
        if (isReelsUrl(url)) {
            return getClipsUserPage(username, endCursor);
        }

        IOException lastFailure = null;

        if (hasCookie("sessionid")) {
            try {
                return getFeedUserPage(username, endCursor);
            } catch (IOException e) {
                lastFailure = e;
                logger.warn("Feed API pagination failed for {}: {}. Trying GraphQL fallback.",
                        username, e.getMessage());
            }
        }

        try {
            return fetchGraphqlTimelinePage(username, endCursor);
        } catch (IOException e) {
            if (lastFailure != null) {
                e.addSuppressed(lastFailure);
            }
            sendUpdate(RipStatusMessage.STATUS.DOWNLOAD_WARN,
                    "Instagram pagination stopped: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Fetches one page of {@code /popular/{query}} results via
     * {@code PolarisKeywordSearchExplorePageRelayQuery}.
     */
    private JSONObject fetchKeywordSearchPage(String keyword, String afterCursor) throws IOException {
        if (popularSearchSessionId == null || popularSearchSessionId.isEmpty()) {
            popularSearchSessionId = UUID.randomUUID().toString();
        }

        JSONObject variables = new JSONObject();
        variables.put("query", keyword);
        variables.put("search_session_id", popularSearchSessionId);
        variables.put("serp_session_id", popularSearchSessionId);
        if (afterCursor != null && !afterCursor.isEmpty()) {
            variables.put("cursor", afterCursor);
        }

        Map<String, String> form = new HashMap<>();
        form.put("variables", variables.toString());
        form.put("doc_id", GRAPHQL_DOC_ID_KEYWORD_SEARCH);
        form.put("server_timestamps", "true");
        form.put("fb_api_req_friendly_name", GRAPHQL_FRIENDLY_NAME_KEYWORD_SEARCH);

        String requestUrl = "https://www.instagram.com/api/graphql";
        String referer = "https://www.instagram.com/popular/" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "/";
        logger.debug("Fetching Instagram keyword search doc_id={} query={} after={}",
                GRAPHQL_DOC_ID_KEYWORD_SEARCH, keyword, afterCursor);

        Http request = Http.url(requestUrl)
                .method(Method.POST)
                .data(form)
                .userAgent(INSTAGRAM_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-IG-App-ID", INSTAGRAM_APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", cookies.getOrDefault("csrftoken", ""))
                .header("X-FB-LSD", cookies.getOrDefault("lsd", ""))
                .header("X-FB-Friendly-Name", GRAPHQL_FRIENDLY_NAME_KEYWORD_SEARCH)
                .header("Origin", "https://www.instagram.com")
                .header("Referer", referer)
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .cookies(cookies)
                .ignoreContentType()
                .ignoreHttpErrors();
        applyOptionalInstagramHeaders(request);
        Response response = request.response();

        int statusCode = response.statusCode();
        String body = response.body();
        if (statusCode == 429) {
            throw new IOException("Rate limited by Instagram keyword search.");
        }
        if (statusCode != 200) {
            throw new IOException("Keyword search HTTP " + statusCode + " for '" + keyword + "'");
        }

        return parseInstagramJsonBody(body, "keyword search for " + keyword);
    }

    /**
     * Reshapes {@code xdt_fbsearch__top_serp_graphql} into the GraphQL timeline
     * structure expected by {@link #getURLsFromJSON(JSONObject)}.
     * Package-private for unit tests.
     */
    JSONObject convertKeywordSearchToTimeline(JSONObject json) throws IOException {
        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            throw new IOException("Keyword search response missing data object");
        }
        JSONObject serp = data.optJSONObject("xdt_fbsearch__top_serp_graphql");
        if (serp == null) {
            throw new IOException("Keyword search response missing xdt_fbsearch__top_serp_graphql");
        }

        JSONArray edgesOut = new JSONArray();
        JSONArray edgesIn = serp.optJSONArray("edges");
        if (edgesIn != null) {
            for (int i = 0; i < edgesIn.length(); i++) {
                JSONObject edge = edgesIn.optJSONObject(i);
                if (edge == null) {
                    continue;
                }
                JSONObject unit = edge.optJSONObject("node");
                if (unit == null || !"XDTTopSerpMediaGridUnit".equals(unit.optString("__typename"))) {
                    continue;
                }
                JSONArray items = unit.optJSONArray("items");
                if (items == null) {
                    continue;
                }
                for (int j = 0; j < items.length(); j++) {
                    JSONObject media = items.optJSONObject(j);
                    if (media == null) {
                        continue;
                    }
                    JSONObject node = mediaToNode(media);
                    if (node == null) {
                        continue;
                    }
                    JSONObject outEdge = new JSONObject();
                    outEdge.put("node", node);
                    edgesOut.put(outEdge);
                }
            }
        }

        JSONObject pageInfo = serp.optJSONObject("page_info");
        if (pageInfo == null) {
            pageInfo = new JSONObject();
            pageInfo.put("has_next_page", false);
        }

        JSONObject timelineMedia = new JSONObject();
        timelineMedia.put("edges", edgesOut);
        timelineMedia.put("page_info", pageInfo);
        JSONObject user = new JSONObject();
        user.put("edge_owner_to_timeline_media", timelineMedia);
        JSONObject dataOut = new JSONObject();
        dataOut.put("user", user);
        JSONObject result = new JSONObject();
        result.put("data", dataOut);
        return result;
    }

    /**
     * Paginates profile posts via Instagram's Polaris GraphQL doc_id queries.
     * Logged-in sessions use the private-API-shaped feed connection; anonymous
     * sessions use the classic timeline edge.
     */
    private JSONObject fetchGraphqlTimelinePage(String username, String afterCursor) throws IOException {
        String userId = getUserID(username);
        boolean loggedIn = hasCookie("sessionid");
        String docId = loggedIn ? GRAPHQL_DOC_ID_FEED_TIMELINE : GRAPHQL_DOC_ID_PROFILE_TIMELINE;

        JSONObject variables = new JSONObject();
        if (loggedIn) {
            JSONObject data = new JSONObject();
            data.put("count", 12);
            data.put("include_relationship_info", true);
            data.put("latest_besties_reel_media", true);
            data.put("latest_reel_media", true);
            variables.put("data", data);
            variables.put("username", username);
        } else {
            variables.put("id", userId);
        }
        variables.put("after", afterCursor != null ? afterCursor : JSONObject.NULL);
        variables.put("before", JSONObject.NULL);
        variables.put("first", 12);
        variables.put("last", JSONObject.NULL);
        variables.put("__relay_internal__pv__PolarisFeedShareMenurelayprovider", false);

        Map<String, String> form = new HashMap<>();
        form.put("variables", variables.toString());
        form.put("doc_id", docId);
        form.put("server_timestamps", "true");

        String requestUrl = "https://www.instagram.com/graphql/query";
        logger.debug("Fetching Instagram GraphQL timeline doc_id={} after={}", docId, afterCursor);

        Http request = Http.url(requestUrl)
                .method(Method.POST)
                .data(form)
                .userAgent(INSTAGRAM_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("X-IG-App-ID", INSTAGRAM_APP_ID)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRFToken", cookies.getOrDefault("csrftoken", ""))
                .header("X-FB-LSD", cookies.getOrDefault("lsd", ""))
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/" + username + "/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .cookies(cookies)
                .ignoreContentType()
                .ignoreHttpErrors();
        applyOptionalInstagramHeaders(request);
        Response response = request.response();

        int statusCode = response.statusCode();
        String body = response.body();
        if (statusCode == 429) {
            throw new IOException("Rate limited by Instagram GraphQL pagination.");
        }
        if (statusCode != 200) {
            throw new IOException("GraphQL timeline HTTP " + statusCode + " for " + username);
        }

        JSONObject json = parseInstagramJsonBody(body, "GraphQL timeline for " + username);
        return normalizeGraphqlTimeline(json, loggedIn);
    }

    /**
     * Normalizes a GraphQL timeline payload into the
     * {@code data.user.edge_owner_to_timeline_media} shape used by {@link #getURLsFromJSON}.
     * Package-private for unit tests.
     */
    JSONObject normalizeGraphqlTimeline(JSONObject json, boolean loggedIn) throws IOException {
        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            throw new IOException("GraphQL timeline response missing data object");
        }

        // Login walls / blocked anonymous queries often return {"data":{"user":null}}.
        // optJSONObject returns null for JSON null; has("user") is still true.
        JSONObject userNode = data.optJSONObject("user");
        if (data.has("user") && userNode == null) {
            throw new IOException("Instagram returned empty user data — log into Firefox and fully quit "
                    + "so sessionid is available. GraphQL timeline had null user"
                    + (loggedIn ? " despite sessionid." : " (no sessionid cookie)."));
        }

        if (!loggedIn && userNode != null) {
            if (userNode.has("edge_owner_to_timeline_media")) {
                JSONObject result = new JSONObject();
                result.put("data", data);
                return result;
            }
        }

        JSONObject connection = data.optJSONObject("xdt_api__v1__feed__user_timeline_graphql_connection");
        if (connection == null) {
            // Some logged-out responses still nest under user even when we used the feed doc_id.
            if (userNode != null && userNode.has("edge_owner_to_timeline_media")) {
                JSONObject result = new JSONObject();
                result.put("data", data);
                return result;
            }
            throw new IOException("GraphQL timeline response missing timeline connection");
        }

        JSONArray edgesIn = connection.optJSONArray("edges");
        if (edgesIn == null) {
            edgesIn = new JSONArray();
        }
        JSONArray edgesOut = new JSONArray();
        for (int i = 0; i < edgesIn.length(); i++) {
            JSONObject edge = edgesIn.getJSONObject(i);
            JSONObject nodeMedia = edge.optJSONObject("node");
            if (nodeMedia == null) {
                continue;
            }
            JSONObject node = mediaToNode(nodeMedia);
            if (node == null) {
                // Classic GraphImage/GraphVideo nodes already have display_url / video_url.
                if (nodeMedia.has("display_url") || nodeMedia.has("video_url")
                        || nodeMedia.has("__typename")) {
                    node = nodeMedia;
                } else {
                    continue;
                }
            }
            JSONObject outEdge = new JSONObject();
            outEdge.put("node", node);
            edgesOut.put(outEdge);
        }

        JSONObject pageInfo = connection.optJSONObject("page_info");
        if (pageInfo == null) {
            pageInfo = new JSONObject();
            pageInfo.put("has_next_page", false);
        }

        JSONObject timelineMedia = new JSONObject();
        timelineMedia.put("edges", edgesOut);
        timelineMedia.put("page_info", pageInfo);
        JSONObject user = new JSONObject();
        user.put("edge_owner_to_timeline_media", timelineMedia);
        JSONObject dataOut = new JSONObject();
        dataOut.put("user", user);
        JSONObject result = new JSONObject();
        result.put("data", dataOut);
        return result;
    }

    @Override
    public void downloadCompleted(URL url, java.nio.file.Path saveAs) {
        super.downloadCompleted(url, saveAs);
        handleSuccessfulDownload(url);
    }

    @Override
    public void downloadExists(URL url, java.nio.file.Path file) {
        super.downloadExists(url, file);
        if (downloadLimitTracker.isEnabled()) {
            downloadLimitTracker.onFailure(url);
        } else {
            handleSuccessfulDownload(url);
        }
    }

    @Override
    public void downloadErrored(URL url, String reason) {
        downloadLimitTracker.onFailure(url);
        super.downloadErrored(url, reason);
    }

    @Override
    public boolean hasASAPRipping() {
        return maxDownloadLimitReached;
    }

    private void handleSuccessfulDownload(URL url) {
        if (downloadLimitTracker.onSuccess(url)) {
            maxDownloadLimitReached = true;
            hasNextPage = false;
            if (downloadLimitTracker.shouldNotifyLimitReached()) {
                String message = "Reached max download limit of " + maxDownloads + ". Stopping.";
                logger.info(message);
                sendUpdate(RipStatusMessage.STATUS.DOWNLOAD_COMPLETE_HISTORY, message);
            }
        }
    }

    private static class InstagramBotBlockedException extends IOException {
        InstagramBotBlockedException(String message) {
            super(message);
        }
    }
}
