
package gg.embargo.bingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import okhttp3.*;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Singleton
public class BingoScreenshotManager {
    private static final String API_BASE = "https://embargo.gg/api/";
    private static final String SCREENSHOT_ENDPOINT = API_BASE + "bingo/plugin/screenshot";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final int MAX_IMAGE_DIMENSION = 1280;
    private static final float JPEG_QUALITY = 0.75f;
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

    private final ConcurrentLinkedQueue<PendingScreenshot> pendingScreenshots = new ConcurrentLinkedQueue<>();

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

    public void shutDown() {
        if (!started.getAndSet(false)) {
            return;
        }

        if (uploadExecutor != null) {
            uploadExecutor.shutdownNow();
            uploadExecutor = null;
        }

        pendingScreenshots.clear();
        log.debug("BingoScreenshotManager shut down");
    }

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

        drawManager.requestNextFrameListener(image -> {
            BufferedImage screenshot = (BufferedImage) image;

            uploadExecutor.submit(() -> {
                processAndUpload(screenshot, boardId, tileId, itemId, itemName, playerName, world);
            });
        });
    }

    private void processAndUpload(BufferedImage image, int boardId, int tileId, int itemId,
            String itemName, String playerName, int world) {
        try {
            if (!uploadSemaphore.tryAcquire(10, TimeUnit.SECONDS)) {
                log.warn("Screenshot upload rate limited, queuing for later");
                queuePendingScreenshot(image, boardId, tileId, itemId, itemName, playerName, world);
                return;
            }

            try {
                BufferedImage processedImage = resizeIfNecessary(image);

                byte[] imageBytes = toBytes(processedImage);

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

    private byte[] toBytes(BufferedImage image) throws IOException {
        BufferedImage rgbImage = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgbImage.getGraphics().drawImage(image, 0, 0, null);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(rgbImage, "png", baos);
            return baos.toByteArray();
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);

            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);

            writer.write(null, new IIOImage(rgbImage, null, null), param);
        } finally {
            writer.dispose();
        }

        return baos.toByteArray();
    }

    private void uploadToApi(byte[] imageBytes, int boardId, int tileId, int itemId,
            String itemName, String playerName, int world) {
        try {
            String screenshotBase64 = Base64.getEncoder().encodeToString(imageBytes);

            JsonObject payload = new JsonObject();
            payload.addProperty("rsn", playerName);
            payload.addProperty("boardId", boardId);
            payload.addProperty("tileId", tileId);
            payload.addProperty("screenshotBase64", screenshotBase64);

            String payloadStr = payload.toString();
            log.debug("Uploading screenshot: {} bytes (image: {} bytes)", payloadStr.length(), imageBytes.length);

            Request request = new Request.Builder()
                    .url(SCREENSHOT_ENDPOINT)
                    .post(RequestBody.create(JSON, payloadStr))
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("Successfully uploaded bingo screenshot for tile {} ({})", tileId, itemName);

                    String responseBody = response.body() != null ? response.body().string() : null;
                    if (responseBody != null && !responseBody.isEmpty()) {
                        try {
                            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                            if (jsonResponse.has("screenshotUrl")) {
                                String url = jsonResponse.get("screenshotUrl").getAsString();
                                log.debug("Screenshot uploaded to: {}", url);
                            }
                        } catch (Exception e) {
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

    private void queuePendingScreenshot(BufferedImage image, int boardId, int tileId, int itemId,
            String itemName, String playerName, int world) {
        try {
            byte[] imageBytes = toBytes(image);
            pendingScreenshots.add(new PendingScreenshot(
                    imageBytes, boardId, tileId, itemId, itemName, playerName, world));

            while (pendingScreenshots.size() > 10) {
                pendingScreenshots.poll();
            }
        } catch (IOException e) {
            log.error("Error queuing screenshot", e);
        }
    }

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
                        pendingScreenshots.add(screenshot);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

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
