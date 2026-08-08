package com.rarchives.ripme.tst.ripper.rippers;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import com.rarchives.ripme.ripper.rippers.EliteBabesRipper;
import com.rarchives.ripme.utils.Http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

public class EliteBabesRipperTest extends RippersTest {

    private static final String GALLERY = "https://www.elitebabes.com/metart-luna-pica-in-pink-tulle-107265/";
    private static final String MODEL = "https://www.elitebabes.com/model/luna-pica/";
    private static final String COLLECTION = "https://www.elitebabes.com/collections/maos/";

    @Test
    public void testGetGID() throws IOException, URISyntaxException {
        Assertions.assertEquals("metart-luna-pica-in-pink-tulle-107265", gidOf(GALLERY));
        Assertions.assertEquals("model_luna-pica", gidOf(MODEL));
        Assertions.assertEquals("collections_maos", gidOf(COLLECTION));
        Assertions.assertEquals("model_luna-pica_sort_latest", gidOf(MODEL + "sort/latest/"));
    }

    /** Listings are named after their subject so sort orders of one model share a folder. */
    @Test
    public void testGetAlbumTitle() throws IOException, URISyntaxException {
        Assertions.assertEquals("elitebabes_luna-pica", titleOf(MODEL));
        Assertions.assertEquals("elitebabes_luna-pica", titleOf(MODEL + "sort/latest/"));
        Assertions.assertEquals("elitebabes_maos", titleOf(COLLECTION));
        Assertions.assertEquals("elitebabes_brunette", titleOf("https://www.elitebabes.com/tag/brunette/"));
        // A gallery keeps its own name; posts live at the site root.
        Assertions.assertEquals("elitebabes_metart-luna-pica-in-pink-tulle-107265", titleOf(GALLERY));
        // An unrecognised prefix is kept rather than guessed at.
        Assertions.assertEquals("elitebabes_whatever_thing", titleOf("https://www.elitebabes.com/whatever/thing/"));
    }

    private String titleOf(String url) throws IOException, URISyntaxException {
        URL parsed = new URI(url).toURL();
        return new EliteBabesRipper(parsed).getAlbumTitle(parsed);
    }

    @Test
    public void testCanRip() throws IOException, URISyntaxException {
        URL url = new URI(GALLERY).toURL();
        Assertions.assertTrue(new EliteBabesRipper(url).canRip(url));
    }

    /** Listing rips are flat, so the gallery slug is what keeps sets from overwriting each other. */
    @Test
    public void testFileNameIsQualifiedByGallery() throws IOException, URISyntaxException {
        EliteBabesRipper ripper = new EliteBabesRipper(new URI(MODEL).toURL());
        URL image = new URI("https://cdn.elitebabes.com/content/250591/0001-01_1200.jpg").toURL();

        Assertions.assertEquals("femjoy-luna-moonie-in-tantalizing-107465_0001-01_1200.jpg",
                ripper.fileNameFor("femjoy-luna-moonie-in-tantalizing-107465", image, ""));
    }

    @Test
    public void testLongGalleryNameIsShortenedUniquely() throws IOException, URISyntaxException {
        EliteBabesRipper ripper = new EliteBabesRipper(new URI(MODEL).toURL());
        URL image = new URI("https://cdn.elitebabes.com/content/250591/0001-01_1200.jpg").toURL();

        // Two long slugs sharing a 200 character prefix must not collapse onto one filename.
        String shared = "a".repeat(200);
        String first = ripper.fileNameFor(shared + "-first-set-12345", image, "");
        String second = ripper.fileNameFor(shared + "-second-set-67890", image, "");

        Assertions.assertTrue(first.length() <= 255, "Filename too long: " + first.length());
        Assertions.assertTrue(second.length() <= 255, "Filename too long: " + second.length());
        Assertions.assertNotEquals(first, second);
        Assertions.assertTrue(first.endsWith("_0001-01_1200.jpg"), "Lost the original name: " + first);
    }

    private String gidOf(String url) throws IOException, URISyntaxException {
        URL parsed = new URI(url).toURL();
        return new EliteBabesRipper(parsed).getGID(parsed);
    }

    @Test
    @Tag("flaky")
    public void testGalleryRip() throws IOException, URISyntaxException {
        testRipper(new EliteBabesRipper(new URI(GALLERY).toURL()));
    }

    @Test
    @Tag("flaky")
    public void testGalleryFindsFullSizeImages() throws IOException, URISyntaxException {
        EliteBabesRipper ripper = new EliteBabesRipper(new URI(GALLERY).toURL());
        List<String> images = ripper.getURLsFromPage(ripper.getFirstPage());
        Assertions.assertFalse(images.isEmpty(), "Found no images in " + GALLERY);
        for (String image : images) {
            Assertions.assertTrue(image.startsWith("https://cdn.elitebabes.com/content/"),
                    "Not a CDN image url: " + image);
            // Thumbnails are the _wNNN variants; we want the full size renditions.
            Assertions.assertFalse(image.matches(".*_w\\d+\\.jpg$"), "Downloaded a thumbnail: " + image);
        }
    }

    @Test
    @Tag("flaky")
    public void testListingEnumeratesEveryGallery() throws IOException, URISyntaxException {
        EliteBabesRipper ripper = new EliteBabesRipper(new URI(MODEL).toURL());
        List<String> galleries = ripper.collectGalleryUrls(Http.url(MODEL).get());

        // The listing renders 20 galleries server side and pages the rest in via the grid API.
        Assertions.assertTrue(galleries.size() > 20,
                "Only found " + galleries.size() + " galleries, expected paging to run");
        for (String gallery : galleries) {
            Assertions.assertTrue(gallery.startsWith("https://www.elitebabes.com/"), "Off-site url: " + gallery);
        }
    }

    /** A listing rip walks galleries, so its first page is a gallery and not the listing. */
    @Test
    @Tag("flaky")
    public void testListingRipStartsAtFirstGallery() throws IOException, URISyntaxException {
        EliteBabesRipper ripper = new EliteBabesRipper(new URI(MODEL).toURL());
        List<String> images = ripper.getURLsFromPage(ripper.getFirstPage());
        Assertions.assertFalse(images.isEmpty(), "First gallery of " + MODEL + " yielded no images");
    }
}
