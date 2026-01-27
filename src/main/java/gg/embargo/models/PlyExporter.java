package gg.embargo.models;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Utility class to export ModelData to PLY (Polygon File Format) binary format.
 * <p>
 * PLY format specification: binary little-endian with ASCII header.
 * Includes vertex colors (RGB) for proper model rendering.
 */
@Slf4j
public class PlyExporter {

    private static final int MAX_FILE_SIZE = 1024 * 1024; // 1MB limit for player/pet models
    private static final int MAX_SCENE_FILE_SIZE = 5 * 1024 * 1024; // 5MB limit for scene models

    /**
     * Exports ModelData to PLY binary format (little-endian) with vertex colors.
     *
     * @param modelData the model data to export
     * @return byte array containing the PLY file, or null if export fails
     */
    public static byte[] export(ModelData modelData) {
        return export(modelData, MAX_FILE_SIZE);
    }

    /**
     * Exports ModelData to PLY binary format with a custom size limit.
     * Use exportScene() for scene models which have a higher limit.
     *
     * @param modelData the model data to export
     * @return byte array containing the PLY file, or null if export fails
     */
    public static byte[] exportScene(ModelData modelData) {
        return export(modelData, MAX_SCENE_FILE_SIZE);
    }

    private static byte[] export(ModelData modelData, int maxSize) {
        if (modelData == null || !modelData.isValid()) {
            log.warn("Invalid model data provided for PLY export");
            return null;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Write ASCII header
            writeHeader(baos, modelData.getVertexCount(), modelData.getFaceCount());

            // Write binary vertex data (little-endian shorts + RGB bytes)
            writeVertices(baos, modelData);

            // Write binary face data (little-endian ints)
            writeFaces(baos, modelData);

            byte[] result = baos.toByteArray();

            if (result.length > maxSize) {
                log.warn("PLY file exceeds size limit ({} bytes): {} bytes", maxSize, result.length);
                return null;
            }

            return result;

        } catch (IOException e) {
            log.error("Error exporting PLY file", e);
            return null;
        }
    }

    /**
     * Writes the ASCII PLY header with vertex color properties.
     */
    private static void writeHeader(ByteArrayOutputStream baos, int vertexCount, int faceCount)
            throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("ply\n");
        header.append("format binary_little_endian 1.0\n");
        header.append("element vertex ").append(vertexCount).append("\n");
        header.append("property int16 x\n");
        header.append("property int16 y\n");
        header.append("property int16 z\n");
        header.append("property uint8 red\n");
        header.append("property uint8 green\n");
        header.append("property uint8 blue\n");
        header.append("element face ").append(faceCount).append("\n");
        header.append("property list uint8 int32 vertex_indices\n");
        header.append("end_header\n");

        baos.write(header.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Writes vertex data as little-endian int16 coordinates + uint8 RGB colors.
     * Format per vertex: x(2) + y(2) + z(2) + r(1) + g(1) + b(1) = 9 bytes
     * Note: Y and Z are swapped when writing (matching RuneProfile's coordinate transform)
     */
    private static void writeVertices(ByteArrayOutputStream baos, ModelData modelData)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(9); // 3 shorts (6 bytes) + 3 bytes RGB
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < modelData.getVertexCount(); i++) {
            buffer.clear();
            // Coordinates as int16 (short) - Y and Z swapped for coordinate system conversion
            buffer.putShort(modelData.getVerticesX()[i]);
            buffer.putShort(modelData.getVerticesZ()[i]);  // Write Z as Y
            buffer.putShort(modelData.getVerticesY()[i]);  // Write Y as Z
            // Colors as uint8
            buffer.put(modelData.getVertexColorsR()[i]);
            buffer.put(modelData.getVertexColorsG()[i]);
            buffer.put(modelData.getVertexColorsB()[i]);
            baos.write(buffer.array());
        }
    }

    /**
     * Writes face data as triangles (1 byte count + 3 little-endian ints).
     */
    private static void writeFaces(ByteArrayOutputStream baos, ModelData modelData)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(13); // 1 byte count + 3 ints * 4 bytes
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < modelData.getFaceCount(); i++) {
            buffer.clear();
            buffer.put((byte) 3); // Triangle: 3 vertices per face
            buffer.putInt(modelData.getFaceIndices1()[i]);
            buffer.putInt(modelData.getFaceIndices2()[i]);
            buffer.putInt(modelData.getFaceIndices3()[i]);
            baos.write(buffer.array());
        }
    }
}
