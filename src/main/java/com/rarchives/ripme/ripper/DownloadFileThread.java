package com.rarchives.ripme.ripper;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.HttpStatusException;

import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.Utils;

/**
 * Thread for downloading files. Includes retry logic, observer notifications,
 * and other goodies.
 */
class DownloadFileThread implements Runnable {
    private static final Logger logger = LogManager.getLogger(DownloadFileThread.class);
    private static final long MIN_FILE_SIZE_BYTES = 10 * 1024;
    /** Cap on 429 backoff waits so a permanently throttled URL cannot spin forever. */
    private static final int MAX_RATE_LIMIT_WAITS = 10;
    private static final long MAX_RATE_LIMIT_WAIT_SECONDS = 300;
    private static final long MAX_RETRY_SLEEP_MILLIS = 30_000;

    private String referrer = "";
    private Map<String, String> cookies = new HashMap<>();
    private String userAgent = AbstractRipper.USER_AGENT;
    private Map<String, String> extraHeaders = new HashMap<>();

    private final URL url;
    private File saveAs;
    private final String prettySaveAs;
    private final AbstractRipper observer;
    private final int retries;
    private final Boolean getFileExtFromMIME;

    private final int TIMEOUT;

    private final int retrySleep;
    public DownloadFileThread(URL url, File saveAs, AbstractRipper observer, Boolean getFileExtFromMIME) {
        super();
        this.url = url;
        this.saveAs = saveAs;
        this.prettySaveAs = Utils.removeCWD(saveAs.toPath());
        this.observer = observer;
        this.retries = Utils.getConfigInteger("download.retries", 3);
        this.TIMEOUT = Utils.getConfigInteger("download.timeout", 60000);
        this.retrySleep = Utils.getConfigInteger("download.retry.sleep", 0);
        this.getFileExtFromMIME = getFileExtFromMIME;
    }

    public void setReferrer(String referrer) {
        this.referrer = referrer;
    }

    public void setCookies(Map<String, String> cookies) {
        this.cookies = cookies;
    }

    public void setUserAgent(String userAgent) {
        if (userAgent != null && !userAgent.isBlank()) {
            this.userAgent = userAgent;
        }
    }

    public void setExtraHeaders(Map<String, String> extraHeaders) {
        if (extraHeaders != null) {
            this.extraHeaders = extraHeaders;
        }
    }

    /**
     * Attempts to download the file. Retries as needed. Notifies observers upon
     * completion/error/warn.
     */
    
    @Override
    public void run() {
        // First thing we make sure the file name doesn't have any illegal chars in it
        File targetFile = new File(
                saveAs.getParentFile().getAbsolutePath() + File.separator + Utils.sanitizeSaveAs(saveAs.getName()));
        saveAs = targetFile;
        File workingFile = targetFile;
        Path targetPath = targetFile.toPath();
        long fileSize = 0;
        int bytesTotal;
        int bytesDownloaded = 0;
        boolean resumeDownload = observer.tryResumeDownload();
        if (targetFile.exists() && resumeDownload) {
            fileSize = targetFile.length();
        }
        try {
            observer.stopCheck();
        } catch (IOException e) {
            observer.downloadErrored(url, Utils.getLocalizedString("download.interrupted"));
            return;
        }
        boolean useTempFile = false;
        if (targetFile.exists() && !resumeDownload && !getFileExtFromMIME
                || Utils.fuzzyExists(Paths.get(targetFile.getParent()), targetFile.getName()) && getFileExtFromMIME
                        && !resumeDownload) {
            if (Utils.getConfigBoolean("file.overwrite", false)) {
                useTempFile = true;
            } else {
                logger.info("[!] " + Utils.getLocalizedString("skipping") + " " + url + " -- "
                        + Utils.getLocalizedString("file.already.exists") + ": " + prettySaveAs);
                observer.downloadExists(url, targetPath);
                return;
            }
        }
        Path tempFilePath = null;
        if (useTempFile) {
            try {
                tempFilePath = Files.createTempFile(targetFile.getParentFile().toPath(), "ripme-", ".tmp");
                workingFile = tempFilePath.toFile();
            } catch (IOException e) {
                logger.error("[!] Failed to prepare temporary file for {}: {}", targetFile.getAbsolutePath(),
                        e.getMessage());
                observer.downloadErrored(url, Utils.getLocalizedString("download.interrupted"));
                return;
            }
        }
        URL urlToDownload = this.url;
        boolean redirected = false;
        int tries = 0; // Number of attempts to download
        int rateLimitWaits = 0;
        do {
            tries += 1;
            try {
                logger.info("    Downloading file: " + urlToDownload + (tries > 0 ? " Retry #" + tries : ""));
                observer.sendUpdate(STATUS.DOWNLOAD_STARTED, url.toExternalForm());

                // Setup HTTP request. Decide the connection type from the URL we are actually
                // fetching (which may differ from the original after a redirect) so that a
                // redirect that switches protocol/host does not cause a ClassCastException.
                HttpURLConnection huc;
                URLConnection rawConnection = urlToDownload.openConnection();
                if (rawConnection instanceof HttpsURLConnection) {
                    huc = (HttpsURLConnection) rawConnection;
                } else {
                    huc = (HttpURLConnection) rawConnection;
                }
                huc.setInstanceFollowRedirects(true);
                // It is important to set both ConnectTimeout and ReadTimeout. If you don't then
                // ripme will wait forever
                // for the server to send data after connecting.
                huc.setConnectTimeout(TIMEOUT);
                huc.setReadTimeout(TIMEOUT);
                huc.setRequestProperty("accept", "*/*");
                if (!referrer.equals("")) {
                    huc.setRequestProperty("Referer", referrer); // Sic
                }
                huc.setRequestProperty("User-agent", userAgent);
                for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                    huc.setRequestProperty(header.getKey(), header.getValue());
                }
                StringBuilder cookie = new StringBuilder();
                for (String key : cookies.keySet()) {
                    if (!cookie.toString().equals("")) {
                        cookie.append("; ");
                    }
                    cookie.append(key).append("=").append(cookies.get(key));
                }
                huc.setRequestProperty("Cookie", cookie.toString());
                if (resumeDownload) {
                    if (fileSize != 0) {
                        huc.setRequestProperty("Range", "bytes=" + fileSize + "-");
                    }
                }
                logger.debug(Utils.getLocalizedString("request.properties") + ": " + huc.getRequestProperties());
                huc.connect();

                int statusCode = huc.getResponseCode();
                logger.debug("Status code: " + statusCode);
                // If the server doesn't allow resuming downloads error out
                if (statusCode != 206 && resumeDownload && targetFile.exists()) {
                    // TODO find a better way to handle servers that don't support resuming
                    // downloads then just erroring out
                    throw new IOException(Utils.getLocalizedString("server.doesnt.support.resuming.downloads"));
                }
                if (statusCode / 100 == 3) { // 3xx Redirect
                    String location = huc.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        logger.error("[!] Redirect status code " + statusCode + " without a Location header while downloading " + urlToDownload);
                        observer.downloadErrored(url, "Redirect (" + statusCode + ") without Location header while downloading " + url.toExternalForm());
                        return;
                    }
                    if (!redirected) {
                        // Don't increment retries on the first redirect
                        tries--;
                        redirected = true;
                    }
                    // Resolve relative redirects against the current URL.
                    urlToDownload = new URI(urlToDownload.toString()).resolve(location).toURL();
                    // Throw exception so download can be retried
                    throw new IOException("Redirect status code " + statusCode + " - redirect to " + location);
                }
                if (statusCode == 429) { // Too Many Requests
                    logger.warn("[!] Received 429 Too Many Requests for " + url);
                    observer.notifyRateLimited("HTTP 429 Too Many Requests for " + url);
                    rateLimitWaits += 1;
                    if (rateLimitWaits > MAX_RATE_LIMIT_WAITS) {
                        logger.error("[!] Still rate limited after " + MAX_RATE_LIMIT_WAITS + " waits for " + url);
                        observer.downloadErrored(url, "Rate limited (HTTP 429) after " + MAX_RATE_LIMIT_WAITS
                                + " retries while downloading " + url.toExternalForm());
                        return;
                    }
                    // Waiting out a rate limit is not a failed attempt, so it must not consume the
                    // normal retry budget - otherwise a throttled host exhausts the retries instantly.
                    tries -= 1;
                    long waitTimeSeconds = parseRetryAfterSeconds(huc.getHeaderField("Retry-After"));
                    if (waitTimeSeconds <= 0) {
                        waitTimeSeconds = Math.min(1L << Math.min(rateLimitWaits, 6), MAX_RATE_LIMIT_WAIT_SECONDS);
                    }
                    waitTimeSeconds = Math.min(waitTimeSeconds, MAX_RATE_LIMIT_WAIT_SECONDS);

                    logger.info("Waiting for " + waitTimeSeconds + " seconds before retrying...");
                    Utils.sleep(waitTimeSeconds * 1000L);
                    continue; // Retry the loop
                } else if (statusCode / 100 == 4) {
                    logger.error("[!] {} {} while downloading from {} (Referer: {})",
                            Utils.getLocalizedString("nonretriable.status.code"), statusCode, url,
                            referrer.isBlank() ? "(none)" : referrer);
                    observer.downloadErrored(url, Utils.getLocalizedString("nonretriable.status.code") + " "
                            + statusCode + " while downloading " + url.toExternalForm());
                    return; // Not retriable, drop out.
                }
                if (statusCode / 100 == 5) { // 5xx errors
                    // Only throw here: reporting the error now would trip the ripper's consecutive
                    // -failure circuit breaker even when the very next attempt succeeds. The retry
                    // loop reports it for real once the retries are exhausted.
                    logger.warn("[!] {} {} while downloading {}", Utils.getLocalizedString("retriable.status.code"),
                            statusCode, url);
                    throw new IOException(Utils.getLocalizedString("retriable.status.code") + " " + statusCode);
                }
                if (huc.getContentLength() == 503 && urlToDownload.getHost().endsWith("imgur.com")) {
                    // Imgur image with 503 bytes is "404"
                    logger.error("[!] Imgur image is 404 (503 bytes long): " + url);
                    observer.downloadErrored(url, "Imgur image is 404: " + url.toExternalForm());
                    return;
                }

                // If the ripper is using the bytes progress bar set bytesTotal to
                // huc.getContentLength()
                if (observer.useByteProgessBar()) {
                    bytesTotal = huc.getContentLength();
                    observer.setBytesTotal(bytesTotal);
                    observer.sendUpdate(STATUS.TOTAL_BYTES, bytesTotal);
                    logger.debug("Size of file at " + this.url + " = " + bytesTotal + "b");
                }

                // Save file
                InputStream bis;
                bis = new BufferedInputStream(huc.getInputStream());

                // Check if we should get the file ext from the MIME type
                if (getFileExtFromMIME) {
                    String fileExt = URLConnection.guessContentTypeFromStream(bis);
                    if (fileExt != null) {
                        fileExt = fileExt.replaceAll("image/", "");
                    } else {
                        logger.error("Was unable to get content type from stream");
                        // Try to get the file type from the magic number
                        byte[] magicBytes = new byte[8];
                        bis.read(magicBytes, 0, 5);
                        bis.reset();
                        fileExt = Utils.getEXTFromMagic(magicBytes);
                        if (fileExt == null) {
                            logger.error(Utils.getLocalizedString("was.unable.to.get.content.type.using.magic.number"));
                            logger.error(
                                    Utils.getLocalizedString("magic.number.was") + ": " + Arrays.toString(magicBytes));
                        }
                    }
                    if (fileExt != null) {
                        targetFile = new File(targetFile.toString() + "." + fileExt);
                        targetPath = targetFile.toPath();
                        saveAs = targetFile;
                        if (!useTempFile) {
                            workingFile = targetFile;
                        }
                    }
                }
                // If we're resuming a download we append data to the existing file
                OutputStream fos = null;
                if (statusCode == 206) {
                    fos = new FileOutputStream(workingFile, true);
                } else {
                    try {
                        fos = new FileOutputStream(workingFile);
                    } catch (FileNotFoundException e) {
                        String fnfMessage = e.getMessage() == null ? "" : e.getMessage();
                        // We do this because some filesystems have a max name length
                        if (fnfMessage.contains("File name too long")) {
                            logger.error("The filename " + saveAs.getName()
                                    + " is to long to be saved on this file system.");
                            logger.info("Shortening filename");
                            String[] saveAsSplit = workingFile.getName().split("\\.");
                            // Get the file extension so when we shorten the file name we don't cut off the
                            // file extension
                            String fileExt = saveAsSplit[saveAsSplit.length - 1];
                            // The max limit for filenames on Linux with Ext3/4 is 255 bytes
                            logger.info(workingFile.getName().substring(0, 254 - fileExt.length()) + fileExt);
                            String filename = workingFile.getName().substring(0, 254 - fileExt.length()) + "." + fileExt;
                            // We can't just use the new file name as the saveAs because the file name
                            // doesn't include the
                            // users save path, so we get the user save path from the old saveAs
                            targetFile = new File(targetFile.getParentFile().getAbsolutePath() + File.separator + filename);
                            targetPath = targetFile.toPath();
                            saveAs = targetFile;
                            workingFile = useTempFile ? workingFile : targetFile;
                            fos = new FileOutputStream(workingFile);
                        } else if (targetFile.getAbsolutePath().length() > 259 && Utils.isWindows()) {
                            // This if is for when the file path has gone above 260 chars which windows does
                            // not allow
                            fos = Files.newOutputStream(
                                    Utils.shortenSaveAsWindows(targetFile.getParentFile().getPath(), targetFile.getName()));
                            assert fos != null: "After shortenSaveAsWindows: " + targetFile.getAbsolutePath();
                        }
                        if (fos == null) {
                            // Re-throw so the failure is surfaced/retried instead of causing a
                            // later NullPointerException when we try to write to a null stream.
                            throw e;
                        }
                    }
                }
                byte[] data = new byte[1024 * 256];
                int bytesRead;
                boolean shouldSkipFileDownload = huc.getContentLength() / 1000000 >= 10 && AbstractRipper.isThisATest();
                // If this is a test rip we skip large downloads
                if (shouldSkipFileDownload) {
                    logger.debug("Not downloading whole file because it is over 10mb and this is a test");
                } else {
                    while ((bytesRead = bis.read(data)) != -1) {
                        try {
                            observer.stopCheck();
                        } catch (IOException e) {
                            observer.downloadErrored(url, Utils.getLocalizedString("download.interrupted"));
                            return;
                        }
                        fos.write(data, 0, bytesRead);
                        if (observer.useByteProgessBar()) {
                            bytesDownloaded += bytesRead;
                            observer.setBytesCompleted(bytesDownloaded);
                            observer.sendUpdate(STATUS.COMPLETED_BYTES, bytesDownloaded);
                        }
                    }
                }
                bis.close();
                fos.close();

                File fileToInspect = workingFile;
                long finalSize = fileToInspect.length();
                if (!shouldSkipFileDownload && finalSize < MIN_FILE_SIZE_BYTES) {
                    logger.warn("[!] Deleting {} ({} bytes) because it is smaller than {} bytes", prettySaveAs, finalSize,
                            MIN_FILE_SIZE_BYTES);
                    if (!fileToInspect.delete()) {
                        logger.warn("[!] Failed to delete {} after size check", fileToInspect.getAbsolutePath());
                    }
                    observer.downloadErrored(url, "File smaller than 10KB (deleted)");
                    return;
                }
                if (!shouldSkipFileDownload && !observer.registerDownloadHash(fileToInspect.toPath())) {
                    logger.warn("[!] Deleting {} because its hash matches a previously downloaded file", prettySaveAs);
                    if (!fileToInspect.delete()) {
                        logger.warn("[!] Failed to delete duplicate file {}", fileToInspect.getAbsolutePath());
                    }
                    observer.downloadExists(url, targetPath);
                    return;
                }
                if (useTempFile) {
                    try {
                        Files.move(fileToInspect.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                        workingFile = targetFile;
                    } catch (IOException moveException) {
                        logger.error("[!] Failed to replace existing file {}: {}", targetPath, moveException.getMessage());
                        if (!fileToInspect.delete()) {
                            logger.warn("[!] Failed to delete temporary file {}", fileToInspect.getAbsolutePath());
                        }
                        observer.downloadErrored(url, Utils.getLocalizedString("download.interrupted"));
                        return;
                    }
                }
                break; // Download successful: break out of infinite loop
            } catch (SocketTimeoutException timeoutEx) {
                // A timeout is the archetypal transient error, so fall through to the retry check.
                // Breaking out here reported a truncated (or missing) file as a completed download.
                logger.error("[!] " + url.toExternalForm() + " timed out");
            } catch (HttpStatusException hse) {
                logger.debug(Utils.getLocalizedString("http.status.exception"), hse);
                logger.error("[!] HTTP status " + hse.getStatusCode() + " while downloading from " + urlToDownload);
                Set<Integer> skipStatusCodes = Set.of(404, 410);
                if (skipStatusCodes.contains(hse.getStatusCode()) && Utils.getConfigBoolean("errors.skip404", false)) {
                    observer.downloadErrored(url,
                            "HTTP status code " + hse.getStatusCode() + " while downloading " + url.toExternalForm());
                    return;
                }
            } catch (IOException | URISyntaxException e) {
                logger.debug("IOException", e);
                logger.error("[!] " + Utils.getLocalizedString("exception.while.downloading.file") + ": " + url + " - "
                        + e.getMessage());
            } catch (NullPointerException npe){
                // Log the actual stack trace so the real cause is diagnosable instead of being
                // silently swallowed, and fall through to the retry logic below.
                logger.error("[!] " + Utils.getLocalizedString("failed.to.download") + " for URL " + url, npe);
            }
            if (tries > this.retries) {
                logger.error("[!] " + Utils.getLocalizedString("exceeded.maximum.retries") + " (" + this.retries
                        + ") for URL " + url);
                observer.downloadErrored(url,
                        Utils.getLocalizedString("failed.to.download") + " " + url.toExternalForm());
                return;
            } else {
                if (retrySleep > 0) {
                    // Back off progressively so a struggling host gets time to recover.
                    Utils.sleep(Math.min(retrySleep * (long) tries, MAX_RETRY_SLEEP_MILLIS));
                }
            }
        } while (true);
        observer.downloadCompleted(url, saveAs.toPath());
        logger.info("[+] Saved " + url + " as " + this.prettySaveAs);
    }

    /**
     * @return seconds to wait from a {@code Retry-After} header (delta-seconds or HTTP date),
     *         or {@code 0} when the header is absent or unparseable.
     */
    static long parseRetryAfterSeconds(String retryAfterHeader) {
        if (retryAfterHeader == null || retryAfterHeader.isBlank()) {
            return 0;
        }
        String value = retryAfterHeader.trim();
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // Fall through to the HTTP-date form.
        }
        try {
            long seconds = Duration.between(Instant.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
            return Math.max(0, seconds);
        } catch (DateTimeParseException e) {
            logger.warn("Could not parse Retry-After header: {}", retryAfterHeader);
            return 0;
        }
    }
}
