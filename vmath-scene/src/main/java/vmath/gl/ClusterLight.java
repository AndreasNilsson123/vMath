package vmath.gl;

import vmath.annotations.Experimental;
import vmath.annotations.GpuStruct;
import vmath.core.Vec4f;

/**
 * A light as a clustered-lighting shader reads it: three {@code vec4}s, 48 bytes, the same under
 * std430 and std140 (every member is a vec4).
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ClusterLight light = new ClusterLight(new Vec4f(1f, 2f, 3f, 10f), new Vec4f(0f, -1f, 0f, 0.8f), new Vec4f(1f, 0.9f, 0.7f, 5f));
 * Vec4f positionRange = light.positionRange();                          // x, y, z and the range in w
 * }</pre>
 *
 * @param positionRange   {@code xyz}: position (view space for the assignment pass, world space if the shader works there), {@code w}: the range the light reaches
 *
 * @param directionCosOuter {@code xyz}: unit direction a spot light shines along, {@code w}: cosine
 *     of its outer half angle; {@code w <= -1} marks a point light
 * @param colorIntensity {@code rgb}: linear colour, {@code w}: intensity
 */
@Experimental("the light record may gain members")
@GpuStruct(layout = GpuStruct.Layout.STD430)
public record ClusterLight(Vec4f positionRange, Vec4f directionCosOuter, Vec4f colorIntensity) {

    /**
     * The {@code w} of {@link #directionCosOuter()} that marks a point light.
     */
    public static final float POINT = -1f;
}
