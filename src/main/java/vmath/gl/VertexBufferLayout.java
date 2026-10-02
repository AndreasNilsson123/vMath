package vmath.gl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import vmath.annotations.Experimental;

/**
 * The layout of one interleaved vertex buffer: named attributes with their shader locations, formats and byte offsets, and the stride. It turns into what each API
 * wants: {@link #glFormats} (the arguments of {@code glVertexAttribFormat} / {@code glVertexAttribIFormat} for one binding), {@link #vkAttributes} and
 * {@link #vkBinding} (the Vulkan vertex input structs' values) and {@link #glslInputs} (the {@code layout(location = n) in ...} declarations), so the three cannot
 * disagree about an offset.
 *
 * <pre>{@code
 * VertexBufferLayout layout = VertexBufferLayout.builder()
 *         .attribute("position", 0, VertexFormat.FLOAT32X3)
 *         .attribute("normal", 1, VertexFormat.SNORM16X2)
 *         .attribute("uv", 2, VertexFormat.FLOAT16X2)
 *         .build();   // offsets 0, 12, 16; stride 20
 * }</pre>
 *
 * <p>Attributes are placed one after another, each aligned to its component size; the stride is rounded up to a multiple of 4. Use {@code attributeAt} to put an
 * attribute at a given offset (to match a buffer that was written by other code).
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads freely. The arrays it hands out are its own storage: do
 * not modify them.
 */
@Experimental("the description may gain binding and divisor details")
public final class VertexBufferLayout {

    /** One attribute: its shader input name, location, format and byte offset in the vertex. */
    public record Attribute(String name, int location, VertexFormat format, int offset) {
    }

    /** The arguments of {@code glVertexAttribFormat} (or {@code glVertexAttribIFormat} when {@code integer}) for one attribute; the offset is {@code relativeOffset}. */
    public record GlFormat(int location, int size, int type, boolean normalized, boolean integer, int relativeOffset) {
    }

    /** The values of one {@code VkVertexInputAttributeDescription}. */
    public record VkAttribute(int location, int binding, int format, int offset) {
    }

    /** The values of one {@code VkVertexInputBindingDescription}; {@code perInstance} selects {@code VK_VERTEX_INPUT_RATE_INSTANCE}. */
    public record VkBinding(int binding, int stride, boolean perInstance) {
    }

    private final List<Attribute> attributes;
    private final int stride;
    private final boolean perInstance;

    private VertexBufferLayout(List<Attribute> attributes, int stride, boolean perInstance) {
        this.attributes = List.copyOf(attributes);
        this.stride = stride;
        this.perInstance = perInstance;
    }

    /** A builder for a layout; attributes are laid out in the order they are added. */
    public static Builder builder() {
        return new Builder();
    }

    /** The attributes in memory order. */
    public List<Attribute> attributes() {
        return attributes;
    }

    /** Bytes from one vertex to the next. */
    public int stride() {
        return stride;
    }

    /** {@code true} if the buffer advances per instance (divisor 1 in OpenGL, instance input rate in Vulkan) rather than per vertex. */
    public boolean perInstance() {
        return perInstance;
    }

    /** The attribute with this name. */
    public Attribute attribute(String name) {
        for (Attribute a : attributes) {
            if (a.name().equals(name)) {
                return a;
            }
        }
        throw new IllegalArgumentException("no attribute '" + name + "'");
    }

    /** Arguments for the OpenGL 4.3 {@code glVertexAttribFormat} family, in attribute order. */
    public List<GlFormat> glFormats() {
        List<GlFormat> out = new ArrayList<>();
        for (Attribute a : attributes) {
            VertexFormat f = a.format();
            out.add(new GlFormat(a.location(), f.components(), f.glType(), f.normalized(), f.integer(), a.offset()));
        }
        return out;
    }

    /** The Vulkan attribute descriptions for a buffer bound at {@code binding}. */
    public List<VkAttribute> vkAttributes(int binding) {
        List<VkAttribute> out = new ArrayList<>();
        for (Attribute a : attributes) {
            out.add(new VkAttribute(a.location(), binding, a.format().vkFormat(), a.offset()));
        }
        return out;
    }

    /** The Vulkan binding description for a buffer bound at {@code binding}. */
    public VkBinding vkBinding(int binding) {
        return new VkBinding(binding, stride, perInstance);
    }

    /** The vertex shader input declarations, one line per attribute: {@code layout(location = 0) in vec3 position;}. */
    public String glslInputs() {
        StringBuilder sb = new StringBuilder();
        for (Attribute a : attributes) {
            sb.append("layout(location = ").append(a.location()).append(") in ").append(a.format().glsl()).append(' ').append(a.name()).append(";\n");
        }
        return sb.toString();
    }

    /** Fluent builder for {@link VertexBufferLayout}. */
    public static final class Builder {
        private final List<Attribute> list = new ArrayList<>();
        private final Set<String> names = new HashSet<>();
        private final Set<Integer> locations = new HashSet<>();
        private int cursor;
        private int minStride;
        private boolean perInstance;

        private Builder() {
        }

        /** Adds an attribute after the previous one, aligned to its component size. */
        public Builder attribute(String name, int location, VertexFormat format) {
            int a = format.alignment();
            return attributeAt(name, location, format, (cursor + a - 1) / a * a);
        }

        /** Adds an attribute at an explicit byte offset (a multiple of the component size). Later {@code attribute} calls continue after it. */
        public Builder attributeAt(String name, int location, VertexFormat format, int offset) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("an attribute needs a name");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate attribute name '" + name + "'");
            }
            if (location < 0 || !locations.add(location)) {
                throw new IllegalArgumentException("invalid or duplicate location " + location);
            }
            if (offset < 0 || offset % format.alignment() != 0) {
                throw new IllegalArgumentException("offset " + offset + " of '" + name + "' is not a multiple of " + format.alignment());
            }
            list.add(new Attribute(name, location, format, offset));
            cursor = Math.max(cursor, offset + format.bytes());
            minStride = cursor;
            return this;
        }

        /** Marks the buffer as advancing per instance rather than per vertex. */
        public Builder perInstance() {
            perInstance = true;
            return this;
        }

        /** Builds the layout; {@link IllegalStateException} when no attribute was added. */
        public VertexBufferLayout build() {
            if (list.isEmpty()) {
                throw new IllegalStateException("a vertex buffer layout needs at least one attribute");
            }
            return new VertexBufferLayout(list, (minStride + 3) & ~3, perInstance);
        }

        /** Builds with an explicit stride, for buffers whose vertices are padded beyond the attributes. */
        public VertexBufferLayout build(int stride) {
            if (list.isEmpty()) {
                throw new IllegalStateException("a vertex buffer layout needs at least one attribute");
            }
            if (stride < minStride) {
                throw new IllegalArgumentException("the stride " + stride + " is smaller than the attributes need: " + minStride);
            }
            return new VertexBufferLayout(list, stride, perInstance);
        }
    }
}
