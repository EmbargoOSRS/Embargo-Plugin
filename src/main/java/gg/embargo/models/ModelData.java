package gg.embargo.models;

import lombok.Builder;
import lombok.Data;

/**
 * Data class holding extracted 3D model vertex and face data with colors.
 * Used for exporting player/pet models to PLY format.
 *
 * Vertices are stored in "exploded" format - 3 vertices per face, not shared.
 * This allows per-face-vertex colors as required by OSRS model shading.
 * Coordinates are int16 (short) to match the PLY specification.
 */
@Data
@Builder
public class ModelData {
    // Vertex coordinates (int16/short per spec)
    private final short[] verticesX;
    private final short[] verticesY;
    private final short[] verticesZ;

    // Vertex colors (RGB, one per vertex)
    private final byte[] vertexColorsR;
    private final byte[] vertexColorsG;
    private final byte[] vertexColorsB;

    // Face indices (each face references 3 sequential vertices)
    private final int[] faceIndices1;
    private final int[] faceIndices2;
    private final int[] faceIndices3;

    private final int vertexCount;
    private final int faceCount;

    /**
     * Validates that the model data is complete and usable.
     *
     * @return true if the model has valid vertex, face, and color data
     */
    public boolean isValid() {
        return vertexCount > 0 && faceCount > 0
                && verticesX != null && verticesX.length >= vertexCount
                && verticesY != null && verticesY.length >= vertexCount
                && verticesZ != null && verticesZ.length >= vertexCount
                && vertexColorsR != null && vertexColorsR.length >= vertexCount
                && vertexColorsG != null && vertexColorsG.length >= vertexCount
                && vertexColorsB != null && vertexColorsB.length >= vertexCount
                && faceIndices1 != null && faceIndices1.length >= faceCount
                && faceIndices2 != null && faceIndices2.length >= faceCount
                && faceIndices3 != null && faceIndices3.length >= faceCount;
    }
}
