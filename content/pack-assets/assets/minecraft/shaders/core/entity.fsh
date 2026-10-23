#version 330
#extension GL_ARB_separate_shader_objects : require

#ifdef GLINT
#include <minecraft:globals.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
#else
layout(location = 2) in vec4 vertexColor;
#endif

#ifndef EMISSIVE
layout(location = 4) in vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) in vec4 overlayColor;
#endif

layout(location = 6) in vec2 texCoord0;
#ifdef GLINT
layout(location = 7) in vec2 texCoordGlint;
#endif

layout(location = 8) flat in int rigPart;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Astera skin rig (ADR-0250): a rig display is drawn with the vanilla player-head cube, whose UVs lie in the
// head box layout of the 64x64 skin (base layer at u 0..32, hat layer at u 32..64, v 0..16). Each fragment
// finds the face of that 8x8x8 box it lies in and the position inside the face, then samples the same face of
// the bone's own box (texOffs and size below) instead. Returns (-1, -1) for fragments outside the layout.
vec2 rigRemap(vec2 uv, int part) {
    int bone = part & 7;
    bool slim = (part & 8) != 0;
    vec2 px = uv * 64.0;
    bool outer = px.x >= 32.0;
    float lu = px.x - (outer ? 32.0 : 0.0);
    float v = px.y;
    if (lu < 0.0 || lu >= 32.0 || v < 0.0 || v >= 16.0) {
        return vec2(-1.0);
    }

    // Source face: 0 top, 1 bottom, 2 right, 3 front, 4 left, 5 back, plus the 0..1 position inside it.
    int face;
    vec2 f;
    if (v < 8.0) {
        if (lu >= 8.0 && lu < 16.0) {
            face = 0;
            f = vec2(lu - 8.0, v) / 8.0;
        } else if (lu >= 16.0 && lu < 24.0) {
            face = 1;
            f = vec2(lu - 16.0, v) / 8.0;
        } else {
            return vec2(-1.0);
        }
    } else {
        int column = int(floor(lu / 8.0));
        face = 2 + column;
        f = vec2(lu - float(column) * 8.0, v - 8.0) / 8.0;
    }

    // Target box: texOffs (skin pixels) and size (width, height, depth).
    vec2 offs;
    vec3 size;
    if (bone == 0) {
        offs = outer ? vec2(32.0, 0.0) : vec2(0.0, 0.0);
        size = vec3(8.0, 8.0, 8.0);
    } else if (bone == 1) {
        offs = outer ? vec2(16.0, 32.0) : vec2(16.0, 16.0);
        size = vec3(8.0, 12.0, 4.0);
    } else if (bone == 2) {
        offs = outer ? vec2(40.0, 32.0) : vec2(40.0, 16.0);
        size = vec3(slim ? 3.0 : 4.0, 12.0, 4.0);
    } else if (bone == 3) {
        offs = outer ? vec2(48.0, 48.0) : vec2(32.0, 48.0);
        size = vec3(slim ? 3.0 : 4.0, 12.0, 4.0);
    } else if (bone == 4) {
        offs = outer ? vec2(0.0, 32.0) : vec2(0.0, 16.0);
        size = vec3(4.0, 12.0, 4.0);
    } else {
        offs = outer ? vec2(0.0, 48.0) : vec2(16.0, 48.0);
        size = vec3(4.0, 12.0, 4.0);
    }
    float w = size.x;
    float h = size.y;
    float d = size.z;

    vec2 origin;
    vec2 extent;
    if (face == 0) {
        origin = vec2(d, 0.0);
        extent = vec2(w, d);
    } else if (face == 1) {
        origin = vec2(d + w, 0.0);
        extent = vec2(w, d);
    } else if (face == 2) {
        origin = vec2(0.0, d);
        extent = vec2(d, h);
    } else if (face == 3) {
        origin = vec2(d, d);
        extent = vec2(w, h);
    } else if (face == 4) {
        origin = vec2(d + w, d);
        extent = vec2(d, h);
    } else {
        origin = vec2(2.0 * d + w, d);
        extent = vec2(w, h);
    }
    return (offs + origin + clamp(f, 0.001, 0.999) * extent) / 64.0;
}

vec4 calculateFinalColor(vec4 color) {
    #ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    #endif

    #ifndef EMISSIVE
    color *= lightMapColor;
    #endif

    #ifdef GLINT
    vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);
    // Matches BlendFuntion.GLINT
    color.rgb += glintColor.rgb * glintColor.rgb;
    #endif

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}

void main() {
    vec2 skinUV = texCoord0;
    if (rigPart >= 0) {
        skinUV = rigRemap(texCoord0, rigPart);
        if (skinUV.x < 0.0) {
            discard;
        }
    }
    vec4 color = texture(Sampler0, skinUV);

    #ifdef OIT_ADDITIVE
    color.a = min(0.99, color.a);
    #endif

    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef PER_FACE_LIGHTING
    vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
    #else
    vec4 faceVertexColor = vertexColor;
    #endif

    #ifdef DISSOLVE
    if (faceVertexColor.a < texture(DissolveMaskSampler, texCoord0).a) {
        discard;
    }
    // The dissolve effect entirely replaces translucency
    faceVertexColor.a = 1.0;
    #endif

    color *= faceVertexColor * ColorModulator;

    #ifdef GLINT
    color.a = max(color.a, GlintAlpha);
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
