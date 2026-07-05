
package gg.embargo.bingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import gg.embargo.EmbargoApi;
import gg.embargo.ChatPrivacyMode;
import gg.embargo.PlayerIdentity;
import gg.embargo.EmbargoConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import okhttp3.*;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Singleton
public class BingoScreenshotManager {
    private static final String API_BASE = EmbargoApi.BASE_URL;
    private static final String SCREENSHOT_ENDPOINT = API_BASE + "bingo/plugin/screenshot";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

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

    @Inject
    private EmbargoConfig config;

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

    public void captureBase64(Consumer<String> callback) {
        if (!started.get() || uploadExecutor == null) {
            callback.accept(null);
            return;
        }

        if (client == null || client.getLocalPlayer() == null) {
            callback.accept(null);
            return;
        }

        clientThread.invoke(() -> {
            ChatPrivacyMode privacyMode = config.bingoChatPrivacy();
            boolean chatHidden = hideWidget(privacyMode == ChatPrivacyMode.HIDE_ALL, InterfaceID.Chatbox.CHATAREA);
            boolean pmHidden = hideWidget(privacyMode != ChatPrivacyMode.HIDE_NONE, InterfaceID.PmChat.CONTAINER);

            drawManager.requestNextFrameListener(image -> {
                unhideWidget(chatHidden, InterfaceID.Chatbox.CHATAREA);
                unhideWidget(pmHidden, InterfaceID.PmChat.CONTAINER);

                BufferedImage screenshot = (BufferedImage) image;
                uploadExecutor.submit(() -> {
                    String base64 = toBase64(prepareImage(screenshot));
                    callback.accept(base64);
                });
            });
        });
    }

    public void captureAndUpload(int boardId, int tileId, int itemId, String itemName) {
        if (!started.get() || uploadExecutor == null) {
            log.debug("Screenshot manager not started, skipping capture");
            return;
        }

        if (client == null || client.getLocalPlayer() == null) {
            return;
        }

        String playerName = PlayerIdentity.getUsername(client);
        int world = client.getWorld();

        log.debug("Queuing screenshot capture for tile {} ({}) on board {}", tileId, itemName, boardId);

        clientThread.invoke(() -> {
            ChatPrivacyMode privacyMode = config.bingoChatPrivacy();
            boolean chatHidden = hideWidget(privacyMode == ChatPrivacyMode.HIDE_ALL, InterfaceID.Chatbox.CHATAREA);
            boolean pmHidden = hideWidget(privacyMode != ChatPrivacyMode.HIDE_NONE, InterfaceID.PmChat.CONTAINER);

            drawManager.requestNextFrameListener(image -> {
                unhideWidget(chatHidden, InterfaceID.Chatbox.CHATAREA);
                unhideWidget(pmHidden, InterfaceID.PmChat.CONTAINER);

                BufferedImage screenshot = (BufferedImage) image;

                uploadExecutor.submit(() -> {
                    processAndUpload(prepareImage(screenshot), boardId, tileId, itemId, itemName, playerName, world);
                });
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
                byte[] imageBytes = toBytes(image);

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

    private byte[] toBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    // Maximum uploaded screenshot width; larger frames are downscaled
    private static final int MAX_SCREENSHOT_WIDTH = 1600;

    /**
     * Applies upload hygiene to a captured frame: downscales very wide frames
     * to cap payload size, and burns local + UTC timestamps into the corner so
     * staff can verify when the screenshot was actually taken.
     */
    private BufferedImage prepareImage(BufferedImage image) {
        BufferedImage result = image;

        if (result.getWidth() > MAX_SCREENSHOT_WIDTH) {
            double scale = (double) MAX_SCREENSHOT_WIDTH / result.getWidth();
            int newHeight = (int) Math.round(result.getHeight() * scale);
            BufferedImage scaled = new BufferedImage(MAX_SCREENSHOT_WIDTH, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(result, 0, 0, MAX_SCREENSHOT_WIDTH, newHeight, null);
            g.dispose();
            result = scaled;
        } else if (result.getType() != BufferedImage.TYPE_INT_RGB) {
            // Copy so the timestamp drawing never mutates the original frame
            BufferedImage copy = new BufferedImage(result.getWidth(), result.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            g.drawImage(result, 0, 0, null);
            g.dispose();
            result = copy;
        }

        drawTimestamp(result);
        return result;
    }

    private void drawTimestamp(BufferedImage image) {
        ZonedDateTime now = ZonedDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String text = now.format(formatter) + " " + now.getZone().getId()
                + " | " + now.withZoneSameInstant(ZoneOffset.UTC).format(formatter) + " UTC";

        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            int x = 8;
            int y = image.getHeight() - 8;

            // Dark outline so the text is readable on any background
            g.setColor(Color.BLACK);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        g.drawString(text, x + dx, y + dy);
                    }
                }
            }
            g.setColor(Color.WHITE);
            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }
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
                    log.debug("Successfully uploaded bingo screenshot for tile {} ({})", tileId, itemName);

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
            byte[] bytes = toBytes(image);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            log.error("Error encoding screenshot to base64", e);
            return null;
        }
    }

    private boolean hideWidget(boolean shouldHide, int componentId) {
        if (!shouldHide) {
            return false;
        }

        Widget widget = client.getWidget(componentId);
        if (widget == null || widget.isHidden()) {
            return false;
        }

        widget.setHidden(true);
        return true;
    }

    private void unhideWidget(boolean wasHidden, int componentId) {
        if (!wasHidden) {
            return;
        }

        clientThread.invoke(() -> {
            Widget widget = client.getWidget(componentId);
            if (widget != null) {
                widget.setHidden(false);
            }
        });
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
