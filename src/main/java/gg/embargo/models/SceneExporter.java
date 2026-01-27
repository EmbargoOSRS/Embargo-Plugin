package gg.embargo.models;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Exports the surrounding scene around the player as a combined PLY model.
 * Includes terrain with heights, game objects, wall objects, ground objects, and decorative objects.
 */
@Slf4j
public class SceneExporter {

    private static final int TILE_RADIUS = 5; // 11x11 tiles around player
    private static final int LOCAL_TILE_SIZE = 128; // RuneLite tile size in local units
    private static final int DEFAULT_GROUND_COLOR = 0x4B3C2A; // Default brown for missing colors

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

        // Get tile heights for terrain elevation
        int[][][] tileHeights = scene.getTileHeights();

        // Get player's scene position
        LocalPoint playerLocal = player.getLocalLocation();
        int playerSceneX = playerLocal.getSceneX();
        int playerSceneY = playerLocal.getSceneY();
        int playerPlane = client.getPlane();

        // Player's local coordinates (center point for relative positioning)
        int playerLocalX = playerLocal.getX();
        int playerLocalY = playerLocal.getY();

        // Get player's height for reference
        int playerHeight = (tileHeights != null && playerPlane < tileHeights.length
                && playerSceneX < tileHeights[playerPlane].length
                && playerSceneY < tileHeights[playerPlane][playerSceneX].length)
                ? tileHeights[playerPlane][playerSceneX][playerSceneY] : 0;

        log.info("[SceneExport] Player at scene ({}, {}), plane {}, height {}",
                playerSceneX, playerSceneY, playerPlane, playerHeight);
        log.info("[SceneExport] Player local coords: ({}, {})", playerLocalX, playerLocalY);
        log.info("[SceneExport] Scene size: {}, searching radius: {}", Constants.SCENE_SIZE, TILE_RADIUS);

        List<ExtractedModel> extractedModels = new ArrayList<>();
        List<TerrainTriangle> terrainTriangles = new ArrayList<>();

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

                boolean hasObjects = false;
                boolean hasTerrain = false;

                // Extract terrain - prefer SceneTileModel over SceneTilePaint
                SceneTileModel tileModel = tile.getSceneTileModel();
                if (tileModel != null) {
                    List<TerrainTriangle> modelTerrain = extractTileModel(
                            tileModel, sceneX, sceneY, playerLocalX, playerLocalY, playerHeight);
                    if (!modelTerrain.isEmpty()) {
                        terrainTriangles.addAll(modelTerrain);
                        hasTerrain = true;
                    }
                } else {
                    // Fall back to SceneTilePaint for simple tiles
                    SceneTilePaint paint = tile.getSceneTilePaint();
                    if (paint != null) {
                        List<TerrainTriangle> paintTerrain = extractTilePaint(
                                paint, sceneX, sceneY, tileHeights, playerPlane,
                                playerLocalX, playerLocalY, playerHeight);
                        if (!paintTerrain.isEmpty()) {
                            terrainTriangles.addAll(paintTerrain);
                            hasTerrain = true;
                        }
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
        log.info("[SceneExport] Extracted {} object models, {} terrain triangles",
                extractedModels.size(), terrainTriangles.size());

        // Combine terrain and objects
        if (extractedModels.isEmpty() && terrainTriangles.isEmpty()) {
            log.warn("[SceneExport] No models or terrain extracted");
            return null;
        }

        return combineAllData(client, extractedModels, terrainTriangles);
    }

    /**
     * Extracts terrain from a SceneTilePaint (simple tile) with proper heights.
     */
    private static List<TerrainTriangle> extractTilePaint(SceneTilePaint paint, int sceneX, int sceneY,
                                                           int[][][] tileHeights, int plane,
                                                           int playerLocalX, int playerLocalY, int playerHeight) {
        List<TerrainTriangle> triangles = new ArrayList<>();

        int swColor = paint.getSwColor();
        int seColor = paint.getSeColor();
        int nwColor = paint.getNwColor();
        int neColor = paint.getNeColor();

        // Check if this is a textured tile
        int texture = paint.getTexture();

        // Get heights at each corner
        int swHeight = getHeight(tileHeights, plane, sceneX, sceneY) - playerHeight;
        int seHeight = getHeight(tileHeights, plane, sceneX + 1, sceneY) - playerHeight;
        int nwHeight = getHeight(tileHeights, plane, sceneX, sceneY + 1) - playerHeight;
        int neHeight = getHeight(tileHeights, plane, sceneX + 1, sceneY + 1) - playerHeight;

        // Calculate positions relative to player
        int baseX = sceneX * LOCAL_TILE_SIZE - playerLocalX;
        int baseY = sceneY * LOCAL_TILE_SIZE - playerLocalY;

        // SW corner
        int swX = baseX;
        int swZ = baseY;
        int swY = -swHeight; // Negate for coordinate system

        // SE corner
        int seX = baseX + LOCAL_TILE_SIZE;
        int seZ = baseY;
        int seY = -seHeight;

        // NW corner
        int nwX = baseX;
        int nwZ = baseY + LOCAL_TILE_SIZE;
        int nwY = -nwHeight;

        // NE corner
        int neX = baseX + LOCAL_TILE_SIZE;
        int neZ = baseY + LOCAL_TILE_SIZE;
        int neY = -neHeight;

        // Convert colors - use default if 0
        int swRgb = (swColor != 0) ? JagexColor.HSLtoRGB((short) swColor) : DEFAULT_GROUND_COLOR;
        int seRgb = (seColor != 0) ? JagexColor.HSLtoRGB((short) seColor) : DEFAULT_GROUND_COLOR;
        int nwRgb = (nwColor != 0) ? JagexColor.HSLtoRGB((short) nwColor) : DEFAULT_GROUND_COLOR;
        int neRgb = (neColor != 0) ? JagexColor.HSLtoRGB((short) neColor) : DEFAULT_GROUND_COLOR;

        // Skip only if completely invisible (all colors 0 AND no texture)
        if (swColor == 0 && seColor == 0 && nwColor == 0 && neColor == 0 && texture == -1) {
            return triangles;
        }

        // If textured, try to get texture color
        if (texture != -1) {
            // For now, just use the tile colors - texture support can be added later
        }

        // Triangle 1: SW, SE, NE
        triangles.add(new TerrainTriangle(
                swX, swY, swZ, swRgb,
                seX, seY, seZ, seRgb,
                neX, neY, neZ, neRgb
        ));

        // Triangle 2: SW, NE, NW
        triangles.add(new TerrainTriangle(
                swX, swY, swZ, swRgb,
                neX, neY, neZ, neRgb,
                nwX, nwY, nwZ, nwRgb
        ));

        return triangles;
    }

    /**
     * Extracts terrain from a SceneTileModel (complex terrain with height variation).
     */
    private static List<TerrainTriangle> extractTileModel(SceneTileModel model, int sceneX, int sceneY,
                                                           int playerLocalX, int playerLocalY, int playerHeight) {
        List<TerrainTriangle> triangles = new ArrayList<>();

        int[] faceX = model.getFaceX();
        int[] faceY = model.getFaceY();
        int[] faceZ = model.getFaceZ();
        int[] vertexX = model.getVertexX();
        int[] vertexY = model.getVertexY();
        int[] vertexZ = model.getVertexZ();
        int[] colorA = model.getTriangleColorA();
        int[] colorB = model.getTriangleColorB();
        int[] colorC = model.getTriangleColorC();

        if (faceX == null || vertexX == null) {
            return triangles;
        }

        // Calculate base offset
        int baseX = sceneX * LOCAL_TILE_SIZE - playerLocalX;
        int baseZ = sceneY * LOCAL_TILE_SIZE - playerLocalY;

        for (int i = 0; i < faceX.length; i++) {
            int v1 = faceX[i];
            int v2 = faceY[i];
            int v3 = faceZ[i];

            if (v1 >= vertexX.length || v2 >= vertexX.length || v3 >= vertexX.length) {
                continue;
            }

            // Get colors for each vertex
            int c1 = (colorA != null && i < colorA.length) ? colorA[i] : 0;
            int c2 = (colorB != null && i < colorB.length) ? colorB[i] : 0;
            int c3 = (colorC != null && i < colorC.length) ? colorC[i] : 0;

            // Convert to RGB
            int rgb1 = (c1 != 0) ? JagexColor.HSLtoRGB((short) c1) : DEFAULT_GROUND_COLOR;
            int rgb2 = (c2 != 0) ? JagexColor.HSLtoRGB((short) c2) : DEFAULT_GROUND_COLOR;
            int rgb3 = (c3 != 0) ? JagexColor.HSLtoRGB((short) c3) : DEFAULT_GROUND_COLOR;

            // Skip if all colors are 0
            if (c1 == 0 && c2 == 0 && c3 == 0) {
                continue;
            }

            // Vertex positions - note the coordinate transforms:
            // vertexX is local X offset within tile
            // vertexZ is local Z offset within tile (RuneLite's Y on ground plane)
            // vertexY is height (negative in RuneLite = up)
            int x1 = baseX + vertexX[v1];
            int y1 = -(vertexY[v1] + playerHeight);
            int z1 = baseZ + vertexZ[v1];

            int x2 = baseX + vertexX[v2];
            int y2 = -(vertexY[v2] + playerHeight);
            int z2 = baseZ + vertexZ[v2];

            int x3 = baseX + vertexX[v3];
            int y3 = -(vertexY[v3] + playerHeight);
            int z3 = baseZ + vertexZ[v3];

            triangles.add(new TerrainTriangle(
                    x1, y1, z1, rgb1,
                    x2, y2, z2, rgb2,
                    x3, y3, z3, rgb3
            ));
        }

        return triangles;
    }

    /**
     * Gets tile height with bounds checking.
     */
    private static int getHeight(int[][][] tileHeights, int plane, int x, int y) {
        if (tileHeights == null) return 0;
        if (plane < 0 || plane >= tileHeights.length) return 0;
        if (x < 0 || x >= tileHeights[plane].length) return 0;
        if (y < 0 || y >= tileHeights[plane][x].length) return 0;
        return tileHeights[plane][x][y];
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
                                            List<TerrainTriangle> terrainTriangles) {
        // Count faces
        int objectFaces = 0;
        for (ExtractedModel em : objectModels) {
            objectFaces += em.model.getFaceCount();
        }

        int terrainFaces = terrainTriangles.size();
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

                colorsR[outIdx1] = (byte) ((color1 >> 16) & 0xFF);
                colorsG[outIdx1] = (byte) ((color1 >> 8) & 0xFF);
                colorsB[outIdx1] = (byte) (color1 & 0xFF);

                colorsR[outIdx2] = (byte) ((color2 >> 16) & 0xFF);
                colorsG[outIdx2] = (byte) ((color2 >> 8) & 0xFF);
                colorsB[outIdx2] = (byte) (color2 & 0xFF);

                colorsR[outIdx3] = (byte) ((color3 >> 16) & 0xFF);
                colorsG[outIdx3] = (byte) ((color3 >> 8) & 0xFF);
                colorsB[outIdx3] = (byte) (color3 & 0xFF);

                faceIndices1[faceOffset + face] = outIdx1;
                faceIndices2[faceOffset + face] = outIdx2;
                faceIndices3[faceOffset + face] = outIdx3;
            }

            vertexOffset += faceCount * 3;
            faceOffset += faceCount;
        }

        // Add terrain triangles
        for (TerrainTriangle t : terrainTriangles) {
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

            colorsR[outIdx1] = (byte) ((t.color1 >> 16) & 0xFF);
            colorsG[outIdx1] = (byte) ((t.color1 >> 8) & 0xFF);
            colorsB[outIdx1] = (byte) (t.color1 & 0xFF);

            colorsR[outIdx2] = (byte) ((t.color2 >> 16) & 0xFF);
            colorsG[outIdx2] = (byte) ((t.color2 >> 8) & 0xFF);
            colorsB[outIdx2] = (byte) (t.color2 & 0xFF);

            colorsR[outIdx3] = (byte) ((t.color3 >> 16) & 0xFF);
            colorsG[outIdx3] = (byte) ((t.color3 >> 8) & 0xFF);
            colorsB[outIdx3] = (byte) (t.color3 & 0xFF);

            faceIndices1[faceOffset] = outIdx1;
            faceIndices2[faceOffset] = outIdx2;
            faceIndices3[faceOffset] = outIdx3;

            vertexOffset += 3;
            faceOffset += 1;
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
     * Helper class to hold a terrain triangle with per-vertex positions and colors.
     */
    private static class TerrainTriangle {
        final int x1, y1, z1, color1;
        final int x2, y2, z2, color2;
        final int x3, y3, z3, color3;

        TerrainTriangle(int x1, int y1, int z1, int color1,
                        int x2, int y2, int z2, int color2,
                        int x3, int y3, int z3, int color3) {
            this.x1 = x1; this.y1 = y1; this.z1 = z1; this.color1 = color1;
            this.x2 = x2; this.y2 = y2; this.z2 = z2; this.color2 = color2;
            this.x3 = x3; this.y3 = y3; this.z3 = z3; this.color3 = color3;
        }
    }
}
