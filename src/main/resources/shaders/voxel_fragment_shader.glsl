#version 150

in vec3 vNormal;
in vec2 vTexCoord;
in vec3 vWorldPos;
in vec4 vLightSpacePos;
in float vLight;
in float vAO;

uniform sampler2D textureSampler;
uniform sampler2DShadow shadowMap;

uniform vec3 sunDir;
uniform vec3 sunColor;
uniform float ambient;

uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;

uniform vec3 cameraPos;

out vec4 fragColor;

float calculateShadow(vec4 lightSpacePos) {
    vec3 projCoords = lightSpacePos.xyz / lightSpacePos.w;
    projCoords = projCoords * 0.5 + 0.5;

    // Hors du frustum lumière
    if (projCoords.z > 1.0)
        return 1.0;

    float shadow = 0.0;
    float bias = 0.005;
    int samples = 1;
    const float texelSize = 1.0 / 2048.0;  // taille shadow map en pixels

    for (int x = -samples; x <= samples; ++x) {
        for (int y = -samples; y <= samples; ++y) {
            vec2 offset = vec2(x, y) * texelSize;
            shadow += texture(shadowMap, vec3(projCoords.xy + offset, projCoords.z - bias));
        }
    }
    shadow /= pow((2 * samples + 1), 2);
    return shadow;
}

void main() {
    vec3 N = normalize(vNormal);
    vec3 L = normalize(sunDir);
    vec3 V = normalize(cameraPos - vWorldPos);

    vec3 albedo = texture(textureSampler, vTexCoord).rgb * 0.8;

    float diff = max(dot(N, L), 0.0);
    float shadow = calculateShadow(vLightSpacePos);

    float aoFactor = mix(0.7, 1.0, vAO);
    float lightFactor = vLight;

    vec3 lighting = (ambient + sunColor * diff * shadow) * lightFactor * aoFactor;
    vec3 color = albedo * lighting;

    float dist = length(cameraPos - vWorldPos);
    float fogFactor = clamp((fogEnd - dist) / (fogEnd - fogStart), 0.0, 1.0);

    fragColor = mix(vec4(fogColor, 1.0), vec4(color, 1.0), fogFactor);
}