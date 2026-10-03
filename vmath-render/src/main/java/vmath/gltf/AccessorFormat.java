package vmath.gltf;

/** The accessor component types and element layouts of glTF 2.0, and the conversion of one stored component to a float. */
final class AccessorFormat {

    private AccessorFormat() {
    }

    static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8 | (d[o + 2] & 0xFF) << 16 | (d[o + 3] & 0xFF) << 24;
    }

    static int componentSize(int componentType) {
        return switch (componentType) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new GltfException("unknown componentType " + componentType);
        };
    }

    static int typeComponents(String type) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4", "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new GltfException("unknown accessor type " + type);
        };
    }

    static int matrixRows(String type) {
        return switch (type) {
            case "MAT2" -> 2;
            case "MAT3" -> 3;
            case "MAT4" -> 4;
            default -> 0;
        };
    }

    /** Bytes of one element; matrix columns are padded to a multiple of 4 bytes. */
    static int elementBytes(String type, int compSize) {
        int rows = matrixRows(type);
        if (rows == 0) {
            return typeComponents(type) * compSize;
        }
        int column = (rows * compSize + 3) & ~3;
        return column * rows;
    }

    /** Byte offset of component {@code c} inside an element. */
    static int componentOffset(String type, int compSize, int c) {
        int rows = matrixRows(type);
        if (rows == 0) {
            return c * compSize;
        }
        int column = (rows * compSize + 3) & ~3;
        return (c / rows) * column + (c % rows) * compSize;
    }

    static float convert(byte[] b, int p, int componentType, boolean normalized) {
        switch (componentType) {
            case 5120: {
                int v = b[p];
                return normalized ? Math.max(v / 127f, -1f) : v;
            }
            case 5121: {
                int v = b[p] & 0xFF;
                return normalized ? v / 255f : v;
            }
            case 5122: {
                int v = (short) ((b[p] & 0xFF) | (b[p + 1] << 8));
                return normalized ? Math.max(v / 32767f, -1f) : v;
            }
            case 5123: {
                int v = (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8;
                return normalized ? v / 65535f : v;
            }
            case 5125:
                return le32(b, p) & 0xFFFFFFFFL;
            default:
                return Float.intBitsToFloat(le32(b, p));
        }
    }
}
