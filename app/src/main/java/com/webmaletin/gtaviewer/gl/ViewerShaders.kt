package com.webmaletin.gtaviewer.gl

/** Shaders GLSL ES 3.00 del visor. Iluminación de dos caras relativa a la cámara; no dependen del winding. */
object ViewerShaders {
    const val MESH_VERTEX = """#version 300 es
uniform mat4 uMVP;
uniform mat4 uModel;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
out vec3 vN;
out vec2 vUv;
out vec3 vWorld;
void main() {
    vN = mat3(uModel) * aNormal;
    vUv = aUv;
    vWorld = (uModel * vec4(aPos, 1.0)).xyz;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

    const val MESH_FRAGMENT = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec4 uColor;
uniform int uUseTex;
uniform vec3 uCamPos;
uniform float uHighlight;
uniform vec3 uHighlightColor;
in vec3 vN;
in vec2 vUv;
in vec3 vWorld;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vN);
    vec3 v = normalize(uCamPos - vWorld);
    if (dot(n, v) < 0.0) n = -n;
    vec4 base = uColor;
    if (uUseTex == 1) base *= texture(uTex, vUv);
    if (base.a < 0.02) discard;
    vec3 key = normalize(vec3(0.45, 0.85, 0.55));
    vec3 fill = normalize(vec3(-0.6, 0.25, -0.5));
    float hemi = 0.5 + 0.5 * n.y;
    float light = 0.30 + 0.28 * hemi + 0.62 * max(dot(n, key), 0.0) + 0.22 * max(dot(n, fill), 0.0)
                + 0.18 * pow(max(dot(n, v), 0.0), 2.0);
    vec3 c = base.rgb * min(light, 1.25);
    c = mix(c, uHighlightColor, uHighlight);
    fragColor = vec4(c, base.a);
}
"""

    const val LINE_VERTEX = """#version 300 es
uniform mat4 uMVP;
layout(location = 0) in vec3 aPos;
void main() { gl_Position = uMVP * vec4(aPos, 1.0); }
"""

    const val LINE_FRAGMENT = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() { fragColor = uColor; }
"""
}
