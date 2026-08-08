package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

/**
 * Ripper for elitebabes.com.
 *
 * Gallery pages hold the images in a fancybox list; video posts hold a player instead. Listing
 * pages (models, collections, tags, ...) hold no media of their own, so they are expanded into
 * the queue. Those listings only render their first 20 entries server side and fetch the rest
 * from a "gridapi" endpoint, whose URL and page count are published in an inline script.
 */
public class EliteBabesRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(EliteBabesRipper.class);

    private static final Pattern SRCSET_ENTRY = Pattern.compile("(\\S+)\\s+(\\d+)w");
    private static final Pattern API_URL = Pattern.compile("apiUrl\\s*=\\s*'([^']*)'");
    private static final Pattern TOTAL_PAGES = Pattern.compile("totalPages\\s*=\\s*parseInt\\('(\\d+)'\\)");

    /** Guards against runaway paging if the site ever reports a nonsense page count. */
    private static final int MAX_LISTING_PAGES = 1000;

    public EliteBabesRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "elitebabes";
    }

    @Override
    public String getDomain() {
        return "elitebabes.com";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        String path = url.getPath().replaceAll("^/+", "").replaceAll("/+$", "");
        if (path.isEmpty()) {
            throw new MalformedURLException("Expected an elitebabes gallery or listing URL, got " + url);
        }
        return path.replace('/', '_');
    }

    @Override
    public Document getFirstPage() throws IOException {
        return Http.url(url).referrer("https://" + url.getHost() + "/").get();
    }

    @Override
    protected boolean hasQueueSupport() {
        return true;
    }

    @Override
    public boolean pageContainsAlbums(URL url) {
        try {
            return !isMediaPage(getCachedFirstPage());
        } catch (IOException | URISyntaxException e) {
            logger.warn("Failed to load " + url + " while checking for albums", e);
            return false;
        }
    }

    /**
     * A gallery shows its images through fancybox links, a video post shows a player. Listing
     * pages have neither, only thumbnails linking to other posts.
     */
    private boolean isMediaPage(Document doc) {
        return !doc.select("a[data-fancybox]").isEmpty() || !doc.select("video > source[src]").isEmpty();
    }

    @Override
    public List<String> getAlbumsToQueue(Document doc) {
        Set<String> albums = new LinkedHashSet<>();
        addAlbumLinks(doc, albums);

        String apiUrl = findInScripts(doc, API_URL);
        int totalPages = Math.min(parseIntOrDefault(findInScripts(doc, TOTAL_PAGES), 1), MAX_LISTING_PAGES);
        if (apiUrl != null && !apiUrl.isEmpty()) {
            for (int page = 2; page <= totalPages && !isStopped(); page++) {
                String pageUrl = apiUrl + "&mpage=" + page;
                try {
                    sleep(500);
                    int before = albums.size();
                    addAlbumLinks(Http.url(pageUrl).referrer(this.url).get(), albums);
                    if (albums.size() == before) {
                        logger.debug("No new albums on " + pageUrl + ", stopping");
                        break;
                    }
                } catch (IOException e) {
                    logger.warn("Failed to load " + pageUrl, e);
                    break;
                }
            }
        }

        logger.info("Queueing " + albums.size() + " albums from " + this.url);
        return new ArrayList<>(albums);
    }

    /**
     * Every thumbnail links to its post twice: once from the image and once from the hover
     * overlay. Only the overlay link is guaranteed to stay on elitebabes -- for video posts the
     * image links out to the partner site hosting the video.
     */
    private void addAlbumLinks(Document doc, Set<String> albums) {
        for (Element figure : doc.select("li > figure")) {
            Element link = figure.selectFirst("div.img-overlay p a[href]");
            if (link == null) {
                link = figure.selectFirst("a[href]");
            }
            if (link == null) {
                continue;
            }
            String href = link.attr("abs:href");
            if (isAlbumUrl(href)) {
                albums.add(href);
            }
        }
    }

    private boolean isAlbumUrl(String href) {
        if (href.isEmpty()) {
            return false;
        }
        try {
            URL parsed = new URL(href);
            if (!parsed.getHost().endsWith(getDomain())) {
                return false;
            }
            String path = parsed.getPath().replaceAll("^/+", "").replaceAll("/+$", "");
            // Posts live at the site root; anything nested is a listing or a profile page.
            return !path.isEmpty() && !path.contains("/");
        } catch (MalformedURLException e) {
            return false;
        }
    }

    @Override
    public List<String> getURLsFromPage(Document doc) {
        List<String> urls = new ArrayList<>();
        for (Element image : doc.select("a[data-fancybox]")) {
            String best = largestFromSrcset(image.attr("data-srcset"));
            if (best.isEmpty()) {
                best = image.attr("abs:href");
            }
            if (!best.isEmpty()) {
                urls.add(best);
            }
        }
        if (urls.isEmpty()) {
            // Video posts list their sources highest quality first.
            Element source = doc.selectFirst("video > source[src]");
            if (source != null) {
                // The query string only asks the CDN to throttle the stream.
                urls.add(source.attr("abs:src").replaceAll("\\?.*$", ""));
            }
        }
        return urls;
    }

    /**
     * Picks the highest resolution from a srcset. Not every image is available in every size, so
     * the widths offered per image are the only reliable source.
     */
    private String largestFromSrcset(String srcset) {
        String best = "";
        int bestWidth = -1;
        for (String candidate : srcset.split(",")) {
            Matcher matcher = SRCSET_ENTRY.matcher(candidate.trim());
            if (matcher.matches()) {
                int width = Integer.parseInt(matcher.group(2));
                if (width > bestWidth) {
                    bestWidth = width;
                    best = matcher.group(1);
                }
            }
        }
        return best;
    }

    private String findInScripts(Document doc, Pattern pattern) {
        for (Element script : doc.select("script")) {
            Matcher matcher = pattern.matcher(script.data());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private int parseIntOrDefault(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index), "", this.url.toExternalForm(), null);
    }
}
