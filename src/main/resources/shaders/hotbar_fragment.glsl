#version 150 core

in vec2 vTexCoord;
in vec4 vColor;
flat in int vTexMode;

uniform sampler2D uTexture;

out vec4 fragColor;

void main() {
    if (vTexMode == 2) {
        vec4 tex = texture(uTexture, vTexCoord);
        vec3 displayRgb = pow(max(tex.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        fragColor = vec4(displayRgb * vColor.rgb, tex.a * vColor.a);
    } else if (vTexMode == 1) {
        fragColor = texture(uTexture, vTexCoord) * vColor;
    } else {
        fragColor = vColor;
    }
}
