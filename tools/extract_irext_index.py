# -*- coding: utf-8 -*-
"""
开发期脚本：从 irext 官方 sqlite3.db 提取五级精简索引 → assets/irext/irext-index.json

数据来源（MIT 许可，官方仓库）：
  https://github.com/irext/database  →  db/irext_db_20260519_sqlite3.db（Git LFS，约 103MB）
  https://github.com/irext/database  →  binaries/irext-binaries_20260519.zip（Git LFS，约 1.76MB）

用法（仅开发用，不打包进 APK）：
  python tools/extract_irext_index.py --db <irext_db_sqlite3.db路径> \
      [--out app/src/main/assets/irext/irext-index.json] \
      [--binaries app/src/main/assets/irext/irext-binaries.zip]   # 可选：校验 bin 文件名存在性

输出 JSON 结构（计划 §4.1，压缩格式 ≤3MB）：
  {
    "version": "20260519",
    "categories": [
      {
        "id": 1, "name": "空调", "nameEn": "AC",
        "brands": [
          {
            "id": 1001, "name": "格力",
            "areas": [], "operators": [],          // 非机顶盒为空
            "remotes": [ { "id": 3459, "name": "new_ac_10712", "bin": "irda_new_ac_10712.bin" } ]
          }
        ]
      },
      {
        "id": 3, "name": "机顶盒", "nameEn": "STB",
        "brands": [
          {
            "id": 0, "name": "运营商机顶盒",          // STB 无品牌数据，用虚拟品牌承载省市运营商链
            "areas": [ { "name": "广东省", "cities": [
              { "name": "深圳市", "operators": [
                { "operator": "深圳天威视讯", "remotes": [ { "id": 6001, "name": "remote_box_xxx", "bin": "irda_xxx_remote_box_xxx.bin" } ] }
              ] }
            ] } ]
          }
        ]
      }
    ]
  }

实现要点：
  - 仅用 Python 标准库 sqlite3 / json / zipfile，无第三方依赖
  - 只保留 id/name/bin 等小字段，丢弃 decode_remote（10 万行按键码）、collect_* 等超大表
  - 空调 AC 参数说明：温度范围/模式位掩码等 acParams 数据不在 sqlite 中（在 .bin 二进制内部，
    由 JNI getTemperatureRange/getACSupportedMode 运行时读取，见计划 §3.3），故索引不输出 acParams
  - remote_index 表内嵌的 city_name/operator_name 部分记录含损坏字节（如 '北京�?'），
    省市名改从 city 表按行政区划代码匹配（数据完整），运营商名做字符白名单清洗
  - bin 文件名 = "irda_{remote_map}.bin"，与官方 binaries zip 内文件名一一对应（脚本逐一校验）
"""
import argparse
import json
import os
import re
import sqlite3
import sys
import zipfile

# 机顶盒类 category_id（大陆 STB=3，台湾 STB=11）：走 省→市→运营商 链
STB_CATEGORY_IDS = {3, 11}

# 名称清洗：只保留中文/全角/字母数字/常见符号，丢弃损坏字节
_CLEAN_RE = re.compile(r'[^\u4e00-\u9fff\u3000-\u303f\uff00-\uffefA-Za-z0-9()（）·\-+_ .,]')


def clean_name(s):
    """清洗损坏/截断的名称字节，'北京�?' → '北京'"""
    if not s:
        return ''
    return _CLEAN_RE.sub('', s).strip()


def load_province_city_map(cur):
    """从 city 表构建 代码→名称 映射（代码 6 位行政区划码，如 110000 北京市 / 440300 深圳市）"""
    code2name = {}
    for code, name in cur.execute("SELECT code, name FROM city WHERE length(code)=6").fetchall():
        code2name[code] = clean_name(name)
    return code2name


def main():
    ap = argparse.ArgumentParser(description='irext sqlite3.db → 精简索引 JSON（开发期脚本）')
    ap.add_argument('--db', required=True, help='irext_db_*_sqlite3.db 路径')
    ap.add_argument('--out', default=None,
                    help='输出 JSON 路径（默认 app/src/main/assets/irext/irext-index.json）')
    ap.add_argument('--binaries', default=None,
                    help='irext-binaries.zip 路径（可选，用于校验 bin 文件名存在性）')
    ap.add_argument('--max-mb', type=float, default=3.0, help='体积上限（MB），超限报错')
    args = ap.parse_args()

    if args.out is None:
        here = os.path.dirname(os.path.abspath(__file__))
        args.out = os.path.normpath(os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'irext', 'irext-index.json'))

    conn = sqlite3.connect(args.db)
    cur = conn.cursor()

    # ---- 1. 表结构核对（字段名以实际表为准，先打印再映射） ----
    tables = [r[0] for r in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()]
    print('[1/5] 表结构核对: %s' % ', '.join(tables))
    required = {'category', 'brand', 'remote_index', 'city'}
    missing = required - set(tables)
    if missing:
        sys.exit('缺少必需表 %s，无法提取，请核对数据库文件' % sorted(missing))

    # ---- 2. 读 category / brand ----
    categories = []
    print('[2/5] 读取 category/brand 表…')
    for cid, name, name_en in cur.execute(
            'SELECT id, name, name_en FROM category ORDER BY id').fetchall():
        categories.append({'id': cid, 'name': clean_name(name), 'nameEn': name_en or '',
                           'brands': []})
    brand_rows = cur.execute(
        'SELECT id, category_id, name, name_en FROM brand ORDER BY category_id, id').fetchall()
    print('      category=%d 个, brand=%d 行' % (len(categories), len(brand_rows)))

    # ---- 3. 读 remote_index（只取小字段） ----
    print('[3/5] 读取 remote_index 表（%d 行）…' %
          cur.execute('SELECT COUNT(*) FROM remote_index').fetchone()[0])
    ri_rows = cur.execute('''SELECT id, category_id, brand_id, city_code, city_name,
                                    operator_id, operator_name, remote_map, status
                             FROM remote_index''').fetchall()
    status_cnt = {}
    for r in ri_rows:
        status_cnt[r[8]] = status_cnt.get(r[8], 0) + 1
    print('      status 分布: %s' % status_cnt)
    # 只保留 status=1（有效）记录；其余丢弃
    ri_rows = [r for r in ri_rows if r[8] == 1]
    print('      status=1 记录: %d 行' % len(ri_rows))

    # ---- 4. 构建品牌→遥控器 与 省→市→运营商→遥控器 链 ----
    code2name = load_province_city_map(cur)   # 行政区划代码 → 名称（数据完整）
    brand_map = {cid: [] for cid, _ in enumerate([c['id'] for c in categories], 0)}
    # 品牌索引：category_id -> {brand_id: brand dict}
    brand_by_cat = {}
    for bid, cid, name, name_en in brand_rows:
        brand_by_cat.setdefault(cid, {})[bid] = {
            'id': bid, 'name': clean_name(name), 'nameEn': name_en or '',
            'areas': [], 'operators': [], 'remotes': []}

    # bin 文件名集合（校验用）
    bin_set = set()
    if args.binaries:
        with zipfile.ZipFile(args.binaries) as zf:
            bin_set = {n.split('/')[-1] for n in zf.namelist()}
        print('      校验用 binaries zip: %d 个文件' % len(bin_set))

    def make_remote(rid, remote_map):
        """构造遥控器节点；bin 名 = irda_{remote_map}.bin，缺失则返回 None"""
        if not remote_map:
            return None
        bin_name = 'irda_%s.bin' % remote_map
        if bin_set and bin_name not in bin_set:
            return None   # 二进制文件缺失的记录跳过（全库仅 1 条，见实测记录）
        return {'id': rid, 'name': remote_map, 'bin': bin_name}

    miss_bin = 0
    for rid, cid, bid, city_code, city_name, oid, op_name, remote_map, status in ri_rows:
        cat = next((c for c in categories if c['id'] == cid), None)
        if cat is None:
            continue
        if cid in STB_CATEGORY_IDS:
            # ---- 机顶盒：省→市→运营商→遥控器 ----
            # 省份：行政区划码前 2 位 + '0000'；城市：city_code 本身；名称一律从 city 表取
            prov_code = (city_code[:2] + '0000') if city_code and len(city_code) >= 2 else ''
            prov_name = code2name.get(prov_code) or '未知省份'
            city_name = code2name.get(city_code) or clean_name(city_name)
            op = clean_name(op_name) or '其他运营商'
            if not cat['brands']:
                cat['brands'].append({'id': 0, 'name': '运营商机顶盒', 'nameEn': 'STB',
                                      'areas': [], 'operators': [], 'remotes': []})
            brand = cat['brands'][0]
            area = next((a for a in brand['areas'] if a['name'] == prov_name), None)
            if area is None:
                area = {'name': prov_name, 'cities': []}
                brand['areas'].append(area)
            city = next((ci for ci in area['cities'] if ci['name'] == city_name), None)
            if city is None:
                city = {'name': city_name, 'operators': []}
                area['cities'].append(city)
            oper = next((o for o in city['operators'] if o['operator'] == op), None)
            if oper is None:
                oper = {'operator': op, 'remotes': []}
                city['operators'].append(oper)
            rem = make_remote(rid, remote_map)
            if rem:
                oper['remotes'].append(rem)
            else:
                miss_bin += 1
        else:
            # ---- 非机顶盒：品牌 → 遥控器 ----
            brand = brand_by_cat.get(cid, {}).get(bid)
            if brand is None:
                continue   # 品牌缺失的记录丢弃（属脏数据）
            rem = make_remote(rid, remote_map)
            if rem:
                brand['remotes'].append(rem)
            else:
                miss_bin += 1

    # 挂载品牌（含空品牌保留——向导第 2 步过滤）
    for cat in categories:
        cat['brands'] = list(brand_by_cat.get(cat['id'], {}).values()) if cat['id'] not in STB_CATEGORY_IDS else cat['brands']
    print('      缺失 bin 跳过: %d 条' % miss_bin)

    # ---- 5. 压缩输出 JSON 并校验体积 ----
    version = os.path.basename(args.db)
    m = re.search(r'(\d{8})', version)
    out_doc = {'version': m.group(1) if m else 'unknown', 'categories': categories}
    text = json.dumps(out_doc, ensure_ascii=False, separators=(',', ':'))
    size = len(text.encode('utf-8'))
    limit = int(args.max_mb * 1024 * 1024)
    print('[4/5] JSON 体积: %.2f MB（上限 %s MB）' % (size / 1048576, args.max_mb))
    if size > limit:
        sys.exit('索引体积 %d 字节超过上限 %d 字节，请裁剪字段或减少品牌后重试' % (size, limit))

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8', newline='') as f:
        f.write(text)
    print('[5/5] 已写出: %s' % args.out)
    conn.close()


if __name__ == '__main__':
    main()
