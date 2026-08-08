package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.rarchives.ripme.utils.Utils;

/**
 * Ripper for elitebabes.com.
 *
 * Gallery pages hold their images in a fancybox list; video posts hold a player instead. Listing
 * pages (models, collections, tags, ...) hold no media of their own, so they are ripped by walking
 * every gallery they link to, one gallery per page of the rip. Those listings only render their
 * first 20 entries server side and fetch the rest from a "gridapi" endpoint, whose URL and page
 * count are published in an inline script.
 *
 * Everything a listing produces lands in one folder. The CDN names files per set rather than
 * globally ("0001-01_1200.jpg" occurs in every set numbered 0001), so filenames from a listing are
 * prefixed with their gallery slug to keep sets from overwriting each other.
 */
public class EliteBabesRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(EliteBabesRipper.class);

    private static final Pattern SRCSET_ENTRY = Pattern.compile("(\\S+)\\s+(\\d+)w");
    private static final Pattern API_URL = Pattern.compile("apiUrl\\s*=\\s*'([^']*)'");
    private static final Pattern TOTAL_PAGES = Pattern.compile("totalPages\\s*=\\s*parseInt\\('(\\d+)'\\)");

    /** Guards against runaway paging if the site ever reports a nonsense page count. */
    private static final int MAX_LISTING_PAGES = 1000;

    /**
     * First path segment of the site's listings. An unrecognised prefix is simply kept in the folder
     * name, which is harmless -- posts themselves always live at the site root.
     */
    private static final Set<String> LISTING_PREFIXES =
            Set.of("model", "model-tag", "collections", "collection", "tag", "category");

    /** Longest full path Windows accepts, and the per-name limit everywhere else. */
    private static final int MAX_WINDOWS_PATH = 259;
    private static final int MAX_FILE_NAME = 255;

    /** Galleries from a listing that have not been ripped yet. */
    private final List<String> pendingGalleries = new ArrayList<>();
    private boolean listingMode;
    private String currentGallerySlug = "";

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
        String path = trimSlashes(url.getPath());
        if (path.isEmpty()) {
            throw new MalformedURLException("Expected an elitebabes gallery or listing URL, got " + url);
        }
        return path.replace('/', '_');
    }

    /**
     * Names the folder after the subject alone, so "/model/luna-pica/" and any of its sort orders
     * all rip into "elitebabes_luna-pica". The GID keeps the full path, so distinct listings still
     * count as distinct rips.
     */
    @Override
    public String getAlbumTitle(URL url) throws MalformedURLException {
        List<String> segments = new ArrayList<>(Arrays.asList(trimSlashes(url.getPath()).split("/")));
        if (segments.size() > 1 && LISTING_PREFIXES.contains(segments.get(0).toLowerCase())) {
            segments.remove(0);
        }
        // Sort orders are a view of the same listing, not a different subject.
        int sort = segments.indexOf("sort");
        if (sort >= 0) {
            segments = segments.subList(0, sort);
        }
        if (segments.isEmpty()) {
            return getHost() + "_" + getGID(url);
        }
        return getHost() + "_" + String.join("_", segments);
    }

    /**
     * For a listing this returns the first gallery rather than the listing itself, so the rip loop
     * can treat one gallery as one page.
     */
    @Override
    public Document getFirstPage() throws IOException {
        Document doc = fetch(url.toExternalForm());
        if (isMediaPage(doc)) {
            return doc;
        }

        listingMode = true;
        pendingGalleries.addAll(collectGalleryUrls(doc));
        logger.info("Found " + pendingGalleries.size() + " galleries in " + url);

        Document firstGallery = nextGalleryWithMedia();
        if (firstGallery == null) {
            throw new IOException("Found no galleries with media at " + url);
        }
        return firstGallery;
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        if (!listingMode) {
            return null;
        }
        Document next = nextGalleryWithMedia();
        if (next == null) {
            throw new IOException("No more galleries");
        }
        return next;
    }

    /**
     * The rip loop aborts the whole rip when a page yields no media, so galleries that fail to load
     * or hold nothing downloadable are skipped here instead.
     */
    private Document nextGalleryWithMedia() {
        while (!pendingGalleries.isEmpty() && !isStopped()) {
            String gallery = pendingGalleries.remove(0);
            try {
                sleep(500);
                Document doc = fetch(gallery);
                if (!mediaUrls(doc).isEmpty()) {
                    return doc;
                }
                logger.warn("No media found in " + gallery + ", skipping");
            } catch (IOException e) {
                logger.warn("Failed to load " + gallery + ", skipping: " + e.getMessage());
            }
        }
        return null;
    }

    private Document fetch(String pageUrl) throws IOException {
        return Http.url(pageUrl).referrer("https://" + url.getHost() + "/").get();
    }

    /**
     * A gallery shows its images through fancybox links, a video post shows a player. Listing pages
     * have neither, only thumbnails linking to other posts.
     */
    private boolean isMediaPage(Document doc) {
        return !doc.select("a[data-fancybox]").isEmpty() || !doc.select("video > source[src]").isEmpty();
    }

    /** Collects every gallery a listing links to, following the grid API pagination. */
    public List<String> collectGalleryUrls(Document listing) {
        Set<String> galleries = new LinkedHashSet<>();
        addGalleryLinks(listing, galleries);

        String apiUrl = findInScripts(listing, API_URL);
        int totalPages = Math.min(parseIntOrDefault(findInScripts(listing, TOTAL_PAGES), 1), MAX_LISTING_PAGES);
        if (apiUrl != null && !apiUrl.isEmpty()) {
            for (int page = 2; page <= totalPages && !isStopped(); page++) {
                String pageUrl = apiUrl + "&mpage=" + page;
                try {
                    sleep(500);
                    int before = galleries.size();
                    addGalleryLinks(fetch(pageUrl), galleries);
                    if (galleries.size() == before) {
                        logger.debug("No new galleries on " + pageUrl + ", stopping");
                        break;
                    }
                } catch (IOException e) {
                    logger.warn("Failed to load " + pageUrl, e);
                    break;
                }
            }
        }
        return new ArrayList<>(galleries);
    }

    /**
     * Every thumbnail links to its post twice: once from the image and once from the hover overlay.
     * Only the overlay link is guaranteed to stay on elitebabes -- for video posts the image links
     * out to the partner site hosting the video.
     */
    private void addGalleryLinks(Document doc, Set<String> galleries) {
        for (Element figure : doc.select("li > figure")) {
            Element link = figure.selectFirst("div.img-overlay p a[href]");
            if (link == null) {
                link = figure.selectFirst("a[href]");
            }
            if (link == null) {
                continue;
            }
            String href = link.attr("abs:href");
            if (isGalleryUrl(href)) {
                galleries.add(href);
            }
        }
    }

    private boolean isGalleryUrl(String href) {
        if (href.isEmpty()) {
            return false;
        }
        try {
            URL parsed = new URL(href);
            if (!parsed.getHost().endsWith(getDomain())) {
                return false;
            }
            // Posts live at the site root; anything nested is a listing or a profile page.
            String path = trimSlashes(parsed.getPath());
            return !path.isEmpty() && !path.contains("/");
        } catch (MalformedURLException e) {
            return false;
        }
    }

    @Override
    public List<String> getURLsFromPage(Document doc) {
        currentGallerySlug = slugFrom(doc.location());
        return mediaUrls(doc);
    }

    private List<String> mediaUrls(Document doc) {
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

    private static String trimSlashes(String path) {
        return path.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static String slugFrom(String pageUrl) {
        String path = trimSlashes(pageUrl.replaceFirst("^https?://[^/]+", "").replaceAll("[?#].*$", ""));
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? path : path.substring(lastSlash + 1);
    }

    /**
     * Builds the saved filename for an image ripped from a listing, qualifying the CDN's per-set
     * filename with the gallery slug. Overlong slugs are cut down to fit the filesystem, keeping a
     * hash of the full slug so two sets can never collapse onto the same name.
     *
     * @return the filename to save as, or null to let the default URL-derived name be used
     */
    public String fileNameFor(String gallerySlug, URL imageUrl, String prefix) {
        String base = slugFrom(imageUrl.toExternalForm());
        String extension = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            extension = base.substring(dot);
            base = base.substring(0, dot);
        }

        int budget = MAX_FILE_NAME;
        if (Utils.isWindows() && workingDir != null) {
            budget = Math.min(budget, MAX_WINDOWS_PATH - workingDir.getAbsolutePath().length() - 1);
        }
        // Room for the prefix, the separator and the CDN's own name.
        budget -= prefix.length() + base.length() + extension.length() + 1;

        String slug = gallerySlug;
        if (budget < slug.length()) {
            slug = shortenSlug(slug, budget);
        }
        if (slug.isEmpty()) {
            logger.warn("Save path is too long to qualify " + base + " with its gallery name");
            return null;
        }
        return slug + "_" + base + extension;
    }

    private String shortenSlug(String slug, int budget) {
        String hash = String.format("%06x", slug.hashCode() & 0xFFFFFF);
        if (budget < hash.length()) {
            return "";
        }
        if (budget == hash.length()) {
            return hash;
        }
        return slug.substring(0, budget - hash.length() - 1) + "_" + hash;
    }

    @Override
    public void downloadURL(URL url, int index) {
        String prefix = getPrefix(index);
        // Single galleries already sit in a folder named after themselves.
        String fileName = listingMode ? fileNameFor(currentGallerySlug, url, prefix) : null;
        addURLToDownload(url, prefix, "", this.url.toExternalForm(), null, fileName);
    }
}
