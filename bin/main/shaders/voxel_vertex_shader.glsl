#version 150

in vec3 aPos;
in vec2 aTex;
in vec3 aNormal;
in float aLight;
in float aAO;

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;
uniform mat4 lightSpaceMatrix;

out vec2 vTexCoord;
out vec3 vNormal;
out vec3 vWorldPos;
out vec4 vLightSpacePos;
out float vLight;
out float vAO;

void main() {
    vec4 world = modelMatrix * vec4(aPos, 1.0);
    vWorldPos = world.xyz;
    vLightSpacePos = lightSpaceMatrix * world;

    gl_Position = projectionMatrix * viewMatrix * world;
    vTexCoord = aTex;
    vNormal = normalize(mat3(modelMatrix) * aNormal);
    vLight = aLight;
    vAO = aAO;
}