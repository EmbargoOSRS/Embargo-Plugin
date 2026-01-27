package gg.embargo.models;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Exports the surrounding scene around the player as a combined PLY model.
 * Includes terrain, game objects, wall objects, ground objects, and decorative objects.
 */
@Slf4j
public class SceneExporter {

    private static final int TILE_RADIUS = 5; // 11x11 tiles around player
    private static final int LOCAL_TILE_SIZE = 128; // RuneLite tile size in local units

    /**
     * Extracts the scene around the player as a combined ModelData.
     *
     * @param client the game client
     * @return combined ModelData of all scene objects, or null if extraction fails
     */
    public static ModelData extractSceneAroundPlayer(Client client) {
        Player player = client.getLocalPlayer();
        if (player == null) {
            log.warn("[SceneExport] No local player");
            return null;
        }

        Scene scene = client.getScene();
        if (scene == null) {
            log.warn("[SceneExport] No scene available");
            return null;
        }

        Tile[][][] tiles = scene.getTiles();
        if (tiles == null) {
            log.warn("[SceneExport] No tiles available");
            return null;
        }

        // Get player's scene position
        LocalPoint playerLocal = player.getLocalLocation();
        int playerSceneX = playerLocal.getSceneX();
        int playerSceneY = playerLocal.getSceneY();
        int playerPlane = client.getPlane();

        // Player's local coordinates (center point for relative positioning)
        int playerLocalX = playerLocal.getX();
        int playerLocalY = playerLocal.getY();

        log.info("[SceneExport] Player at scene ({}, {}), plane {}", playerSceneX, playerSceneY, playerPlane);
        log.info("[SceneExport] Player local coords: ({}, {})", playerLocalX, playerLocalY);
        log.info("[SceneExport] Scene size: {}, searching radius: {}", Constants.SCENE_SIZE, TILE_RADIUS);

        List<ExtractedModel> extractedModels = new ArrayList<>();
        List<TerrainTile> terrainTiles = new ArrayList<>();

        int tilesChecked = 0;
        int tilesWithObjects = 0;
        int tilesWithTerrain = 0;
        int totalGameObjects = 0;
        int totalWallObjects = 0;
        int totalGroundObjects = 0;
        int totalDecorativeObjects = 0;

        // Iterate over tiles around player
        for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
            for (int dy = -TILE_RADIUS; dy <= TILE_RADIUS; dy++) {
                int sceneX = playerSceneX + dx;
                int sceneY = playerSceneY + dy;

                // Check bounds
                if (sceneX < 0 || sceneX >= Constants.SCENE_SIZE ||
                    sceneY < 0 || sceneY >= Constants.SCENE_SIZE) {
                    continue;
                }

                tilesChecked++;
                Tile tile = tiles[playerPlane][sceneX][sceneY];
                if (tile == null) {
                    continue;
                }

                // Calculate tile position relative to player
                int tileBaseX = sceneX * LOCAL_TILE_SIZE;
                int tileBaseY = sceneY * LOCAL_TILE_SIZE;

                boolean hasObjects = false;
                boolean hasTerrain = false;

                // Extract terrain (SceneTilePaint for simple tiles)
                SceneTilePaint paint = tile.getSceneTilePaint();
                if (paint != null) {
                    TerrainTile terrain = extractTilePaint(paint, tileBaseX, tileBaseY, playerLocalX, playerLocalY);
                    if (terrain != null) {
                        terrainTiles.add(terrain);
                        hasTerrain = true;
                    }
                }

                // Extract terrain (SceneTileModel for complex tiles)
                SceneTileModel tileModel = tile.getSceneTileModel();
                if (tileModel != null) {
                    List<TerrainTile> modelTerrain = extractTileModel(tileModel, tileBaseX, tileBaseY, playerLocalX, playerLocalY);
                    if (!modelTerrain.isEmpty()) {
                        terrainTiles.addAll(modelTerrain);
                        hasTerrain = true;
                    }
                }

                if (hasTerrain) {
                    tilesWithTerrain++;
                }

                // Extract game objects
                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects != null) {
                    for (GameObject obj : gameObjects) {
                        if (obj == null) continue;
                        totalGameObjects++;
                        hasObjects = true;
                        extractGameObject(client, obj, playerLocalX, playerLocalY, extractedModels);
                    }
                }

                // Extract wall object
                WallObject wallObject = tile.getWallObject();
                if (wallObject != null) {
                    totalWallObjects++;
                    hasObjects = true;
                    extractWallObject(client, wallObject, playerLocalX, playerLocalY, extractedModels);
                }

                // Extract ground object
                GroundObject groundObject = tile.getGroundObject();
                if (groundObject != null) {
                    totalGroundObjects++;
                    hasObjects = true;
                    extractGroundObject(client, groundObject, playerLocalX, playerLocalY, extractedModels);
                }

                // Extract decorative object
                DecorativeObject decorativeObject = tile.getDecorativeObject();
                if (decorativeObject != null) {
                    totalDecorativeObjects++;
                    hasObjects = true;
                    extractDecorativeObject(client, decorativeObject, playerLocalX, playerLocalY, extractedModels);
                }

                if (hasObjects) {
                    tilesWithObjects++;
                }
            }
        }

        log.info("[SceneExport] Tiles checked: {}, with objects: {}, with terrain: {}",
                tilesChecked, tilesWithObjects, tilesWithTerrain);
        log.info("[SceneExport] Found objects - GameObjects: {}, WallObjects: {}, GroundObjects: {}, DecorativeObjects: {}",
                totalGameObjects, totalWallObjects, totalGroundObjects, totalDecorativeObjects);
        log.info("[SceneExport] Extracted {} object models, {} terrain tiles", extractedModels.size(), terrainTiles.size());

        // Combine terrain and objects
        if (extractedModels.isEmpty() && terrainTiles.isEmpty()) {
            log.warn("[SceneExport] No models or terrain extracted");
            return null;
        }

        return combineAllData(client, extractedModels, terrainTiles);
    }

    /**
     * Extracts terrain from a SceneTilePaint (simple flat tile).
     */
    private static TerrainTile extractTilePaint(SceneTilePaint paint, int tileBaseX, int tileBaseY,
                                                 int playerLocalX, int playerLocalY) {
        int swColor = paint.getSwColor();
        int seColor = paint.getSeColor();
        int nwColor = paint.getNwColor();
        int neColor = paint.getNeColor();

        // Skip if all colors are 0 (invisible/water)
        if (swColor == 0 && seColor == 0 && nwColor == 0 && neColor == 0) {
            return null;
        }

        int offsetX = tileBaseX - playerLocalX;
        int offsetY = tileBaseY - playerLocalY;

        return new TerrainTile(offsetX, offsetY, 0, LOCAL_TILE_SIZE,
                swColor, seColor, nwColor, neColor);
    }

    /**
     * Extracts terrain from a SceneTileModel (complex terrain with height variation).
     */
    private static List<TerrainTile> extractTileModel(SceneTileModel model, int tileBaseX, int tileBaseY,
                                                       int playerLocalX, int playerLocalY) {
        List<TerrainTile> tiles = new ArrayList<>();

        int[] faceX = model.getFaceX();
        int[] faceY = model.getFaceY();
        int[] faceZ = model.getFaceZ();
        int[] vertexX = model.getVertexX();
        int[] vertexY = model.getVertexY();
        int[] vertexZ = model.getVertexZ();
        int[] faceColors = model.getTriangleColorA();

        if (faceX == null || vertexX == null || faceColors == null) {
            return tiles;
        }

        int offsetX = tileBaseX - playerLocalX;
        int offsetY = tileBaseY - playerLocalY;

        for (int i = 0; i < faceX.length; i++) {
            int v1 = faceX[i];
            int v2 = faceY[i];
            int v3 = faceZ[i];

            if (v1 >= vertexX.length || v2 >= vertexX.length || v3 >= vertexX.length) {
                continue;
            }

            int color = (i < faceColors.length) ? faceColors[i] : 0;
            if (color == 0) continue;

            TerrainTile tile = new TerrainTile(
                    offsetX + vertexX[v1], offsetY + vertexZ[v1], -vertexY[v1],
                    offsetX + vertexX[v2], offsetY + vertexZ[v2], -vertexY[v2],
                    offsetX + vertexX[v3], offsetY + vertexZ[v3], -vertexY[v3],
                    color
            );
            tiles.add(tile);
        }

        return tiles;
    }

    private static void extractGameObject(Client client, GameObject obj, int playerLocalX, int playerLocalY,
                                          List<ExtractedModel> models) {
        Renderable renderable = obj.getRenderable();
        if (renderable == null) {
            return;
        }

        Model model = getModelFromRenderable(renderable);
        if (model == null || model.getFaceCount() == 0) {
            return;
        }

        LocalPoint loc = obj.getLocalLocation();
        if (loc == null) return;

        int offsetX = loc.getX() - playerLocalX;
        int offsetY = loc.getY() - playerLocalY;

        log.debug("[SceneExport] Extracted GameObject ID {} at offset ({}, {}), faces={}",
                obj.getId(), offsetX, offsetY, model.getFaceCount());
        models.add(new ExtractedModel(model, offsetX, offsetY, 0));
    }

    private static void extractWallObject(Client client, WallObject obj, int playerLocalX, int playerLocalY,
                                          List<ExtractedModel> models) {
        Renderable renderable1 = obj.getRenderable1();
        Renderable renderable2 = obj.getRenderable2();

        LocalPoint loc = obj.getLocalLocation();
        if (loc == null) return;

        int offsetX = loc.getX() - playerLocalX;
        int offsetY = loc.getY() - playerLocalY;

        if (renderable1 != null) {
            Model model = getModelFromRenderable(renderable1);
            if (model != null && model.getFaceCount() > 0) {
                models.add(new ExtractedModel(model, offsetX, offsetY, 0));
            }
        }

        if (renderable2 != null) {
            Model model = getModelFromRenderable(renderable2);
            if (model != null && model.getFaceCount() > 0) {
                models.add(new ExtractedModel(model, offsetX, offsetY, 0));
            }
        }
    }

    private static void extractGroundObject(Client client, GroundObject obj, int playerLocalX, int playerLocalY,
                                            List<ExtractedModel> models) {
        Renderable renderable = obj.getRenderable();
        if (renderable == null) return;

        Model model = getModelFromRenderable(renderable);
        if (model == null || model.getFaceCount() == 0) return;

        LocalPoint loc = obj.getLocalLocation();
        if (loc == null) return;

        int offsetX = loc.getX() - playerLocalX;
        int offsetY = loc.getY() - playerLocalY;

        models.add(new ExtractedModel(model, offsetX, offsetY, 0));
    }

    private static void extractDecorativeObject(Client client, DecorativeObject obj, int playerLocalX, int playerLocalY,
                                                List<ExtractedModel> models) {
        Renderable renderable = obj.getRenderable();
        if (renderable == null) return;

        Model model = getModelFromRenderable(renderable);
        if (model == null || model.getFaceCount() == 0) return;

        LocalPoint loc = obj.getLocalLocation();
        if (loc == null) return;

        int offsetX = loc.getX() - playerLocalX;
        int offsetY = loc.getY() - playerLocalY;

        models.add(new ExtractedModel(model, offsetX, offsetY, 0));
    }

    /**
     * Combines all extracted data into a single ModelData.
     */
    private static ModelData combineAllData(Client client, List<ExtractedModel> objectModels,
                                            List<TerrainTile> terrainTiles) {
        // Count faces
        int objectFaces = 0;
        for (ExtractedModel em : objectModels) {
            objectFaces += em.model.getFaceCount();
        }

        int terrainFaces = 0;
        for (TerrainTile t : terrainTiles) {
            terrainFaces += t.isTriangle ? 1 : 2; // Quads become 2 triangles
        }

        int totalFaces = objectFaces + terrainFaces;
        int totalVertices = totalFaces * 3;

        log.info("[SceneExport] Combining: {} object faces + {} terrain faces = {} total",
                objectFaces, terrainFaces, totalFaces);

        if (totalFaces == 0) {
            return null;
        }

        // Allocate arrays
        short[] verticesX = new short[totalVertices];
        short[] verticesY = new short[totalVertices];
        short[] verticesZ = new short[totalVertices];
        byte[] colorsR = new byte[totalVertices];
        byte[] colorsG = new byte[totalVertices];
        byte[] colorsB = new byte[totalVertices];
        int[] faceIndices1 = new int[totalFaces];
        int[] faceIndices2 = new int[totalFaces];
        int[] faceIndices3 = new int[totalFaces];

        int vertexOffset = 0;
        int faceOffset = 0;

        // Add object models
        for (ExtractedModel em : objectModels) {
            Model model = em.model;
            int faceCount = model.getFaceCount();
            if (faceCount == 0) continue;

            float[] srcX = model.getVerticesX();
            float[] srcY = model.getVerticesY();
            float[] srcZ = model.getVerticesZ();

            int[] triX = model.getFaceIndices1();
            int[] triY = model.getFaceIndices2();
            int[] triZ = model.getFaceIndices3();

            int[] faceColors1 = model.getFaceColors1();
            int[] faceColors2 = model.getFaceColors2();
            int[] faceColors3 = model.getFaceColors3();
            short[] faceTextures = model.getFaceTextures();

            for (int face = 0; face < faceCount; face++) {
                int v1Idx = triX[face];
                int v2Idx = triY[face];
                int v3Idx = triZ[face];

                int outIdx1 = vertexOffset + face * 3;
                int outIdx2 = vertexOffset + face * 3 + 1;
                int outIdx3 = vertexOffset + face * 3 + 2;

                verticesX[outIdx1] = clampToShort((int) srcX[v1Idx] + em.offsetX);
                verticesY[outIdx1] = clampToShort((int) -srcY[v1Idx]);
                verticesZ[outIdx1] = clampToShort((int) srcZ[v1Idx] + em.offsetY);

                verticesX[outIdx2] = clampToShort((int) srcX[v2Idx] + em.offsetX);
                verticesY[outIdx2] = clampToShort((int) -srcY[v2Idx]);
                verticesZ[outIdx2] = clampToShort((int) srcZ[v2Idx] + em.offsetY);

                verticesX[outIdx3] = clampToShort((int) srcX[v3Idx] + em.offsetX);
                verticesY[outIdx3] = clampToShort((int) -srcY[v3Idx]);
                verticesZ[outIdx3] = clampToShort((int) srcZ[v3Idx] + em.offsetY);

                // Determine colors
                int color1, color2, color3;
                if (faceTextures != null && face < faceTextures.length && faceTextures[face] != -1) {
                    int textureColor = TextureColor.getTextureColor(client, faceTextures[face]);
                    color1 = color2 = color3 = textureColor;
                } else if (faceColors3 != null && face < faceColors3.length && faceColors3[face] == -1) {
                    short hsl = (faceColors1 != null && face < faceColors1.length) ? (short) faceColors1[face] : 0;
                    color1 = color2 = color3 = JagexColor.HSLtoRGB(hsl);
                } else {
                    short hsl1 = (faceColors1 != null && face < faceColors1.length) ? (short) faceColors1[face] : 0;
                    short hsl2 = (faceColors2 != null && face < faceColors2.length) ? (short) faceColors2[face] : 0;
                    short hsl3 = (faceColors3 != null && face < faceColors3.length) ? (short) faceColors3[face] : 0;
                    color1 = JagexColor.HSLtoRGB(hsl1);
                    color2 = JagexColor.HSLtoRGB(hsl2);
                    color3 = JagexColor.HSLtoRGB(hsl3);
                }

                colorsR[outIdx1] = (byte) JagexColor.getRed(color1);
                colorsG[outIdx1] = (byte) JagexColor.getGreen(color1);
                colorsB[outIdx1] = (byte) JagexColor.getBlue(color1);

                colorsR[outIdx2] = (byte) JagexColor.getRed(color2);
                colorsG[outIdx2] = (byte) JagexColor.getGreen(color2);
                colorsB[outIdx2] = (byte) JagexColor.getBlue(color2);

                colorsR[outIdx3] = (byte) JagexColor.getRed(color3);
                colorsG[outIdx3] = (byte) JagexColor.getGreen(color3);
                colorsB[outIdx3] = (byte) JagexColor.getBlue(color3);

                faceIndices1[faceOffset + face] = outIdx1;
                faceIndices2[faceOffset + face] = outIdx2;
                faceIndices3[faceOffset + face] = outIdx3;
            }

            vertexOffset += faceCount * 3;
            faceOffset += faceCount;
        }

        // Add terrain tiles
        for (TerrainTile t : terrainTiles) {
            if (t.isTriangle) {
                // Single triangle
                int outIdx1 = vertexOffset;
                int outIdx2 = vertexOffset + 1;
                int outIdx3 = vertexOffset + 2;

                verticesX[outIdx1] = clampToShort(t.x1);
                verticesY[outIdx1] = clampToShort(t.y1);
                verticesZ[outIdx1] = clampToShort(t.z1);

                verticesX[outIdx2] = clampToShort(t.x2);
                verticesY[outIdx2] = clampToShort(t.y2);
                verticesZ[outIdx2] = clampToShort(t.z2);

                verticesX[outIdx3] = clampToShort(t.x3);
                verticesY[outIdx3] = clampToShort(t.y3);
                verticesZ[outIdx3] = clampToShort(t.z3);

                int rgb = JagexColor.HSLtoRGB((short) t.color);
                byte r = (byte) JagexColor.getRed(rgb);
                byte g = (byte) JagexColor.getGreen(rgb);
                byte b = (byte) JagexColor.getBlue(rgb);

                colorsR[outIdx1] = colorsR[outIdx2] = colorsR[outIdx3] = r;
                colorsG[outIdx1] = colorsG[outIdx2] = colorsG[outIdx3] = g;
                colorsB[outIdx1] = colorsB[outIdx2] = colorsB[outIdx3] = b;

                faceIndices1[faceOffset] = outIdx1;
                faceIndices2[faceOffset] = outIdx2;
                faceIndices3[faceOffset] = outIdx3;

                vertexOffset += 3;
                faceOffset += 1;
            } else {
                // Quad - two triangles
                // Triangle 1: SW, SE, NE
                int outIdx1 = vertexOffset;
                int outIdx2 = vertexOffset + 1;
                int outIdx3 = vertexOffset + 2;

                // SW corner
                verticesX[outIdx1] = clampToShort(t.offsetX);
                verticesY[outIdx1] = 0;
                verticesZ[outIdx1] = clampToShort(t.offsetY);

                // SE corner
                verticesX[outIdx2] = clampToShort(t.offsetX + t.size);
                verticesY[outIdx2] = 0;
                verticesZ[outIdx2] = clampToShort(t.offsetY);

                // NE corner
                verticesX[outIdx3] = clampToShort(t.offsetX + t.size);
                verticesY[outIdx3] = 0;
                verticesZ[outIdx3] = clampToShort(t.offsetY + t.size);

                int rgb1 = JagexColor.HSLtoRGB((short) t.swColor);
                int rgb2 = JagexColor.HSLtoRGB((short) t.seColor);
                int rgb3 = JagexColor.HSLtoRGB((short) t.neColor);

                colorsR[outIdx1] = (byte) JagexColor.getRed(rgb1);
                colorsG[outIdx1] = (byte) JagexColor.getGreen(rgb1);
                colorsB[outIdx1] = (byte) JagexColor.getBlue(rgb1);

                colorsR[outIdx2] = (byte) JagexColor.getRed(rgb2);
                colorsG[outIdx2] = (byte) JagexColor.getGreen(rgb2);
                colorsB[outIdx2] = (byte) JagexColor.getBlue(rgb2);

                colorsR[outIdx3] = (byte) JagexColor.getRed(rgb3);
                colorsG[outIdx3] = (byte) JagexColor.getGreen(rgb3);
                colorsB[outIdx3] = (byte) JagexColor.getBlue(rgb3);

                faceIndices1[faceOffset] = outIdx1;
                faceIndices2[faceOffset] = outIdx2;
                faceIndices3[faceOffset] = outIdx3;

                // Triangle 2: SW, NE, NW
                int outIdx4 = vertexOffset + 3;
                int outIdx5 = vertexOffset + 4;
                int outIdx6 = vertexOffset + 5;

                // SW corner
                verticesX[outIdx4] = clampToShort(t.offsetX);
                verticesY[outIdx4] = 0;
                verticesZ[outIdx4] = clampToShort(t.offsetY);

                // NE corner
                verticesX[outIdx5] = clampToShort(t.offsetX + t.size);
                verticesY[outIdx5] = 0;
                verticesZ[outIdx5] = clampToShort(t.offsetY + t.size);

                // NW corner
                verticesX[outIdx6] = clampToShort(t.offsetX);
                verticesY[outIdx6] = 0;
                verticesZ[outIdx6] = clampToShort(t.offsetY + t.size);

                int rgb4 = JagexColor.HSLtoRGB((short) t.nwColor);

                colorsR[outIdx4] = (byte) JagexColor.getRed(rgb1);
                colorsG[outIdx4] = (byte) JagexColor.getGreen(rgb1);
                colorsB[outIdx4] = (byte) JagexColor.getBlue(rgb1);

                colorsR[outIdx5] = (byte) JagexColor.getRed(rgb3);
                colorsG[outIdx5] = (byte) JagexColor.getGreen(rgb3);
                colorsB[outIdx5] = (byte) JagexColor.getBlue(rgb3);

                colorsR[outIdx6] = (byte) JagexColor.getRed(rgb4);
                colorsG[outIdx6] = (byte) JagexColor.getGreen(rgb4);
                colorsB[outIdx6] = (byte) JagexColor.getBlue(rgb4);

                faceIndices1[faceOffset + 1] = outIdx4;
                faceIndices2[faceOffset + 1] = outIdx5;
                faceIndices3[faceOffset + 1] = outIdx6;

                vertexOffset += 6;
                faceOffset += 2;
            }
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
                .vertexCount(totalVertices)
                .faceCount(totalFaces)
                .build();
    }

    private static Model getModelFromRenderable(Renderable renderable) {
        if (renderable == null) {
            return null;
        }

        if (renderable instanceof Model) {
            return (Model) renderable;
        }

        try {
            return renderable.getModel();
        } catch (Exception e) {
            return null;
        }
    }

    private static short clampToShort(int value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    /**
     * Helper class to hold an extracted model with its position offset.
     */
    private static class ExtractedModel {
        final Model model;
        final int offsetX;
        final int offsetY;
        final int offsetZ;

        ExtractedModel(Model model, int offsetX, int offsetY, int offsetZ) {
            this.model = model;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }
    }

    /**
     * Helper class to hold terrain tile data.
     */
    private static class TerrainTile {
        final boolean isTriangle;
        // For quad tiles (from SceneTilePaint)
        final int offsetX, offsetY, size;
        final int swColor, seColor, nwColor, neColor;
        // For triangle tiles (from SceneTileModel)
        final int x1, y1, z1, x2, y2, z2, x3, y3, z3;
        final int color;

        // Quad constructor
        TerrainTile(int offsetX, int offsetY, int offsetZ, int size,
                    int swColor, int seColor, int nwColor, int neColor) {
            this.isTriangle = false;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.size = size;
            this.swColor = swColor;
            this.seColor = seColor;
            this.nwColor = nwColor;
            this.neColor = neColor;
            // Unused for quads
            this.x1 = this.y1 = this.z1 = 0;
            this.x2 = this.y2 = this.z2 = 0;
            this.x3 = this.y3 = this.z3 = 0;
            this.color = 0;
        }

        // Triangle constructor
        TerrainTile(int x1, int z1, int y1, int x2, int z2, int y2, int x3, int z3, int y3, int color) {
            this.isTriangle = true;
            this.x1 = x1; this.y1 = y1; this.z1 = z1;
            this.x2 = x2; this.y2 = y2; this.z2 = z2;
            this.x3 = x3; this.y3 = y3; this.z3 = z3;
            this.color = color;
            // Unused for triangles
            this.offsetX = this.offsetY = this.size = 0;
            this.swColor = this.seColor = this.nwColor = this.neColor = 0;
        }
    }
}
