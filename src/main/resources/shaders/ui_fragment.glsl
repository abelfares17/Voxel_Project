#version 150 core

in vec2 vTexCoord; // Texture coordinates from vertex shader

uniform vec4 uColor;        // Uniform color for the primitive
uniform sampler2D uTexture; // Texture sampler
uniform int uUseTexture;    // 0 = color only, 1 = texture * color

out vec4 fragColor; // Final fragment color

void main() {
    if (uUseTexture == 2) {
        // Sample sRGB atlas texture, convert from linearized sRGB back to display sRGB, and apply face shading tint
        vec4 tex = texture(uTexture, vTexCoord);
        vec3 displayRgb = pow(max(tex.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        fragColor = vec4(displayRgb * uColor.rgb, tex.a * uColor.a);
    } else if (uUseTexture == 1) {
        // Sample texture and multiply by color (for tinting)
        fragColor = texture(uTexture, vTexCoord) * uColor;
    } else {
        // Use solid color only
        fragColor = uColor;
    }
}
