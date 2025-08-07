#version 150
in vec2 vUV;
out vec4 fragColor;
uniform sampler2D uFontTex;
uniform vec3 uColor;
void main() {
    float alpha = texture(uFontTex, vUV).r;
    fragColor = vec4(uColor, alpha);
    // Optional: if(alpha < 0.01) discard;
}