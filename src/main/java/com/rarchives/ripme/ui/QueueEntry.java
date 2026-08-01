package com.rarchives.ripme.ui;

import java.util.Objects;

import com.rarchives.ripme.utils.Utils;

/**
 * A single queued album URL, with optional force-rip and per-item max-download overrides.
 */
public final class QueueEntry {
    private static final String FORCE_PREFIX = "force|";
    private static final String MAX_PREFIX = "max=";

    private final String url;
    private boolean forceRip;
    /** {@code null} means use the global {@code maxdownloads} setting at rip time. */
    private Integer maxDownloads;

    public QueueEntry(String url) {
        this(url, false, null);
    }

    public QueueEntry(String url, boolean forceRip) {
        this(url, forceRip, null);
    }

    public QueueEntry(String url, boolean forceRip, Integer maxDownloads) {
        this.url = url;
        this.forceRip = forceRip;
        this.maxDownloads = maxDownloads;
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

    public Integer getMaxDownloads() {
        return maxDownloads;
    }

    public void setMaxDownloads(Integer maxDownloads) {
        this.maxDownloads = maxDownloads;
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
        if (trimmed.startsWith(FORCE_PREFIX)) {
            forceRip = true;
            trimmed = trimmed.substring(FORCE_PREFIX.length());
        }
        if (trimmed.startsWith(MAX_PREFIX)) {
            int pipe = trimmed.indexOf('|');
            if (pipe > MAX_PREFIX.length()) {
                String maxPart = trimmed.substring(MAX_PREFIX.length(), pipe);
                try {
                    maxDownloads = Integer.parseInt(maxPart.trim());
                    trimmed = trimmed.substring(pipe + 1);
                } catch (NumberFormatException ignored) {
                    // Treat as a plain URL if max= parsing fails.
                }
            }
        }
        return new QueueEntry(trimmed, forceRip, maxDownloads);
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
