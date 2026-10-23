#!/usr/bin/env python3
"""生成 MDict 读取测试用的小词典（app/src/test/resources/mdict/）。

依赖：pip install mdict-utils python-lzo（python-lzo 需要系统装 liblzo2-dev）
用法：python3 tools/make_mdict_fixtures.py app/src/test/resources/mdict
"""
import os
import sys

import lzo  # python-lzo
import mdict_utils.base.writemdict as writemdict
from mdict_utils.base.writemdict import MDictWriter

# mdict-utils 自带的 lzo 模块只能解压；写 LZO 压缩块要换成 python-lzo。
writemdict.lzo = lzo
writemdict.HAVE_LZO = True


def entries():
    d = {
        "银行": '<div class="def"><b>银行</b> yínháng：经营存款、贷款等业务的金融机构。<img src="bank.png"></div>',
        "Hello": "<p>英文问候语</p>",
        "行": "<p>háng / xíng：多音字。</p>",
        "银號": "@@@LINK=银行",
    }
    # 再加几千条，让索引和正文分成多个块。
    for i in range(3000):
        d[f"词条{i:04d}"] = f"<p>第 {i} 条的释义。</p>"
    return d


def write(path, **kwargs):
    with open(path, "wb") as f:
        MDictWriter(entries(), title="测试词典", description="灵阅 MDict 测试", block_size=1024, **kwargs).write(f)


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    write(f"{out}/v2_zlib_utf8.mdx", version="2.0", compression_type=2, encoding="utf8")
    write(f"{out}/v2_zlib_encrypted_index.mdx", version="2.0", compression_type=2, encoding="utf8", encrypt_index=True)
    # LZO 见于 1.2 版的老词典；2.0 版的索引信息块总是 zlib，这里不造不存在的组合。
    write(f"{out}/v1_lzo_utf8.mdx", version="1.2", compression_type=1, encoding="utf8")
    write(f"{out}/v1_plain_utf16.mdx", version="1.2", compression_type=0, encoding="utf16")
    write(f"{out}/v2_zlib_gbk.mdx", version="2.0", compression_type=2, encoding="gbk")
    png = bytes.fromhex("89504e470d0a1a0a0000000d4948445200000001000000010806000000"
                        "1f15c4890000000d49444154789c6364f8cf00000301010018dd8db0000000"
                        "0049454e44ae426082")
    with open(f"{out}/v2_zlib_utf8.mdd", "wb") as f:
        MDictWriter({"\\bank.png": png, "\\style.css": b".def{color:#a23b2a}"}, title="", description="",
                    block_size=1024, is_mdd=True).write(f)
