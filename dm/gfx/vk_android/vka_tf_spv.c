// transform feedback for the Vulkan replay, the part with no Vulkan in it (vka_tf.h): the capture layout a vertex shader's control section declares, and the
// rewrite that makes a library module capture it. The library's modules carry no capture (entry flag DMSH_VK_F_TF only says the shader has one), so the replay adds it
// when a capturing draw first needs the module: no library has to be built again for it.
// The approach (VK_EXT_transform_feedback with XfbBuffer / XfbStride / Offset on the captured outputs) is the one nx2android's Vulkan backend uses, whose translator
// writes those decorations when it builds its library; here they are added to the finished module instead
#include "vka_tf.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int vka_tf_layout_read(const uint8_t *c, size_t n, vka_tf_layout *out) {
    memset(out, 0, sizeof *out);
    if (!c || n < 0x6ec) return 0;
    uint32_t magic, ver; memcpy(&magic, c, 4); memcpy(&ver, c + 4, 4);
    if (magic == 0x98761234u && ver == 0xc9) return 0;   // an NVN2 section has no such fields
    memcpy(out->stride, c + 0x6dc, 8);
    if (!(out->stride[0] | out->stride[1] | out->stride[2] | out->stride[3])) return 0;
    for (int b = 0; b < 4; b++) {
        if (!out->stride[b]) continue;
        unsigned cnt = c[0x6e8 + b], sum = 0;
        if (cnt < 1 || cnt > VKA_TF_OUTS) return -1;
        for (unsigned i = 0; i < cnt; i++) {
            unsigned loc = c[0xdc + 0x80 * b + i], sz = c[0x4dc + 0x80 * b + i];
            if (loc < 0x20 || loc >= 0x40 || sz < 1 || sz > 4) return -1;   // generic outputs only, one to four components
            out->loc[b][i] = (uint8_t)(loc - 0x20); out->comp[b][i] = (uint8_t)sz; sum += sz;
        }
        if (out->stride[b] != 4 * sum) return -1;
        out->n[b] = (uint8_t)cnt;
    }
    return 1;
}

enum { Op_ConstantNull = 46, Op_EntryPoint = 15, Op_Capability = 17, Op_TypeFloat = 22, Op_TypeVector = 23, Op_TypePointer = 32, Op_Function = 54, Op_FunctionEnd = 56, Op_Variable = 59,
       Op_Load = 61, Op_Store = 62, Op_Decorate = 71, Op_MemberDecorate = 72, Op_VectorShuffle = 79, Op_CompositeExtract = 81, Op_Return = 253,
       Dec_Location = 30, Dec_Offset = 35, Dec_XfbBuffer = 36, Dec_XfbStride = 37, SC_Output = 3 };
#define FAIL(...) do { snprintf(err, errn, __VA_ARGS__); free(loc); free(o); return NULL; } while (0)
#define INS(op, n) (((uint32_t)(n) << 16) | (uint32_t)(op))
uint32_t *vka_spv_xfb(const uint32_t *w, size_t nw, const vka_tf_layout *L, uint32_t max_loc, size_t *out_nw, char *err, size_t errn) {
    uint8_t *loc = NULL; uint32_t *o = NULL;   // loc[id]: 1 + the Location an id is decorated with; bit 7: a pointer to an Output float vec4
    if (errn) err[0] = 0;
    if (nw < 5 || w[0] != 0x07230203) FAIL("not a SPIR-V module");
    uint32_t bound = w[3], main_id = 0, tfloat = 0, tvec[5] = {0}, src[32] = {0}, used = 0, nret = 0, nout = 0;
    size_t ep = 0, types = 0, funcs = 0;
    if (bound < 1 || bound > (1u << 22)) FAIL("id bound %u", bound);
    if (!(loc = calloc(bound, 1))) FAIL("no memory");
    if (max_loc > 32) max_loc = 32;
    for (size_t i = 5, in_main = 0; i < nw;) {
        uint32_t op = w[i] & 0xffff, n = w[i] >> 16;
        if (!n || i + n > nw) FAIL("a cut instruction at word %zu", i);
        if (!types && !funcs && op >= 19 && op <= 39) types = i;   // the first type: annotations end here
        switch (op) {
        case Op_Capability: if (n >= 2 && w[i + 1] == 53) FAIL("the module already has the TransformFeedback capability"); break;
        case Op_EntryPoint: if (n >= 4 && w[i + 1] == 0 && !ep) { ep = i; main_id = w[i + 2]; } break;   // execution model Vertex
        case Op_Decorate: if (n >= 4 && w[i + 2] == Dec_Location && w[i + 1] < bound && w[i + 3] < 32) loc[w[i + 1]] = (uint8_t)(w[i + 3] + 1); break;
        case Op_MemberDecorate: if (n >= 5 && w[i + 3] == Dec_Location) FAIL("an interface block with member locations"); break;
        case Op_TypeFloat: if (n >= 3 && w[i + 2] == 32) tfloat = w[i + 1]; break;
        case Op_TypeVector: if (n >= 4 && tfloat && w[i + 2] == tfloat && w[i + 3] >= 2 && w[i + 3] <= 4) tvec[w[i + 3]] = w[i + 1]; break;
        case Op_TypePointer: if (n >= 4 && w[i + 2] == SC_Output && tvec[4] && w[i + 3] == tvec[4] && w[i + 1] < bound) loc[w[i + 1]] |= 0x80; break;
        case Op_Variable:
            if (n >= 4 && !funcs && w[i + 3] == SC_Output && w[i + 2] < bound && w[i + 1] < bound && (loc[w[i + 2]] & 0x7f)) {
                unsigned l = (loc[w[i + 2]] & 0x7fu) - 1; used |= 1u << l;
                if (loc[w[i + 1]] & 0x80) src[l] = w[i + 2];
            }
            break;
        case Op_Function: if (!funcs) funcs = i; in_main = n >= 5 && w[i + 2] == main_id; break;
        case Op_FunctionEnd: in_main = 0; break;
        case Op_Return: if (in_main) nret++; break;
        default: break;
        }
        i += n;
    }
    if (!ep || !main_id) FAIL("no vertex entry point");
    if (!types || !funcs || types > funcs || ep > types || !nret) FAIL("a module layout this does not know");
    for (int b = 0; b < 4; b++) nout += L->stride[b] ? L->n[b] : 0;
    if (!nout || nout > 4 * VKA_TF_OUTS) FAIL("nothing to capture");
    for (int b = 0; b < 4; b++) for (unsigned k = 0; L->stride[b] && k < L->n[b]; k++) {
        unsigned l = L->loc[b][k], c = L->comp[b][k];
        if (l >= 32 || c < 1 || c > 4) FAIL("a captured output of location %u, %u components", l, c);
        if ((used >> l & 1) && !src[l]) FAIL("the output at location %u is not a float vec4", l);
    }
    // Every new id, then the words: the capability, the entry point with the new variables in its interface and the Xfb execution mode after it, the new decorations
    // where the annotations end, the new types and variables before the first function, the stores before each return of the entry point
    struct { uint32_t var, type, null, l, n, src, buf, off; } v[4 * VKA_TF_OUTS];
    uint32_t id = bound, mk_float = 0, mk_vec[5] = {0}, ptr[5] = {0}, nv = 0;
    if (!tfloat) { tfloat = id++; mk_float = 1; }
    for (int b = 0; b < 4; b++) for (unsigned k = 0, off = 0; L->stride[b] && k < L->n[b]; k++) {
        unsigned l = L->loc[b][k], c = L->comp[b][k], free_loc = 0;
        while (free_loc < max_loc && (used >> free_loc & 1)) free_loc++;
        if (free_loc >= max_loc) FAIL("no free output location below %u for the captured outputs", max_loc);
        used |= 1u << free_loc;
        if (c > 1 && !tvec[c]) { tvec[c] = id++; mk_vec[c] = 1; }
        if (src[l] && !tvec[4]) FAIL("no float vec4 type");
        if (!ptr[c]) ptr[c] = id++;
        v[nv] = (typeof(v[0])){.var = id++, .type = c > 1 ? tvec[c] : tfloat, .l = free_loc, .n = c, .src = src[l], .buf = (uint32_t)b, .off = off};
        if (!src[l]) v[nv].null = id++;
        nv++; off += 4 * c;
    }
    size_t cap = nw + 64 + (size_t)nv * (1 + 16 + 12 + (size_t)nret * 16), on = 0;
    if (!(o = malloc(cap * 4))) FAIL("no memory");
    memcpy(o, w, 5 * 4); on = 5;
    o[on++] = INS(Op_Capability, 2); o[on++] = 53;
    memcpy(o + on, w + 5, (ep - 5) * 4); on += ep - 5;
    uint32_t epn = w[ep] >> 16;
    if (epn + nv > 0xffff) FAIL("the entry point's interface is full");
    o[on++] = INS(Op_EntryPoint, epn + nv); memcpy(o + on, w + ep + 1, (epn - 1) * 4); on += epn - 1;
    for (uint32_t i = 0; i < nv; i++) o[on++] = v[i].var;
    o[on++] = INS(16, 3); o[on++] = main_id; o[on++] = 11;   // OpExecutionMode Xfb
    memcpy(o + on, w + ep + epn, (types - ep - epn) * 4); on += types - ep - epn;
    for (uint32_t i = 0; i < nv; i++) {
        const uint32_t d[4][2] = {{Dec_Location, v[i].l}, {Dec_XfbBuffer, v[i].buf}, {Dec_XfbStride, L->stride[v[i].buf]}, {Dec_Offset, v[i].off}};
        for (int k = 0; k < 4; k++) { o[on++] = INS(Op_Decorate, 4); o[on++] = v[i].var; o[on++] = d[k][0]; o[on++] = d[k][1]; }
    }
    memcpy(o + on, w + types, (funcs - types) * 4); on += funcs - types;
    if (mk_float) { o[on++] = INS(Op_TypeFloat, 3); o[on++] = tfloat; o[on++] = 32; }
    for (uint32_t c = 2; c <= 4; c++) if (mk_vec[c]) { o[on++] = INS(Op_TypeVector, 4); o[on++] = tvec[c]; o[on++] = tfloat; o[on++] = c; }
    for (uint32_t c = 1; c <= 4; c++) if (ptr[c]) { o[on++] = INS(Op_TypePointer, 4); o[on++] = ptr[c]; o[on++] = SC_Output; o[on++] = c > 1 ? tvec[c] : tfloat; }
    for (uint32_t i = 0; i < nv; i++) {
        o[on++] = INS(Op_Variable, 4); o[on++] = ptr[v[i].n]; o[on++] = v[i].var; o[on++] = SC_Output;
        if (v[i].null) { o[on++] = INS(Op_ConstantNull, 3); o[on++] = v[i].type; o[on++] = v[i].null; }
    }
    for (size_t i = funcs, in_main = 0; i < nw;) {
        uint32_t op = w[i] & 0xffff, n = w[i] >> 16;
        if (op == Op_Function) in_main = n >= 5 && w[i + 2] == main_id;
        else if (op == Op_FunctionEnd) in_main = 0;
        else if (op == Op_Return && in_main) for (uint32_t k = 0; k < nv; k++) {
            uint32_t val = v[k].null;
            if (v[k].src) {
                uint32_t t = id++; o[on++] = INS(Op_Load, 4); o[on++] = tvec[4]; o[on++] = t; o[on++] = v[k].src; val = t;
                if (v[k].n == 1) { val = id++; o[on++] = INS(Op_CompositeExtract, 5); o[on++] = tfloat; o[on++] = val; o[on++] = t; o[on++] = 0; }
                else if (v[k].n < 4) { val = id++; o[on++] = INS(Op_VectorShuffle, 5 + v[k].n); o[on++] = v[k].type; o[on++] = val; o[on++] = t; o[on++] = t;
                                       for (uint32_t c = 0; c < v[k].n; c++) o[on++] = c; }
            }
            o[on++] = INS(Op_Store, 3); o[on++] = v[k].var; o[on++] = val;
        }
        memcpy(o + on, w + i, n * 4); on += n; i += n;
    }
    if (on > cap) FAIL("internal: %zu words in room for %zu", on, cap);   // (the room is counted above: never)
    o[3] = id; *out_nw = on;
    free(loc);
    return o;
}
