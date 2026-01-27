package gg.embargo.models;

import gg.embargo.EmbargoConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages 3D model extraction and upload for player characters and pets.
 * <p>
 * Captures player models on login, equipment changes, and pet spawns,
 * exports them to PLY format with vertex colors, and uploads to the Embargo API.
 */
@Slf4j
@Singleton
public class ModelUploadManager {

    private static final String API_BASE = "https://embargo.gg/api/";
    private static final String MODEL_UPLOAD_ENDPOINT = API_BASE + "runelite/models/upload";

    // Rate limiting: minimum 60 seconds between uploads
    private static final long UPLOAD_COOLDOWN_MS = 60_000;

    // Debounce delay for equipment changes (wait for rapid changes to settle)
    private static final long EQUIPMENT_DEBOUNCE_MS = 2_000;

    // Delay after login before capturing model (ensure it's loaded)
    private static final long LOGIN_DELAY_MS = 3_000;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private EmbargoConfig config;

    @Inject
    private EventBus eventBus;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong lastUploadTime = new AtomicLong(0);
    private final AtomicBoolean pendingUpload = new AtomicBoolean(false);

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> debounceTask;

    // Track current pet NPC for model extraction
    private volatile NPC currentPet = null;

    /**
     * Starts the model upload manager.
     */
    public void startUp() {
        if (started.getAndSet(true)) {
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ModelUpload");
            t.setDaemon(true);
            return t;
        });

        eventBus.register(this);
        log.info("ModelUploadManager started");
    }

    /**
     * Shuts down the model upload manager.
     */
    public void shutDown() {
        if (!started.getAndSet(false)) {
            return;
        }

        eventBus.unregister(this);

        if (debounceTask != null) {
            debounceTask.cancel(false);
            debounceTask = null;
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }

        currentPet = null;
        log.info("ModelUploadManager shut down");
    }

    /**
     * Returns whether the manager is currently running.
     */
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Handles login - trigger initial model upload after delay.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (!config.enableModelUploads()) {
            return;
        }

        if (event.getGameState() == GameState.LOGGED_IN) {
            // Delay initial upload to ensure player model is loaded
            scheduleUpload(LOGIN_DELAY_MS);
        }
    }

    /**
     * Handles equipment changes.
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (!config.enableModelUploads()) {
            return;
        }

        // Only track equipment container (ID 94)
        if (event.getContainerId() != InventoryID.EQUIPMENT.getId()) {
            return;
        }

        // Debounce: schedule upload after changes settle
        scheduleDebounced();
    }

    /**
     * Handles NPC spawns to detect pets.
     */
    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        if (!config.enableModelUploads() || !config.includePlayerPet()) {
            return;
        }

        NPC npc = event.getNpc();
        if (isPetNpc(npc)) {
            currentPet = npc;
            log.debug("Pet detected: {}", npc.getName());
            scheduleDebounced();
        }
    }

    /**
     * Handles NPC despawns to clear pet reference.
     */
    @Subscribe
    public void onNpcDespawned(NpcDespawned event) {
        if (currentPet != null && currentPet == event.getNpc()) {
            currentPet = null;
            log.debug("Pet despawned");
        }
    }

    /**
     * Determines if an NPC is the player's pet (follower).
     */
    private boolean isPetNpc(NPC npc) {
        if (npc == null || client.getLocalPlayer() == null) {
            return false;
        }

        // Check if NPC is interacting with (following) the local player
        Actor interacting = npc.getInteracting();
        if (interacting != client.getLocalPlayer()) {
            return false;
        }

        // Additional heuristics: pets typically have small size
        NPCComposition composition = npc.getComposition();
        if (composition == null) {
            return false;
        }

        // Check if it's a follower-type NPC (size 1-2)
        int size = composition.getSize();
        if (size > 2) {
            return false;
        }

        // Check proximity to player
        Player player = client.getLocalPlayer();
        int dx = Math.abs(npc.getWorldLocation().getX() - player.getWorldLocation().getX());
        int dy = Math.abs(npc.getWorldLocation().getY() - player.getWorldLocation().getY());
        return dx <= 2 && dy <= 2;
    }

    /**
     * Schedules a debounced upload after equipment/pet changes settle.
     */
    private void scheduleDebounced() {
        if (executor == null || executor.isShutdown()) {
            return;
        }

        pendingUpload.set(true);

        // Cancel any pending debounce task
        if (debounceTask != null) {
            debounceTask.cancel(false);
        }

        debounceTask = executor.schedule(() -> {
            if (pendingUpload.getAndSet(false)) {
                triggerUpload();
            }
        }, EQUIPMENT_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules an upload after a delay.
     */
    private void scheduleUpload(long delayMs) {
        if (executor == null || executor.isShutdown()) {
            return;
        }

        executor.schedule(this::triggerUpload, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Triggers a model upload with rate limiting.
     */
    public void triggerUpload() {
        log.info("[ModelUpload] triggerUpload() called, enableModelUploads={}", config.enableModelUploads());

        if (!config.enableModelUploads()) {
            log.info("[ModelUpload] Model uploads disabled in config, skipping");
            return;
        }

        // Rate limiting check
        long now = System.currentTimeMillis();
        long lastUpload = lastUploadTime.get();
        if (now - lastUpload < UPLOAD_COOLDOWN_MS) {
            log.info("[ModelUpload] Rate limited, {} ms remaining",
                    UPLOAD_COOLDOWN_MS - (now - lastUpload));
            return;
        }

        log.info("[ModelUpload] Scheduling upload on client thread");

        // Extract and upload on client thread
        clientThread.invokeLater(() -> {
            if (client == null || client.getLocalPlayer() == null) {
                log.info("[ModelUpload] Client or local player is null, retrying...");
                return false;
            }

            if (client.getGameState() != GameState.LOGGED_IN) {
                log.info("[ModelUpload] Game state is {}, retrying...", client.getGameState());
                return false;
            }

            log.info("[ModelUpload] Performing upload for player: {}", client.getLocalPlayer().getName());
            performUpload();
            return true;
        });
    }

    /**
     * Performs the actual model extraction and upload.
     * MUST be called on the client thread.
     */
    private void performUpload() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            log.info("[ModelUpload] performUpload() - localPlayer is null");
            return;
        }

        String rsn = localPlayer.getName();
        if (rsn == null || rsn.isEmpty()) {
            log.info("[ModelUpload] performUpload() - RSN is null or empty");
            return;
        }

        log.info("[ModelUpload] Extracting model for player: {}", rsn);

        // Extract player model
        Model playerModel = localPlayer.getModel();
        if (playerModel == null) {
            log.warn("[ModelUpload] Player model is null - cannot extract");
            return;
        }

        log.info("[ModelUpload] Player model found, vertices={}, faces={}",
                playerModel.getVerticesCount(), playerModel.getFaceCount());

        ModelData playerModelData = extractModelData(playerModel);
        if (playerModelData == null || !playerModelData.isValid()) {
            log.warn("[ModelUpload] Failed to extract player model data");
            return;
        }

        log.info("[ModelUpload] Model data extracted successfully");

        byte[] playerPly = PlyExporter.export(playerModelData);
        if (playerPly == null) {
            log.warn("[ModelUpload] Failed to export player PLY");
            return;
        }

        log.info("[ModelUpload] PLY export successful, size={} bytes", playerPly.length);

        // Extract pet model if enabled and available
        byte[] petPly = null;
        if (config.includePlayerPet() && currentPet != null) {
            log.info("[ModelUpload] Extracting pet model: {}", currentPet.getName());
            Model petModel = currentPet.getModel();
            if (petModel != null) {
                ModelData petModelData = extractModelData(petModel);
                if (petModelData != null && petModelData.isValid()) {
                    petPly = PlyExporter.export(petModelData);
                    log.info("[ModelUpload] Pet PLY export successful, size={} bytes", petPly.length);
                }
            }
        }

        // Upload asynchronously
        final byte[] finalPlayerPly = playerPly;
        final byte[] finalPetPly = petPly;

        if (executor != null && !executor.isShutdown()) {
            log.info("[ModelUpload] Queueing upload to API...");
            executor.execute(() -> uploadModels(rsn, finalPlayerPly, finalPetPly));
        } else {
            log.warn("[ModelUpload] Executor is null or shutdown, cannot upload");
        }
    }

    /**
     * Extracts model data with vertex colors from a RuneLite Model object.
     * Creates "exploded" vertices (3 per face) to support per-face-vertex colors.
     */
    private ModelData extractModelData(Model model) {
        if (model == null) {
            return null;
        }

        try {
            int originalVertexCount = model.getVerticesCount();
            int faceCount = model.getFaceCount();

            if (originalVertexCount <= 0 || faceCount <= 0) {
                return null;
            }

            // Get original vertex arrays (RuneLite uses float coordinates)
            float[] srcVerticesX = model.getVerticesX();
            float[] srcVerticesY = model.getVerticesY();
            float[] srcVerticesZ = model.getVerticesZ();

            // Get face vertex indices
            int[] trianglesX = model.getFaceIndices1();
            int[] trianglesY = model.getFaceIndices2();
            int[] trianglesZ = model.getFaceIndices3();

            // Get face color data (RuneLite uses int arrays for colors)
            int[] faceColors1 = model.getFaceColors1();
            int[] faceColors2 = model.getFaceColors2();
            int[] faceColors3 = model.getFaceColors3();
            short[] faceTextures = model.getFaceTextures();

            // Create exploded vertex arrays (3 vertices per face)
            int explodedVertexCount = faceCount * 3;
            short[] verticesX = new short[explodedVertexCount];
            short[] verticesY = new short[explodedVertexCount];
            short[] verticesZ = new short[explodedVertexCount];
            byte[] colorsR = new byte[explodedVertexCount];
            byte[] colorsG = new byte[explodedVertexCount];
            byte[] colorsB = new byte[explodedVertexCount];

            // Face indices for exploded mesh (sequential: 0,1,2 for face 0, 3,4,5 for face 1, etc.)
            int[] faceIndices1 = new int[faceCount];
            int[] faceIndices2 = new int[faceCount];
            int[] faceIndices3 = new int[faceCount];

            for (int face = 0; face < faceCount; face++) {
                int v1Idx = trianglesX[face];
                int v2Idx = trianglesY[face];
                int v3Idx = trianglesZ[face];

                // Output vertex indices in exploded mesh
                int outIdx1 = face * 3;
                int outIdx2 = face * 3 + 1;
                int outIdx3 = face * 3 + 2;

                // Copy vertex positions with coordinate flip (y = -y, z = -z)
                verticesX[outIdx1] = clampToShort((int) srcVerticesX[v1Idx]);
                verticesY[outIdx1] = clampToShort((int) -srcVerticesY[v1Idx]);
                verticesZ[outIdx1] = clampToShort((int) -srcVerticesZ[v1Idx]);

                verticesX[outIdx2] = clampToShort((int) srcVerticesX[v2Idx]);
                verticesY[outIdx2] = clampToShort((int) -srcVerticesY[v2Idx]);
                verticesZ[outIdx2] = clampToShort((int) -srcVerticesZ[v2Idx]);

                verticesX[outIdx3] = clampToShort((int) srcVerticesX[v3Idx]);
                verticesY[outIdx3] = clampToShort((int) -srcVerticesY[v3Idx]);
                verticesZ[outIdx3] = clampToShort((int) -srcVerticesZ[v3Idx]);

                // Determine colors for each vertex of this face
                int color1, color2, color3;

                // Check if textured face
                if (faceTextures != null && face < faceTextures.length && faceTextures[face] != -1) {
                    // Textured face: use average texture color for all vertices
                    int textureColor = TextureColor.getTextureColor(client, faceTextures[face]);
                    color1 = textureColor;
                    color2 = textureColor;
                    color3 = textureColor;
                } else if (faceColors3 != null && face < faceColors3.length && faceColors3[face] == -1) {
                    // Flat-shaded face: use faceColors1 for all vertices
                    short hsl = (faceColors1 != null && face < faceColors1.length) ? (short) faceColors1[face] : 0;
                    int flatColor = JagexColor.HSLtoRGB(hsl);
                    color1 = flatColor;
                    color2 = flatColor;
                    color3 = flatColor;
                } else {
                    // Gouraud-shaded face: use different color for each vertex
                    short hsl1 = (faceColors1 != null && face < faceColors1.length) ? (short) faceColors1[face] : 0;
                    short hsl2 = (faceColors2 != null && face < faceColors2.length) ? (short) faceColors2[face] : 0;
                    short hsl3 = (faceColors3 != null && face < faceColors3.length) ? (short) faceColors3[face] : 0;
                    color1 = JagexColor.HSLtoRGB(hsl1);
                    color2 = JagexColor.HSLtoRGB(hsl2);
                    color3 = JagexColor.HSLtoRGB(hsl3);
                }

                // Extract RGB components and store
                colorsR[outIdx1] = (byte) JagexColor.getRed(color1);
                colorsG[outIdx1] = (byte) JagexColor.getGreen(color1);
                colorsB[outIdx1] = (byte) JagexColor.getBlue(color1);

                colorsR[outIdx2] = (byte) JagexColor.getRed(color2);
                colorsG[outIdx2] = (byte) JagexColor.getGreen(color2);
                colorsB[outIdx2] = (byte) JagexColor.getBlue(color2);

                colorsR[outIdx3] = (byte) JagexColor.getRed(color3);
                colorsG[outIdx3] = (byte) JagexColor.getGreen(color3);
                colorsB[outIdx3] = (byte) JagexColor.getBlue(color3);

                // Set face indices (sequential in exploded mesh)
                faceIndices1[face] = outIdx1;
                faceIndices2[face] = outIdx2;
                faceIndices3[face] = outIdx3;
            }

            return ModelData.builder()
                    .verticesX(verticesX)
                    .verticesY(verticesY)
                    .verticesZ(verticesZ)
                    .vertexColorsR(colorsR)
                    .vertexColorsG(colorsG)
                    .vertexColorsB(colorsB)
                    .faceIndices1(faceIndices1)
                    .faceIndices2(faceIndices2)
                    .faceIndices3(faceIndices3)
                    .vertexCount(explodedVertexCount)
                    .faceCount(faceCount)
                    .build();

        } catch (Exception e) {
            log.error("Error extracting model data", e);
            return null;
        }
    }

    /**
     * Clamps an int value to short range.
     */
    private static short clampToShort(int value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    /**
     * Uploads model files to the API via multipart POST.
     */
    private void uploadModels(String rsn, byte[] playerPly, byte[] petPly) {
        log.info("[ModelUpload] uploadModels() called for RSN: {}, playerPly={} bytes, petPly={} bytes",
                rsn, playerPly.length, petPly != null ? petPly.length : 0);

        try {
            MultipartBody.Builder builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("rsn", rsn)
                    .addFormDataPart("playerModel", "player.ply",
                            RequestBody.create(MediaType.parse("application/octet-stream"), playerPly));

            if (petPly != null) {
                builder.addFormDataPart("petModel", "pet.ply",
                        RequestBody.create(MediaType.parse("application/octet-stream"), petPly));
            }

            RequestBody requestBody = builder.build();

            Request request = new Request.Builder()
                    .url(MODEL_UPLOAD_ENDPOINT)
                    .post(requestBody)
                    .build();

            log.info("[ModelUpload] Sending POST to {}", MODEL_UPLOAD_ENDPOINT);

            try (Response response = okHttpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    lastUploadTime.set(System.currentTimeMillis());
                    String body = response.body() != null ? response.body().string() : "";
                    log.info("[ModelUpload] SUCCESS - Uploaded 3D models for {} (player: {} bytes{}). Response: {}",
                            rsn, playerPly.length,
                            petPly != null ? ", pet: " + petPly.length + " bytes" : "",
                            body);
                } else {
                    String body = response.body() != null ? response.body().string() : "";
                    log.warn("[ModelUpload] FAILED - Status: {}, Response: {}", response.code(), body);
                }
            }

        } catch (IOException e) {
            log.error("[ModelUpload] ERROR - Exception during upload: {}", e.getMessage(), e);
        }
    }

    /**
     * Manually triggers an upload (for UI button).
     * Bypasses the rate limit.
     */
    public void manualUpload() {
        if (!config.enableModelUploads()) {
            log.debug("Model uploads disabled, skipping manual upload");
            return;
        }

        // Reset rate limit for manual trigger
        lastUploadTime.set(0);
        triggerUpload();
    }
}
