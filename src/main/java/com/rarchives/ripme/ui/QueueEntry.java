package com.rarchives.ripme.ui;

import java.util.Objects;

import com.rarchives.ripme.utils.Utils;

/**
 * A single queued album URL, with optional force-rip, folder prefix, and per-item max-download overrides.
 */
public final class QueueEntry {
    private static final String FORCE_PREFIX = "force|";
    private static final String FOLDER_PREFIX_MARKER = "prefix=";
    private static final String MAX_PREFIX = "max=";

    private final String url;
    private boolean forceRip;
    /** One ASCII letter prepended to the album folder name, or empty. */
    private String folderPrefix;
    /** {@code null} means use the global {@code maxdownloads} setting at rip time. */
    private Integer maxDownloads;
    /**
     * Epoch millis before which the queue must not auto-start this item.
     * {@code 0} means it may start immediately (restored items, retries).
     */
    private long startAfterMillis;

    public QueueEntry(String url) {
        this(url, false, null);
    }

    public QueueEntry(String url, boolean forceRip) {
        this(url, forceRip, null);
    }

    public QueueEntry(String url, boolean forceRip, Integer maxDownloads) {
        this(url, forceRip, maxDownloads, "");
    }

    public QueueEntry(String url, boolean forceRip, Integer maxDownloads, String folderPrefix) {
        this(url, forceRip, maxDownloads, folderPrefix, 0L);
    }

    public QueueEntry(String url, boolean forceRip, Integer maxDownloads, String folderPrefix, long startAfterMillis) {
        this.url = url;
        this.forceRip = forceRip;
        this.folderPrefix = Utils.normalizeFolderPrefix(folderPrefix);
        this.maxDownloads = maxDownloads;
        this.startAfterMillis = Math.max(0L, startAfterMillis);
    }

    public String getUrl() {
        return url;
    }

    public boolean isForceRip() {
        return forceRip;
    }

    public void setForceRip(boolean forceRip) {
        this.forceRip = forceRip;
    }

    public String getFolderPrefix() {
        return folderPrefix == null ? "" : folderPrefix;
    }

    public void setFolderPrefix(String folderPrefix) {
        this.folderPrefix = Utils.normalizeFolderPrefix(folderPrefix);
    }

    public Integer getMaxDownloads() {
        return maxDownloads;
    }

    public void setMaxDownloads(Integer maxDownloads) {
        this.maxDownloads = maxDownloads;
    }

    public long getStartAfterMillis() {
        return startAfterMillis;
    }

    public void setStartAfterMillis(long startAfterMillis) {
        this.startAfterMillis = Math.max(0L, startAfterMillis);
    }

    /**
     * Effective max downloads for this entry: the override if set, otherwise the global default.
     */
    public int getEffectiveMaxDownloads() {
        if (maxDownloads != null) {
            return maxDownloads;
        }
        return Utils.getConfigInteger("maxdownloads", 250);
    }

    public String toConfigString() {
        StringBuilder sb = new StringBuilder();
        if (forceRip) {
            sb.append(FORCE_PREFIX);
        }
        if (folderPrefix != null && !folderPrefix.isEmpty()) {
            sb.append(FOLDER_PREFIX_MARKER).append(folderPrefix).append('|');
        }
        if (maxDownloads != null) {
            sb.append(MAX_PREFIX).append(maxDownloads).append('|');
        }
        sb.append(url);
        return sb.toString();
    }

    public static QueueEntry fromConfigString(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        boolean forceRip = false;
        Integer maxDownloads = null;
        String folderPrefix = "";
        while (!trimmed.isEmpty()) {
            if (trimmed.startsWith(FORCE_PREFIX)) {
                forceRip = true;
                trimmed = trimmed.substring(FORCE_PREFIX.length());
                continue;
            }
            if (trimmed.startsWith(FOLDER_PREFIX_MARKER)) {
                int pipe = trimmed.indexOf('|');
                if (pipe < 0) {
                    break;
                }
                folderPrefix = Utils.normalizeFolderPrefix(trimmed.substring(FOLDER_PREFIX_MARKER.length(), pipe));
                trimmed = trimmed.substring(pipe + 1);
                continue;
            }
            if (trimmed.startsWith(MAX_PREFIX)) {
                int pipe = trimmed.indexOf('|');
                if (pipe <= MAX_PREFIX.length()) {
                    break;
                }
                String maxPart = trimmed.substring(MAX_PREFIX.length(), pipe);
                try {
                    maxDownloads = Integer.parseInt(maxPart.trim());
                } catch (NumberFormatException ignored) {
                    break;
                }
                trimmed = trimmed.substring(pipe + 1);
                continue;
            }
            break;
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        return new QueueEntry(trimmed, forceRip, maxDownloads, folderPrefix, 0L);
    }

    /**
     * Coerces a queue model element (legacy {@link String} or {@link QueueEntry}) into a entry.
     */
    public static QueueEntry from(Object item) {
        if (item == null) {
            return null;
        }
        if (item instanceof QueueEntry) {
            return (QueueEntry) item;
        }
        return fromConfigString(item.toString());
    }

    @Override
    public String toString() {
        return url;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof QueueEntry) {
            return Objects.equals(url, ((QueueEntry) obj).url);
        }
        if (obj instanceof String) {
            return Objects.equals(url, obj);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(url);
    }
}
