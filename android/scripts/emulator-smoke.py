#!/usr/bin/env python3
"""Offline UI smoke on a disposable emulator only. Does not use any account.

Usage: python3 scripts/emulator-smoke.py --serial emulator-5554 --reset
--reset explicitly clears THIS APP'S emulator data; never run on a physical device.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--reset', action='store_true')
parser.add_argument('--mock-only', action='store_true', help='skip already-tested base UI and analyze pages')
parser.add_argument('--output', default='docs/evidence/emulator')
parser.add_argument('--apk', default='app/build/outputs/apk/debug/app-debug.apk')
args = parser.parse_args()
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
results = []


def adb(*arguments, binary=False):
    return subprocess.check_output(['adb', '-s', args.serial, *arguments], timeout=45,
                                   text=not binary)


def dump():
    for attempt in range(5):
        adb('shell', 'rm', '-f', '/sdcard/local-fitness-ui.xml')
        adb('shell', 'uiautomator', 'dump', '/sdcard/local-fitness-ui.xml')
        try:
            text = adb('exec-out', 'cat', '/sdcard/local-fitness-ui.xml')
            return text, ET.fromstring(text)
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(2)
    raise AssertionError('UIAutomator 未生成有效的新界面 XML；不能认定界面检查通过')


def find(text, scroll=False, last=False):
    for attempt in range(6 if scroll else 3):
        xml, root = dump()
        nodes = [n for n in root.iter('node') if n.get('text') == text]
        if nodes:
            return max(nodes, key=lambda n: int(re.findall(r'\d+', n.get('bounds'))[3])) if last else nodes[0]
        if scroll:
            adb('shell', 'input', 'swipe', str(width // 2), str(int(height * .78)), str(width // 2), str(int(height * .28)), '300')
        else:
            time.sleep(1)
    raise AssertionError(f'未找到界面文本：{text}')


def tap(text, scroll=False, last=False):
    node = find(text, scroll, last)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1)


def evidence(name, expected):
    for attempt in range(6):
        xml, root = dump()
        texts = [n.get('text', '') for n in root.iter('node')]
        if all(any(text in candidate for candidate in texts) for text in expected):
            break
        time.sleep(2)
    else:
        raise AssertionError((name, expected, texts))
    (out / f'{name}.xml').write_text(xml, encoding='utf-8')
    (out / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    results.append({'check': name, 'passed': True, 'expected': expected})
    (out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'PASS {name}', flush=True)


width, height = map(int, adb('shell', 'wm', 'size').strip().splitlines()[-1].split(':')[-1].strip().split('x'))
assert args.serial.startswith('emulator-'), '此脚本拒绝真实设备'
assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', '此脚本只允许模拟器'
assert Path(args.apk).is_file(), '请先 assembleDebug 生成真实 APK'
if args.reset:
    if adb('shell', 'pm', 'path', 'cn.local.fitness').strip():
        assert 'Success' in adb('uninstall', 'cn.local.fitness')
assert 'Success' in adb('install', '-r', args.apk)
adb('shell', 'am', 'force-stop', 'cn.local.fitness')
adb('shell', 'am', 'start', '-W', '-n', 'cn.local.fitness/.MainActivity')
time.sleep(3)
if not args.mock_only:
    evidence('01-home', ['本地运动实验室', '完全离线'])
    tap('添加运动记录')
    find('保存到本机')
    tap('保存到本机')
    tap('记录')
    adb('shell', 'input', 'swipe', str(width // 2), str(int(height * .75)), str(width // 2), str(int(height * .4)), '300')
    evidence('02-manual-record', ['我的运动', '手动记录·未认证', '5′00″/公里'])
    tap('模拟', last=True)
    tap('生成本地预览', scroll=True)
    tap('保存此模拟样本', scroll=True)
    tap('记录')
    tap('模拟')
    adb('shell', 'input', 'swipe', str(width // 2), str(int(height * .75)), str(width // 2), str(int(height * .4)), '300')
    evidence('03-simulated-record', ['本地模拟样本', '模拟轨迹·仅本地'])
    tap('首页')
    tap('查看本地测试记录', scroll=True)
    tap('执行离线自检')
    evidence('04-local-self-check', ['通过 · 应用内离线自检', '5 项检查通过'])
    adb('shell', 'am', 'force-stop', 'cn.local.fitness')
    adb('shell', 'am', 'start', '-W', '-n', 'cn.local.fitness/.MainActivity')
    time.sleep(2)
    tap('记录')
    adb('shell', 'input', 'swipe', str(width // 2), str(int(height * .75)), str(width // 2), str(int(height * .4)), '300')
    evidence('05-persisted-after-restart', ['我的运动', '本地模拟样本'])
    tap('工具')
    evidence('06-calculator', ['5′00″/公里', '12.00 公里/小时'])
    print(f'{len(results)} emulator smoke checks passed; not a physical-device test.', flush=True)

    tap('首页')
    tap('协议模块分析', scroll=True)
    evidence('07-protocol-analysis', ['静态分析', 'core'])
    tap('首页')
    tap('GPS 轨迹分析', scroll=True)
    evidence('08-gps-analysis', ['GPS 轨迹分析', '导入 JSON / CSV 轨迹'])
tap('首页')
tap('三级状态验证', scroll=True)
tap('创建测试任务（不自动发送）', scroll=True)
time.sleep(2)

def verify_task(name, upload, settlement, credit):
    for attempt in range(12):
        raw = adb('exec-out', 'run-as', 'cn.local.fitness', 'cat', 'files/datastore/mock_tasks_only.preferences_pb', binary=True)
        text = raw.decode('utf-8', errors='ignore')
        begin = text.find('[')
        data, _ = json.JSONDecoder().raw_decode(text[begin:])
        task = data[0]
        if [task[p]['status'] for p in ('upload', 'settlement', 'credit')] == [upload, settlement, credit]:
            (out / f'{name}-task.json').write_text(json.dumps(task, ensure_ascii=False, indent=2), encoding='utf-8')
            xml, _ = dump()
            (out / f'{name}.xml').write_text(xml, encoding='utf-8')
            (out / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
            results.append({'check': name, 'passed': True, 'statuses': [upload, settlement, credit]})
            (out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
            print(f'PASS {name}', flush=True)
            return
        time.sleep(1)
    (out / f'{name}-failed-task.json').write_text(json.dumps(task, ensure_ascii=False, indent=2), encoding='utf-8')
    raise AssertionError((name, {p: task[p] for p in ('upload', 'settlement', 'credit')}))

verify_task('09-task-unsent', 'NOT_SENT', 'NOT_SENT', 'NOT_SENT')
tap('第一级：发送测试轨迹', scroll=True)
verify_task('10-upload-only', 'SUCCESS', 'NOT_SENT', 'NOT_SENT')
tap('第二级：执行 Mock 结算', scroll=True)
verify_task('11-settlement-only', 'SUCCESS', 'SUCCESS', 'NOT_SENT')
tap('第三级：独立查询测试成绩 / 回查', scroll=True)
verify_task('12-independent-sqlite-credit', 'SUCCESS', 'SUCCESS', 'SUCCESS')
print(f'{len(results)} complete smoke checks passed on software emulator; no real platform calls.', flush=True)
