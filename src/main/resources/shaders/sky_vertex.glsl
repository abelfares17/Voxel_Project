#version 150 core
out vec2 screenPosition;
void main() {
    vec2 p = gl_VertexID == 0 ? vec2(-1, -1) : gl_VertexID == 1 ? vec2(3, -1) : vec2(-1, 3);
    screenPosition = p;
    gl_Position = vec4(p, 1, 1);
}
