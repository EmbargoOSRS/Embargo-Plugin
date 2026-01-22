
package gg.embargo.bingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import okhttp3.*;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages screenshot capture and upload for bingo tile completions.
 * <p>
 * Screenshots are captured using RuneLite's DrawManager and uploaded
 * to both the Embargo API and a Discord webhook.
 * <p>
 * This follows the pattern used by RuneLite's ScreenshotPlugin.
 */
@Slf4j
@Singleton
public class BingoScreenshotManager {
    private static final String API_BASE = "https://embargo.gg/api/";
    private static final String SCREENSHOT_ENDPOINT = API_BASE + "bingo/plugin/screenshot";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // Maximum image dimension (resize if larger)
    private static final int MAX_IMAGE_DIMENSION = 1920;

    // Queue for rate limiting uploads
    private static final int MAX_CONCURRENT_UPLOADS = 2;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private DrawManager drawManager;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private Gson gson;

    @Inject
    private BingoManager bingoManager;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private ExecutorService uploadExecutor;
    private final Semaphore uploadSemaphore = new Semaphore(MAX_CONCURRENT_UPLOADS);

    // Queue of pending screenshots in case of upload failure
    private final ConcurrentLinkedQueue<PendingScreenshot> pendingScreenshots = new ConcurrentLinkedQueue<>();

    /**
     * Starts the screenshot manager.
     */
    public void startUp() {
        if (started.getAndSet(true)) {
            return;
        }

        uploadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_UPLOADS, r -> {
            Thread t = new Thread(r, "BingoScreenshot");
            t.setDaemon(true);
            return t;
        });

        log.debug("BingoScreenshotManager started");
    }

    /**
     * Shuts down the screenshot manager.
     */
    public void shutDown() {
        if (!started.getAndSet(false)) {
            return;
        }

        if (uploadExecutor != null) {
            uploadExecutor.shutdown();
            try {
                if (!uploadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    uploadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                uploadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            uploadExecutor = null;
        }

        pendingScreenshots.clear();
        log.debug("BingoScreenshotManager shut down");
    }

    /**
     * Captures a screenshot and uploads it for the given tile/item.
     * <p>
     * This method is safe to call from any thread. The screenshot will be
     * captured on the next frame render.
     *
     * @param boardId  the bingo board ID
     * @param tileId   the bingo tile ID
     * @param itemId   the item ID that triggered the screenshot
     * @param itemName the item name for logging
     */
    public void captureAndUpload(int boardId, int tileId, int itemId, String itemName) {
        if (!started.get() || uploadExecutor == null) {
            log.debug("Screenshot manager not started, skipping capture");
            return;
        }

        if (client == null || client.getLocalPlayer() == null) {
            return;
        }

        String playerName = client.getLocalPlayer().getName();
        int world = client.getWorld();

        log.debug("Queuing screenshot capture for tile {} ({}) on board {}", tileId, itemName, boardId);

        // Request the next frame for screenshot capture
        drawManager.requestNextFrameListener(image -> {
            // This callback runs during the render cycle, so we need to
            // process the image asynchronously
            BufferedImage screenshot = (BufferedImage) image;

            // Submit to upload executor
            uploadExecutor.submit(() -> {
                processAndUpload(screenshot, boardId, tileId, itemId, itemName, playerName, world);
            });
        });
    }

    /**
     * Processes and uploads a screenshot.
     *
     * @param image      the captured screenshot
     * @param boardId    the bingo board ID
     * @param tileId     the tile ID
     * @param itemId     the item ID
     * @param itemName   the item name
     * @param playerName the player's RSN
     * @param world      the current world
     */
    private void processAndUpload(BufferedImage image, int boardId, int tileId, int itemId,
            String itemName, String playerName, int world) {
        try {
            // Acquire semaphore for rate limiting
            if (!uploadSemaphore.tryAcquire(10, TimeUnit.SECONDS)) {
                log.warn("Screenshot upload rate limited, queuing for later");
                queuePendingScreenshot(image, boardId, tileId, itemId, itemName, playerName, world);
                return;
            }

            try {
                // Resize if necessary
                BufferedImage processedImage = resizeIfNecessary(image);

                // Convert to PNG bytes
                byte[] imageBytes = toBytes(processedImage);

                // Upload to API
                uploadToApi(imageBytes, boardId, tileId, itemId, itemName, playerName, world);

            } finally {
                uploadSemaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Screenshot upload interrupted");
        } catch (Exception e) {
            log.error("Error processing screenshot", e);
        }
    }

    /**
     * Resizes an image if it exceeds the maximum dimension.
     */
    private BufferedImage resizeIfNecessary(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        if (width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION) {
            return image;
        }

        double scale = Math.min(
                (double) MAX_IMAGE_DIMENSION / width,
                (double) MAX_IMAGE_DIMENSION / height);

        int newWidth = (int) (width * scale);
        int newHeight = (int) (height * scale);

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        resized.getGraphics().drawImage(
                image.getScaledInstance(newWidth, newHeight, java.awt.Image.SCALE_SMOOTH),
                0, 0, null);

        return resized;
    }

    /**
     * Converts a BufferedImage to PNG bytes.
     */
    private byte[] toBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    /**
     * Uploads the screenshot to the API using JSON with base64-encoded image.
     */
    private void uploadToApi(byte[] imageBytes, int boardId, int tileId, int itemId,
            String itemName, String playerName, int world) {
        try {
            // Convert image to base64
            String screenshotBase64 = Base64.getEncoder().encodeToString(imageBytes);

            // Build JSON payload
            JsonObject payload = new JsonObject();
            payload.addProperty("rsn", playerName);
            payload.addProperty("boardId", boardId);
            payload.addProperty("tileId", tileId);
            payload.addProperty("screenshotBase64", screenshotBase64);

            Request request = new Request.Builder()
                    .url(SCREENSHOT_ENDPOINT)
                    .post(RequestBody.create(JSON, payload.toString()))
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("Successfully uploaded bingo screenshot for tile {} ({})", tileId, itemName);

                    // Parse response to get the screenshot URL (if returned)
                    String responseBody = response.body() != null ? response.body().string() : null;
                    if (responseBody != null && !responseBody.isEmpty()) {
                        try {
                            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                            if (jsonResponse.has("screenshotUrl")) {
                                String url = jsonResponse.get("screenshotUrl").getAsString();
                                log.debug("Screenshot uploaded to: {}", url);
                            }
                        } catch (Exception e) {
                            // Ignore parsing errors for response
                        }
                    }
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "No response body";
                    log.warn("Failed to upload screenshot: HTTP {} - {}", response.code(), errorBody);
                }
            }
        } catch (IOException e) {
            log.error("Error uploading screenshot", e);
        }
    }

    /**
     * Queues a screenshot for later upload attempt.
     */
    private void queuePendingScreenshot(BufferedImage image, int boardId, int tileId, int itemId,
            String itemName, String playerName, int world) {
        try {
            byte[] imageBytes = toBytes(image);
            pendingScreenshots.add(new PendingScreenshot(
                    imageBytes, boardId, tileId, itemId, itemName, playerName, world));

            // Limit queue size
            while (pendingScreenshots.size() > 10) {
                pendingScreenshots.poll();
            }
        } catch (IOException e) {
            log.error("Error queuing screenshot", e);
        }
    }

    /**
     * Retries uploading any pending screenshots.
     */
    public void retryPendingScreenshots() {
        if (!started.get() || uploadExecutor == null) {
            return;
        }

        PendingScreenshot pending;
        while ((pending = pendingScreenshots.poll()) != null) {
            final PendingScreenshot screenshot = pending;
            uploadExecutor.submit(() -> {
                try {
                    if (uploadSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                        try {
                            uploadToApi(
                                    screenshot.imageBytes,
                                    screenshot.boardId,
                                    screenshot.tileId,
                                    screenshot.itemId,
                                    screenshot.itemName,
                                    screenshot.playerName,
                                    screenshot.world);
                        } finally {
                            uploadSemaphore.release();
                        }
                    } else {
                        // Re-queue if still rate limited
                        pendingScreenshots.add(screenshot);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    /**
     * Converts a screenshot to base64 for embedding in API requests.
     *
     * @param image the image to convert
     * @return base64-encoded PNG string
     */
    public String toBase64(BufferedImage image) {
        try {
            BufferedImage processed = resizeIfNecessary(image);
            byte[] bytes = toBytes(processed);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            log.error("Error encoding screenshot to base64", e);
            return null;
        }
    }

    /**
     * Internal class for pending screenshot data.
     */
    private static class PendingScreenshot {
        final byte[] imageBytes;
        final int boardId;
        final int tileId;
        final int itemId;
        final String itemName;
        final String playerName;
        final int world;

        PendingScreenshot(byte[] imageBytes, int boardId, int tileId, int itemId,
                String itemName, String playerName, int world) {
            this.imageBytes = imageBytes;
            this.boardId = boardId;
            this.tileId = tileId;
            this.itemId = itemId;
            this.itemName = itemName;
            this.playerName = playerName;
            this.world = world;
        }
    }
}
