# -*- coding: utf-8 -*-
"""
开发期脚本：irdb 中国常见品牌 CSV 精选 → assets/irdb/（含 manifest.json）

数据来源（宽松许可，probonopd/irdb；需在「关于」页声明 + 开发期在 irdb repo 开 issue，见计划 D4）：
  https://github.com/probonopd/irdb  →  codes/<品牌>/<设备类型>/<型号>.csv
  建议获取方式（Git 稀疏克隆，避开 GitHub API 限流）：
    git clone --depth 1 --filter=blob:none --sparse https://github.com/probonopd/irdb.git
    cd irdb && git sparse-checkout set codes/TCL codes/Konka ...   # 只拉需要的品牌

用法（仅开发用，不打包进 APK）：
  python tools/fetch_irdb_assets.py --src <irdb/codes 目录> \
      [--out app/src/main/assets/irdb] [--max-mb 5.0]

组织为 assets/irdb/<品牌>/<设备类型>/<型号>.csv，保持 irdb 原始 CSV 格式：
  functionname,protocol,device,subdevice,function
设备类型目录名标准化为 App DeviceType 简写（tv/ac/stb/fan/projector/audio/purifier/other），
便于向导第二步按类型过滤（计划 §4.2）。
"""
import argparse
import json
import os
import re
import shutil
import sys

# irdb 设备类型目录名 → App DeviceType 简写（按关键字匹配，优先级从高到低）
DEVICE_TYPE_RULES = [
    (('tv',), 'tv'),
    (('air condition', 'aircondition'), 'ac'),
    (('satellite', 'cable', 'set top', 'dvb', 'dvr', 'digital box', 'set-top'), 'stb'),
    (('projector',), 'projector'),
    (('fan', 'ceiling', 'air circulator'), 'fan'),
    (('purifier', 'air cleaner'), 'purifier'),
    (('dvd', 'bluray', 'vcr', 'cassette', 'cd ', 'cd ', 'tape', 'player', 'receiver',
      'amplifier', 'speaker', 'stereo', 'tuner', 'radio', 'audio', 'boombox', 'hifi',
      'home theater', 'karaoke'), 'audio'),
]


def map_device_type(dirname):
    """irdb 设备类型目录名 → 标准简写；无法识别 → other"""
    low = dirname.lower()
    for keys, std in DEVICE_TYPE_RULES:
        if any(k in low for k in keys):
            return std
    return 'other'


def safe_name(s):
    """文件/目录名清洗：去掉路径非法字符（CSV 文件名如 '15,-1.csv' 保留原样，仅防 '..'）"""
    s = s.replace('..', '_')
    return s


def main():
    ap = argparse.ArgumentParser(description='irdb CSV 精选拷贝 + manifest 生成（开发期脚本）')
    ap.add_argument('--src', required=True, help='irdb 仓库 codes/ 目录路径')
    ap.add_argument('--out', default=None, help='输出目录（默认 app/src/main/assets/irdb）')
    ap.add_argument('--max-mb', type=float, default=5.0, help='总大小上限（MB），超限报错')
    args = ap.parse_args()

    if args.out is None:
        here = os.path.dirname(os.path.abspath(__file__))
        args.out = os.path.normpath(os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'irdb'))

    if not os.path.isdir(args.src):
        sys.exit('src 目录不存在: %s' % args.src)

    # 目标目录存在则先清空（保证幂等）
    if os.path.isdir(args.out):
        shutil.rmtree(args.out)
    os.makedirs(args.out, exist_ok=True)

    manifest = {'version': 'irdb', 'brands': {}}
    total = 0
    copied = 0
    skipped = []

    for brand in sorted(os.listdir(args.src)):
        brand_dir = os.path.join(args.src, brand)
        if not os.path.isdir(brand_dir):
            continue
        brand_manifest = {}
        for dev_dir in sorted(os.listdir(brand_dir)):
            dev_path = os.path.join(brand_dir, dev_dir)
            if not os.path.isdir(dev_path):
                continue
            std_type = map_device_type(dev_dir)
            files = []
            for f in sorted(os.listdir(dev_path)):
                if not f.lower().endswith('.csv'):
                    continue
                src_file = os.path.join(dev_path, f)
                size = os.path.getsize(src_file)
                if size == 0:
                    skipped.append('%s/%s/%s (空文件)' % (brand, dev_dir, f))
                    continue
                dst_dir = os.path.join(args.out, brand, std_type)
                os.makedirs(dst_dir, exist_ok=True)
                # 不同 irdb 子目录可能存在同名 CSV（如 7,7.csv），冲突时加数字后缀避免覆盖
                dst_file = safe_name(f)
                dst_path = os.path.join(dst_dir, dst_file)
                i = 1
                while os.path.exists(dst_path):
                    root, ext = os.path.splitext(dst_file)
                    dst_file = '%s_%d%s' % (root, i, ext)
                    dst_path = os.path.join(dst_dir, dst_file)
                    i += 1
                shutil.copy2(src_file, dst_path)
                files.append(dst_file)
                total += size
                copied += 1
            if files:
                brand_manifest.setdefault(std_type, []).extend(files)
        if brand_manifest:
            manifest['brands'][brand] = brand_manifest

    limit = int(args.max_mb * 1024 * 1024)
    print('拷贝 CSV: %d 个文件, 总大小 %.2f MB（上限 %s MB）' % (copied, total / 1048576, args.max_mb))
    print('品牌数: %d' % len(manifest['brands']))
    if skipped:
        print('跳过（空文件）: %d 个: %s' % (len(skipped), '; '.join(skipped[:10])))
    if total > limit:
        sys.exit('irdb 总大小 %d 字节超过上限 %d 字节，请减少品牌后重试' % (total, limit))

    with open(os.path.join(args.out, 'manifest.json'), 'w', encoding='utf-8', newline='') as f:
        json.dump(manifest, f, ensure_ascii=False, separators=(',', ':'))
    print('已写出 manifest.json（品牌 %d 个 → 设备类型 → CSV 列表）' % len(manifest['brands']))


if __name__ == '__main__':
    main()
