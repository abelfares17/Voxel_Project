#version 150

in vec2 vTexCoord;
in vec3 vNormal;

uniform sampler2D textureSampler;
out vec4 fragColor;

void main() {
    // Couleur de base depuis la texture
    vec4 texColor = texture(textureSampler, vTexCoord);
    /*
    fragColor = texColor;
    */
    // Largeur de la grille (en fraction d'une tuile, ex : 0.04 = 4% de la tuile)
    float gridWidth = 0.005;

    // La grille doit apparaître sur chaque face, donc on détecte si UV près du bord
    float borderU = min(fract(vTexCoord.x * 64.0), 1.0 - fract(vTexCoord.x * 64.0));
    float borderV = min(fract(vTexCoord.y * 64.0), 1.0 - fract(vTexCoord.y * 64.0));

    // Attention : 64.0 = nombre de tuiles dans l'atlas
    // Si on veut la grille sur chaque bloc même en mode texture unique, il faut mettre 1.0 à la place

    // Si sur la bordure, couleur noire
    if (borderU < gridWidth || borderV < gridWidth) {
        fragColor = vec4(0.05, 0.05, 0.05, 1.0); // Grille noire fine
    } else {
        fragColor = texColor;
    }

}