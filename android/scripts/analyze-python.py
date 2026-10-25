#!/usr/bin/env python3
"""Read Python AST without importing or executing any protocol module."""
import ast
import re
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
root = repo / '协议/7.3.92'
docs = repo / 'android/docs'
assets = repo / 'android/app/src/main/assets/analysis'
assets.mkdir(parents=True, exist_ok=True)
modules = ['core', 'login', 'profile', 'policy', 'telemetry', 'running', 'history', 'sport_client.py', 'cli.py']
notes = {
 'core':'公共设备上下文、请求头签名、NetSec 加解密与 urllib 传输。execute_request 的网络段捕获 HTTPError/Exception，返回错误字典；构造头部发生在 try 外，仍可抛异常。静态分析，不集成真实接口。',
 'login':'LoginClient.login 编排 checkGeeUse、可选验证码、geevalidate、submit_login。captcha.py 通过 Node 子进程调用 Playwright/Chromium，解析 FINAL_RESULT。缺少执行文件、子进程超时或求解失败可能抛出异常。Android 不移植登录或验证码。',
 'profile':'ProfileClient 继承 BaseNetSecClient，读取服务器时间、个人及首页资料。get_server_time 接口失败会回退本机时间，因此返回模型不证明同步成功。档案失败通常返回 None。',
 'policy':'读取跑步规则、围栏、学期和路线点；GeoPoint 校验有限数和范围，GCJ 锚点必须显式存在。点位请求捕获解析错误并记录 last_checkpoint_error；Android 不获取校园打卡点。',
 'telemetry':'启动/页面/设备埋点、控制信令和风险/排行榜读取。_post_json 10 秒超时，控制信令 5 秒；失败变成 error=-1。四个委托读取函数要求传入有 execute_request 的对象。原 facade 传入自身但自身没有该方法，存在静态调用不匹配。Android 不上传遥测或广告。',
 'running':'RunningTrackBuilder 构造 GpsPoint/FivePoint、10 秒分桶、结算字典及逐字段 gzip+Base64 OBS 数据；RunningClient 同步执行预签名、PUT、结算和历史回查。没有持久任务队列。回查缺失时默认 is_valid=True，不能作为真实入账证据。Android 仅重写本地几何与 Mock 三阶段。',
 'history':'HistoryClient 提供详情、分页跑步/室内记录、日历和看板读取。详情模型按 status=1 且 unCompleteReason=0 计算 is_valid；CLI 的 history_record_status 另检查 complete/isValid，二者判断并不完全一致。详情未查到返回 None，不应认定已入账。',
 'sport_client.py':'SportClient792 门面注入 login/profile/policy/running/history/telemetry/ads；登录成功后下发凭据。execute_run 委托 RunningClient。部分 telemetry 委托把门面当 BaseNetSecClient 使用，类型能力不匹配。这里只作静态分析。',
 'cli.py':'main 用 argparse 选择登录、时间、档案、规则、点位、历史、广告及运动提交。未指定操作时默认同步时间，可能发出网络请求；原登录成功日志会打印 Token，不复用这类日志。Android 不执行原 CLI。'
}
all_files = sorted({p for m in modules for p in ((root / m).glob('*.py') if (root / m).is_dir() else [root / m])})
file_set = {str(p.relative_to(root)).replace('/', '.').removesuffix('.py'): p for p in all_files}
edges = set()
sections = {}
for module in modules:
 lines = [f'{module} 源码分析', notes[module], '说明：索引基于 AST；不执行模块，不调用远端接口。参数列出名称、类型及默认值是否存在；字符串默认值不展示，避免散播协议常量。']
 paths = sorted((root / module).glob('*.py')) if (root / module).is_dir() else [root / module]
 for path in paths:
  text = path.read_text(encoding='utf-8-sig'); tree = ast.parse(text)
  relative = path.relative_to(root).as_posix(); identity = relative.replace('/', '.').removesuffix('.py')
  lines += ['', f'文件：协议/7.3.92/{relative}']
  imports=[]
  for node in ast.walk(tree):
   if isinstance(node, ast.Import): imports.extend(a.name for a in node.names)
   elif isinstance(node, ast.ImportFrom):
    base = node.module or ''
    if node.level:
     parents = identity.split('.')[:-1]
     base = '.'.join(parents[:len(parents)-node.level+1] + ([base] if base else []))
    imports.append(base)
  lines += ['导入依赖：' + ', '.join(sorted(set(imports)))]
  for dependency in imports:
   if dependency in file_set: edges.add((identity, dependency))
   elif identity.rsplit('.',1)[0]+'.'+dependency in file_set: edges.add((identity,identity.rsplit('.',1)[0]+'.'+dependency))
  def visit(nodes, owner=''):
   global lines
   for node in nodes:
    if isinstance(node, ast.ClassDef):
     fields = [f'{x.target.id}: {ast.unparse(x.annotation)}' for x in node.body if isinstance(x,ast.AnnAssign) and isinstance(x.target,ast.Name)]
     lines.append(f'类 {node.name}（L{node.lineno}），父类：'+(', '.join(ast.unparse(x) for x in node.bases) or '无'))
     if fields: lines.append('数据字段：'+'; '.join(fields))
     visit(node.body,node.name+'.')
    elif isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef)):
     positional=node.args.posonlyargs+node.args.args
     defaults=[None]*(len(positional)-len(node.args.defaults))+node.args.defaults
     params=[]
     for arg,default in zip(positional,defaults):
      value=arg.arg+(': '+ast.unparse(arg.annotation) if arg.annotation else '')
      if default is not None:
       value+=' = '+('字符串默认值' if isinstance(default,ast.Constant) and isinstance(default.value,str) else ast.unparse(default))
      params.append(value)
     if node.args.vararg:params.append('*'+node.args.vararg.arg)
     for arg,default in zip(node.args.kwonlyargs,node.args.kw_defaults): params.append(arg.arg+('（可选）' if default is not None else '（必填）'))
     if node.args.kwarg:params.append('**'+node.args.kwarg.arg)
     ret=ast.unparse(node.returns) if node.returns else '源码未标注类型，见返回分支'
     lines += [f'{owner}{node.name}({", ".join(params)}) → {ret}（L{node.lineno}）']
     doc=ast.get_docstring(node)
     if doc:lines.append('功能：'+' '.join(doc.split()))
     calls=sorted({ast.unparse(n.func) for n in ast.walk(node) if isinstance(n,ast.Call)})
     returns=sorted({ast.unparse(n.value)[:150] for n in ast.walk(node) if isinstance(n,ast.Return) and n.value is not None})
     handlers=sorted({ast.unparse(n.type) if n.type else '所有异常' for n in ast.walk(node) if isinstance(n,ast.ExceptHandler)})
     raises=sorted({ast.unparse(n.exc.func if isinstance(n.exc,ast.Call) else n.exc) for n in ast.walk(node) if isinstance(n,ast.Raise) and n.exc is not None})
     if returns:lines.append('输出表达式（静态）：'+'; '.join(returns))
     lines.append('调用：'+(', '.join(calls) or '无显式函数调用'))
     lines.append('本函数捕获：'+(', '.join(handlers) or '无显式捕获，异常可能向上传播')+'；显式抛出：'+(', '.join(raises) or '无'))
  visit(tree.body)
 content='\n'.join(lines)+'\n'
 content=re.sub(r'(?:https?://)?(?:[A-Za-z0-9-]+\.)+(?:com|cn|net|org|dev)(?:/[A-Za-z0-9_./-]*)?', '[外部服务地址省略]', content)
 sections[module]=content
 (assets/f'{module}.txt').write_text(content,encoding='utf-8')
index=['# 7.3.92 源码接口完整索引','','由 `python3 android/scripts/analyze-python.py` 基于当前源码 AST 生成。没有导入、执行原协议代码。静态返回表达式不代表实测成功，分支/异常仍须结合主分析报告。嵌套函数调用可能一并列出，动态分派无法仅靠 AST 完全证明。','']
for module,content in sections.items():index += [f'## {module}','','```text',content.rstrip(),'```','']
(docs/'源码接口索引.md').write_text('\n'.join(index),encoding='utf-8')
graph=['# 实际文件导入依赖图','','覆盖指定九个模块的全部 Python 文件；下图为静态内部导入边，标准库、Node/浏览器和 ads 依赖另见主报告。`sys.path` 动态注入仍可能影响运行时解析。','','```mermaid','flowchart LR']
ids={name:f'n{i}' for i,name in enumerate(sorted(file_set))}
for name,ident in ids.items():graph.append(f'  {ident}["{name}"]')
for a,b in sorted(edges):graph.append(f'  {ids[a]} --> {ids[b]}')
graph += ['```','']
(docs/'文件依赖图.md').write_text('\n'.join(graph),encoding='utf-8')
print(f'Analyzed {len(all_files)} source files, {len(edges)} import edges, {len(sections)} module assets; no protocol execution.')
