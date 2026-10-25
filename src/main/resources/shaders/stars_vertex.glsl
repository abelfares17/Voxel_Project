#version 150 core
in vec3 aPos;
in float aAO; // brightness in the renderer's existing attribute slot 3
uniform mat4 rotationProjection;
out vec3 direction;
out float brightness;
void main() {
    direction = aPos;
    brightness = aAO;
    gl_Position = rotationProjection * vec4(aPos, 0);
    gl_Position.z = gl_Position.w;
    gl_PointSize = 2.5;
}
