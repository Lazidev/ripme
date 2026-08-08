package com.rarchives.ripme.tst.ripper.rippers;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import com.rarchives.ripme.ripper.rippers.EliteBabesRipper;

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

    @Test
    public void testCanRip() throws IOException, URISyntaxException {
        URL url = new URI(GALLERY).toURL();
        Assertions.assertTrue(new EliteBabesRipper(url).canRip(url));
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
    public void testListingIsQueued() throws IOException, URISyntaxException {
        EliteBabesRipper ripper = new EliteBabesRipper(new URI(MODEL).toURL());
        Assertions.assertTrue(ripper.pageContainsAlbums(new URI(MODEL).toURL()),
                "Model page should be expanded into the queue");

        List<String> albums = ripper.getAlbumsToQueue(ripper.getFirstPage());
        // The listing renders 20 albums server side and pages the rest in via the grid API.
        Assertions.assertTrue(albums.size() > 20, "Only found " + albums.size() + " albums, expected paging to run");
        for (String album : albums) {
            Assertions.assertTrue(album.startsWith("https://www.elitebabes.com/"), "Off-site album url: " + album);
        }
    }

    @Test
    @Tag("flaky")
    public void testGalleryIsNotQueued() throws IOException, URISyntaxException {
        EliteBabesRipper ripper = new EliteBabesRipper(new URI(GALLERY).toURL());
        Assertions.assertFalse(ripper.pageContainsAlbums(new URI(GALLERY).toURL()),
                "Gallery page should be ripped, not queued");
    }
}
