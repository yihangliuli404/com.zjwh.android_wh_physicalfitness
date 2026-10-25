# OBS 交互与结算响应：原代码分析及 Mock 替代

## OBS 实际调用链

`SportClient792.execute_run → RunningClient.execute_full_run → builder.build_submission_payload → request_obs_upload_url → upload_to_obs → submit_record`。

1. builder 先生成三份结果：save_record 字典、OBS 字典、对象 key。对象 key 含开始时间所属小时与 UUID。GPS、步频/速度桶、固定点等逐字段 gzip+Base64 后放入外层 JSON。
2. `request_obs_upload_url` 通过通用加密客户端申请临时 PUT 地址，请求含 bucketName/objectKey/method/contentType。仅 error=10000 且有 data 时整理 data；字符串 data 尝试 JSON 解码。
3. `upload_to_obs(signed_url,signed_headers,payload)` 把外层字典紧凑 JSON UTF-8 序列化，复制签名请求头（或默认 text/plain），同步 urllib PUT，TLS 使用默认受信任 SSL context，超时 30 秒。
4. 2xx → `{error:10000,message:...}`；HTTPError → HTTP code/原因；其他异常（包括超时、连接等）统一 error=-1。原实现没有细分网络错误，没有自动重试、可取消任务、后台队列或状态持久化。「异步推送」注释不符合函数同步等待实现。
5. PUT 失败立即返回失败结果，不执行后续结算；PUT 成功只证明该对象传输被认为成功，不能证明结算或入账。
6. data 不一定为对象、signedUrl 可能缺失，后续 `.get`/Request 构造部分在保护范围外，原错误分类不完整。没有把这些未经实测的情况描述成已复现服务器行为。

## 结算实际模型与条件

逐字段的实际字典值表达式和类型推断见[结算与 OBS 字段索引](结算字段索引.md)，不把 Python 类型标注误当成服务端 schema 校验。

`builder.build_submission_payload` 输出普通 dict，没有强类型请求 DTO。字段分为：

| 字段族 | 实际字段 / 类型与检查 |
| --- | --- |
| 身份与关联 | uid/selectedUnid/sportType 为数值；uuid 字符串；不迁入 Android Mock |
| 距离和时间 | totalDis/validDis、totalTime/validTime、startTime/stopTime；总时间正整数、毫秒差一致、距离有限正数及 GPS 距离一致性先验证 |
| 完成度 | complete 为目标距离/时间达到学校阈值的布尔结果；unCompleteReason 为本地推断值；status/errorCode 等本地常量不能代表服务器判定 |
| 传感器/辅助 | totalSteps、avgStepFreq、speed、calorie、avgPower、10 秒分桶；speed 为距离/秒×1000 的数值，不是页面配速 |
| 轨迹/必经点 | allLocJson 字符串、fivePointJson 字符串、speedPerTenSec/stepsPerTenSec 数组；计分模式 fixed-point 字符串外还有 JSON envelope |
| 其他 | policy、selDistance、selRunTime、latitude/longitude（第一点）、geo-fence 元数据、签名与 originalSign 等；本任务不复制签名常量或平台报文生成器 |

`submit_record` 将字典交给通用加密 POST。原 client 只按响应 dict 的 `error == 10000` 判为该请求成功，从 data 取 rrid 或 id；未定义完整错误码表，其他码作为失败传播。成功后等待 0.5 秒，用 rrid 调 `history.get_one_record`，再做详情配置与页面曝光回刷。

**关键局限**：原 client 在无 rrid 或历史详情为空时默认 `is_valid=True`，且返回 `success=True`；详情模型自身也只按 status/reason 判定。HTTP 成功、业务受理、记录可独立查询是不同条件，不能直接套用原布尔返回值。

## Android：真实 HTTP 的本机 Mock 替代

不申请真实 OBS URL，不复制真实校园域名或登录凭据，不配置任意外部服务地址。APK 的普通 INTERNET 权限仅由固定 `127.0.0.1` 传输实现使用；HttpURLConnection 强制 `Proxy.NO_PROXY`，不跟随重定向；网络安全配置只允许 127.0.0.1 明文 HTTP。系统权限本身不是目的地址防火墙，目的地址限制由应用代码固定构造保证。

- PUT `/test/uploads/{taskId}`：`TestPayload(testOnly=true,taskId,record)`，服务端验证轨迹后只保存在当前 Mock 进程内存。请求头选择预设场景，不能指定远端地址。
- POST `/test/settlements/{taskId}`：`SettlementRequest(testOnly=true,taskId)`；必须是当前 Mock 进程已接收的轨迹，否则 409。可返回 403、202、200 业务失败、畸形 JSON、超时/断连或 completed=true。
- GET `/test/credits/{taskId}`：独立查询 `mock_credits_only.db` SQLite 测试表，返回匹配任务、testOnly、code、credited 和 receiptId。没有有效回执即未确认。
- `MockReply` 所需字段严格解析；HTTP 非 2xx、业务 code 非零、仅受理、格式错误、任务 ID 不匹配、断连和超时分别显示。800 ms 客户端超时，预设慢响应 2000 ms；请求体上限 2 MiB，响应上限 16 KiB，日志仅保留响应前 4096 字符。

Mock 的测试成绩写入发生于模拟结算完成分支，与生产业务无关。一个预设场景故意「结算完成但不写测试库」，验证客户端保持入账未确认。重复结算使用任务 ID 唯一键，不重复插入测试成绩。测试库、测试任务 DataStore 和运动档案 DataStore 分开存储；并未集成真实成绩只读接口。

后台/进程退出后，内存上传缓存会消失；测试 SQLite 和任务日志保留。重新结算若缺内存上传会返回 409，不能伪造恢复上传。进行中的状态在冷启动标记为「进程中断·需回查」，独立 GET 仍可确认已写入的测试库回执。
