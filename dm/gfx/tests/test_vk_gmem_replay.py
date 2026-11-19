"""The Vulkan library's replay_raw_loads (vk_android_spv.py): a straight-line vertex shader's raw loads read through kept pool entries, main's body run again on a miss.

The shader is a vertex shader with the translator's raw load helpers (test_vk_gmem_helpers.py), after promote_locals, loop_pool_search and cache_raw_loads as
vk_android_build.py runs them: a run of four words at one base (one cache_raw_loads call), the translator's 128-bit helper at another base (a uvec4 physical
pointer, word 0 returned and words 1-3 stored to a Private array, as the translator emits it; GLSL's buffer_reference needs a block, so the test rewrites its
access to the translator's form), single words at a third base and after the run, and the 128-bit helper again at the run's base. Every value goes to an output.

The interpreter of test_vk_gmem_cache.py runs main before and after over a table of 300 pools, for bases at a pool's start, middle and end, runs that leave
their pool after 1, 2 or 3 words (the rest in no pool, or in the next pool when two pools touch), addresses in no pool (0, the sink, past every pool), a chunk
a pool covers only partly (no hint: the search), and the three bases in one, two or three pools: every output is the same whenever a vertex reads at most
GMEM_REPLAY_SLOTS pools and GMEM_REPLAY_GAPS stretches in no pool (the bound the pass keeps exact). Shaders the pass must leave alone (a branch in main, a
Private variable read before main writes it, a compute shader) come back unchanged."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[0] / 'tools'))
sys.path.insert(0, str(HERE))
import vk_android_spv as spv  # noqa: E402
from test_vk_gmem_helpers import ADDR, FIND_NEW, pool_table  # noqa: E402
from test_vk_gmem_cache import SHADER as COMPUTE_SHADER, run  # noqa: E402

SHADER = '''#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
layout(set = 0, binding = 0, std430) readonly buffer Map { uint w[]; } map;
layout(set = 0, binding = 2, std430) readonly buffer Io { uint w[]; } io;
layout(buffer_reference, std430, buffer_reference_align = 4) buffer Word { uint v; };
layout(buffer_reference, std430, buffer_reference_align = 16) buffer Quad { uvec4 v; };
layout(location = 0) flat out uvec4 o0;
layout(location = 1) flat out uvec4 o1;
layout(location = 2) flat out uvec4 o2;
layout(location = 3) flat out uvec4 o3;
uint g_last = 0u;
uint g_w[3];
@DECL@

uint gload(uint lo, uint hi)
{
@LOAD@
@ADDR@
        return Word(packUint2x32(uvec2(hl, hh))).v;
}

uint gload4(uint lo, uint hi)
{
@LOAD4@
@ADDR@
        uvec4 q = Quad(packUint2x32(uvec2(hl, hh))).v;
        g_w[0] = q.y; g_w[1] = q.z; g_w[2] = q.w;
        return q.x;
}

void main()
{
    uint lo = io.w[0], hi = io.w[1];
    uint lo1 = lo + 4u, hi1 = hi + uint(lo1 < lo);
    uint lo2 = lo + 8u, hi2 = hi + uint(lo2 < lo);
    uint lo3 = lo + 12u, hi3 = hi + uint(lo3 < lo);
    uint a0 = gload(lo, hi), a1 = gload(lo1, hi1), a2 = gload(lo2, hi2), a3 = gload(lo3, hi3);
    uint blo = io.w[2], bhi = io.w[3];
    uint b0 = gload4(blo, bhi);
    uvec3 bw = uvec3(g_w[0], g_w[1], g_w[2]);
    uint clo = io.w[4], chi = io.w[5];
    uint c0 = gload(clo, chi);
    uint lo4 = lo + 16u, hi4 = hi + uint(lo4 < lo);
    uint a4 = gload(lo4, hi4);
    uint d0 = gload4(lo, hi);
    o0 = uvec4(a0, a1, a2, a3);
    o1 = uvec4(b0, bw);
    o2 = uvec4(c0, a4, d0, g_w[0]);
    o3 = uvec4(g_w[1], g_w[2], 0u, 0u);
@MAIN@
}
'''


def source(decl='', main=''):
    return (SHADER.replace('@LOAD4@', FIND_NEW.replace('@RET@', ' (g_w[0] = 0u, g_w[1] = 0u, g_w[2] = 0u, 0u)')).replace('@LOAD@', FIND_NEW.replace('@RET@', ' 0u'))
            .replace('@ADDR@', ADDR).replace('@DECL@', decl).replace('@MAIN@', main))


def translator_wide(m):
    """the 128-bit helper as the translator emits it: OpConvertUToPtr straight to a uvec4 pointer (GLSL goes through the block's member), and the words taken
    from the loaded vector (glslang reads them back from the local q)"""
    cvt = {x[2]: x for x in m.ins if x[0] & 0xffff == spv.OP_CONVERT_U_TO_PTR}
    k, x = next((k, x) for k, x in enumerate(m.ins) if x[0] & 0xffff == spv.OP_ACCESS_CHAIN and x[3] in cvt and m.op(m.pointee(x[1])[1]) == spv.OP_TYPE_VECTOR)
    cvt[x[3]][1] = x[1]
    del m.ins[k]
    for y in m.ins:
        if y[0] & 0xffff == spv.OP_LOAD and y[3] == x[2]: y[3] = x[3]
    q = next(i for i, n in m.names.items() if n == 'q')
    v = next(y[2] for y in m.ins if y[0] & 0xffff == spv.OP_STORE and y[1] == q)
    chains = {y[2]: m.consts[y[4]][1][0] for y in m.ins if y[0] & 0xffff == spv.OP_ACCESS_CHAIN and y[3] == q}
    out = []
    for y in m.ins:
        if y[0] & 0xffff == spv.OP_ACCESS_CHAIN and y[3] == q: continue
        if y[0] & 0xffff == spv.OP_LOAD and y[3] in chains: y = [5 << 16 | spv.OP_COMPOSITE_EXTRACT, y[1], y[2], v, chains[y[3]]]
        out.append(y)
    m.ins = out
    m.index()


def build(src, stage='vert'):
    """(the module as replay_raw_loads gets it, the module after it, its count, spirv-val's result); SPIR-V 1.3, as the translator's modules"""
    with tempfile.TemporaryDirectory() as t:
        d = Path(t)
        (d / f's.{stage}').write_text(src)
        r = subprocess.run(['glslangValidator', '-V', '--target-env', 'vulkan1.1', str(d / f's.{stage}'), '-o', str(d / 's.spv')], capture_output=True, text=True)
        assert r.returncode == 0, r.stdout + r.stderr
        m = spv.Module((d / 's.spv').read_bytes())
        if stage == 'vert': translator_wide(m)
        spv.promote_locals(m)
        spv.loop_pool_search(m)
        table_id = next(v['id'] for v in m.resources() if v['binding'] == 0)
        spv.cache_raw_loads(m, {table_id})
        before = spv.Module(m.data())
        n = spv.replay_raw_loads(m, {table_id})
        (d / 'r.spv').write_bytes(m.data())
        val = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'r.spv')], capture_output=True, text=True)
        return before, spv.Module(m.data()), n, val


def loops(m, f):
    a, e = m.function_range(f)
    return sum(1 for x in m.ins[a:e + 1] if x[0] & 0xffff == spv.OP_LOOP_MERGE)


@unittest.skipUnless(shutil.which('glslangValidator') and shutil.which('spirv-val'), 'needs glslang and SPIRV-Tools')
class GmemReplay(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.before, cls.after, cls.n, cls.val = build(source())

    def test_valid(self):
        self.assertEqual(self.val.returncode, 0, self.val.stdout + self.val.stderr)

    def test_sites(self):
        """the run, the two single words and the two 128-bit calls rewritten; no function but main and its old body is left, and the only searches are main's
        lookup after a run that missed and the body's lookup of its first address (one loop each), none at the call sites"""
        self.assertEqual(self.n, 5)
        fns = [x[2] for x in self.after.ins if x[0] & 0xffff == spv.OP_FUNCTION]
        ent = self.after.entry_function()
        self.assertEqual(len(fns), 2, 'main and its body')
        body = next(f for f in fns if f != ent)
        self.assertEqual(loops(self.after, ent), 2, "main's runs and its lookup")
        self.assertEqual(loops(self.after, body), 1, 'the first address looked up once')
        a, e = self.after.function_range(body)
        self.assertNotIn(spv.OP_FUNCTION_CALL, [x[0] & 0xffff for x in self.after.ins[a:e + 1]])

    def test_same_outputs(self):
        table, pools, sink = pool_table(300)
        hinted = lambda a: table[((a >> 21) & 8191) * 8 + 6] >> 13 == (a >> 21) & 0x7ffff and table[((a >> 21) & 8191) * 8 + 6] & 8191
        s151, e151, d151 = pools[150]; s152 = pools[151][0]   # pool 151 ends where pool 152 starts (a run that leaves one pool goes on in the next)
        table[151 * 8 + 2:151 * 8 + 4] = [s152 & 0xffffffff, s152 >> 32]; pools[150] = (s151, s152, d151)
        starts = sorted(s for s, _, _ in pools)
        bases = [0, sink, 0x0f_ffff_fff0]
        for p in (1, 2, 63, 64, 65, 100, 151, 200, 299, 300):
            s_, e_, _ = pools[p - 1]
            bases += [s_, s_ + 4, (s_ + e_) // 2 & ~3, e_ - 16, e_ - 12, e_ - 8, e_ - 4, e_, s_ - 4]
        part = next(a for a in range(pools[199][0], pools[199][1], 0x10000) if not hinted(a))
        bases.append(part)
        self.assertTrue(any(hinted(a) for a in bases) and not hinted(part), 'some addresses by the hint, one by the search')

        def region(a):   # ('pool', j), ('gap', the last pool start at or below), or None below 64 KiB
            if a < 0x10000: return None
            j = next((j for j, (s_, e_, _) in enumerate(pools) if s_ <= a < e_), None)
            return ('pool', j) if j is not None else ('gap', max((s for s in starts if s <= a), default=-1))
        n = exact = 0
        kinds = set()
        for i, A in enumerate(bases):
            for B, C in ((bases[(i * 7 + 3) % len(bases)], bases[(i * 11 + 5) % len(bases)]), (A, A), (A + 32, bases[(i * 5 + 1) % len(bases)])):
                io = {0: A & 0xffffffff, 1: A >> 32, 2: B & 0xffffffff, 3: B >> 32, 4: C & 0xffffffff, 5: C >> 32}
                want = run(self.before, table, io)
                got = run(self.after, table, io)
                rs = {r for r in map(region, [A, A + 4, A + 8, A + 12, A + 16, B, C]) if r}
                within = sum(r[0] == 'pool' for r in rs) <= spv.GMEM_REPLAY_SLOTS and sum(r[0] == 'gap' for r in rs) <= spv.GMEM_REPLAY_GAPS
                n += 1
                if within:
                    self.assertEqual(got, want, f'bases {A:#x}, {B:#x}, {C:#x}')
                    exact += 1
                where = [region(A + 4 * k) for k in range(4)]
                kinds.add('in' if len(set(where)) == 1 and where[0] and where[0][0] == 'pool' else 'none' if all(not w or w[0] == 'gap' for w in where)
                          else 'two pools' if all(w and w[0] == 'pool' for w in where) else 'leaves')
        self.assertGreater(exact, 250)
        self.assertGreater(exact, n * 9 // 10, 'nearly every vertex within the kept pools and stretches')
        self.assertEqual(kinds, {'in', 'none', 'two pools', 'leaves'}, 'runs inside a pool, in none, across two touching pools, leaving a pool')

    def test_left_alone(self):
        """a branch in main, a Private variable read before main writes it (a rerun would see the last run's value) and a compute shader: no change"""
        for name, src, stage in (('branch', source(main='    if (io.w[6] != 0u) o3.z = 1u;'), 'vert'),
                                 ('private read first', source(decl='uint g_n;', main='    o3.w = g_n; g_n = 1u;'), 'vert'),
                                 ('compute shader', COMPUTE_SHADER, 'comp')):
            with self.subTest(name):
                before, after, n, _ = build(src, stage)
                self.assertEqual(n, 0)
                self.assertEqual(after.data(), before.data())


if __name__ == '__main__':
    unittest.main()
