"""Two rewrites of vk_android_spv.py for modules spirv-val refused (Final Fantasy Resonance's compute shaders): extended_format_caps declares
StorageImageExtendedFormats for an image type of a format outside the base set, and workgroup_plain_arrays gives a Workgroup variable whose array type carries
ArrayStride an undecorated array type of its own. Synthetic modules, no game files; the spirv-val check is skipped without SPIRV-Tools."""
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
import vk_android_spv as spv   # noqa: E402

FMT_RG32UI, FMT_R32UI = 35, 33


def module(image_format=None, workgroup_stride=False):
    """A compute module: optionally a storage image type of image_format, optionally shared memory of 8 words whose array type a uniform block's member shares"""
    ins, nid = [], [1]

    def new():
        nid[0] += 1; return nid[0] - 1

    def op(o, *w): ins.append([(len(w) + 1) << 16 | o, *w])
    op(spv.OP_CAPABILITY, 1)   # Shader
    main, void, fn_t, u32 = new(), new(), new(), new()
    op(14, 0, 1)   # OpMemoryModel Logical GLSL450
    op(spv.OP_ENTRY_POINT, 5, main, *spv.lit_string('main'))
    op(spv.OP_EXECUTION_MODE, main, 17, 1, 1, 1)   # LocalSize
    if workgroup_stride:
        c8, arr, blk, pblk, ubo, parr, shared = new(), new(), new(), new(), new(), new(), new()
        op(spv.OP_DECORATE, arr, 6, 4)   # ArrayStride 4
        op(spv.OP_DECORATE, blk, spv.DEC_BLOCK)
        op(spv.OP_MEMBER_DECORATE, blk, 0, 35, 0)   # Offset 0
        op(spv.OP_DECORATE, ubo, spv.DEC_SET, 0)
        op(spv.OP_DECORATE, ubo, spv.DEC_BINDING, 0)
    op(spv.OP_TYPE_VOID, void)
    op(33, fn_t, void)   # OpTypeFunction
    op(spv.OP_TYPE_INT, u32, 32, 0)
    if image_format is not None:
        img, pimg, var = new(), new(), new()
        op(spv.OP_TYPE_IMAGE, img, u32, 1, 0, 0, 0, 2, image_format)
        op(spv.OP_TYPE_POINTER, pimg, spv.SC_UNIFORM_CONSTANT, img)
        op(spv.OP_VARIABLE, pimg, var, spv.SC_UNIFORM_CONSTANT)
    if workgroup_stride:
        op(spv.OP_CONSTANT, u32, c8, 8)
        op(spv.OP_TYPE_ARRAY, arr, u32, c8)
        op(spv.OP_TYPE_STRUCT, blk, arr)
        op(spv.OP_TYPE_POINTER, pblk, spv.SC_UNIFORM, blk)
        op(spv.OP_VARIABLE, pblk, ubo, spv.SC_UNIFORM)
        op(spv.OP_TYPE_POINTER, parr, 4, arr)
        op(spv.OP_VARIABLE, parr, shared, 4)
    lbl = new()
    op(spv.OP_FUNCTION, void, main, 0, fn_t)
    op(spv.OP_LABEL, lbl)
    op(spv.OP_RETURN)
    op(spv.OP_FUNCTION_END)
    words = [spv.MAGIC, 0x10300, 0, nid[0], 0] + [v for x in ins for v in x]
    return spv.Module(struct.pack('<%dI' % len(words), *words))


def spirv_val(m):
    exe = shutil.which('spirv-val')
    if not exe: return None
    with tempfile.NamedTemporaryFile(suffix='.spv', delete=False) as f: f.write(m.data())
    try: return subprocess.run([exe, '--target-env', 'vulkan1.1', f.name], capture_output=True, text=True)
    finally: Path(f.name).unlink()


class ExtendedFormats(unittest.TestCase):
    def test_rg32ui_adds_the_capability_once(self):
        m = module(FMT_RG32UI)
        self.assertEqual(spv.extended_format_caps(m), ['StorageImageExtendedFormats'])
        self.assertIn(spv.CAP_STORAGE_EXTENDED, m.caps)
        self.assertEqual(spv.extended_format_caps(m), [])
        self.assertEqual(m.caps.count(spv.CAP_STORAGE_EXTENDED), 1)

    def test_base_format_and_no_image_unchanged(self):
        for m in (module(FMT_R32UI), module()):
            before = m.data()
            self.assertEqual(spv.extended_format_caps(m), [])
            self.assertEqual(m.data(), before)


class WorkgroupArrays(unittest.TestCase):
    def test_strided_workgroup_array_gets_its_own_type(self):
        m = module(workgroup_stride=True)
        shared = next(v for v, (_, sc) in m.vars.items() if sc == 4)
        old = m.pointee(m.vars[shared][0])[1]
        self.assertEqual(spv.workgroup_plain_arrays(m), 1)
        new = m.pointee(m.vars[shared][0])[1]
        self.assertNotEqual(new, old)
        self.assertEqual(m.op(new), spv.OP_TYPE_ARRAY)
        self.assertNotIn(6, m.decos.get(new, {}))
        self.assertIn(6, m.decos.get(old, {}))   # the uniform block keeps its decorated member type
        self.assertEqual(spv.workgroup_plain_arrays(m), 0)
        r = spirv_val(m)
        if r is not None: self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_module_without_it_unchanged(self):
        m = module(FMT_RG32UI)
        before = m.data()
        self.assertEqual(spv.workgroup_plain_arrays(m), 0)
        self.assertEqual(m.data(), before)


if __name__ == '__main__':
    unittest.main()
