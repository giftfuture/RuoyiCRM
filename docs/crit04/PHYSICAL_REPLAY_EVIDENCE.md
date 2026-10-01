# RuoyiCRM CRIT-02/04 本地物理双栈回执（2026-10-02）

## 运行身份与边界

- 源版：Git `d742330901b40126f482c5f092ca1ba43a3c7ebc`，JDK 11 构建的 `ruoyi-admin.jar` SHA-256 `27e857f89ed7261f01242b58cf56713234dd6e138ab838f4c2a6ff3a07d506af`，本地 HTTP `127.0.0.1:62240`。由于原提交引用仓库中不存在的 `ruoyi-quartz` 和 `ruoyi-generator` 模块，隔离副本仅应用 `docs/crit10/baseline-build-fix.patch` 中的 POM 依赖删除；Java 源码未改。
- 目标版：JDK 21 / Spring Boot 4.1.1 构建的 `ruoyi-admin.jar` SHA-256 `e872cd75a16f8a63590c18a9fbec103e57dba66a2f3643ee08862de917d732e3`，本地 HTTP `127.0.0.1:51203`，`SPRING_MAIN_ALLOW_CIRCULAR_REFERENCES=false`；日志出现 `Started RuoYiApplication`。目标工作树在并发开发中，JAR 摘要比工作树状态更准确。目标 Maven clean package 的 29 项测试中 28 项通过、1 项物理测试在通用构建中跳过；该物理租户测试另在目标一次性 MySQL 上单独通过 1/1。
- 两端分别使用 `deploy/crit10/compose.yaml` 启动的一次性 MySQL 8.4 / Redis 7 项目 `ruoyicrm-crit10-1ce7cc812e` 与 `ruoyicrm-crit10-2167cb7ef5`。各服务仅绑定 `127.0.0.1`；SQL 初始化只保留仓库 DDL 和两条合成租户路由数据，不导入样例账号或业务数据。两个 `probe-receipt.json` 均为 `LOCAL_PHYSICAL_STACK_READY`，含 TCP、SQL 表/行数和 Redis PONG 检查。端口和私有口令在销毁/重建后会变更。
- 两端 tenant1 库各创建四条最小合成鉴权数据（部门、角色、用户、关联），用一次性随机口令登录；验证码写入各自的一次性 Redis。登录按原业务代码更新合成用户最后登录信息并写入 `sys_logininfor`。没有导入源码样例账号、手机号或业务数据；没有执行非 GET 的业务回放。私有口令及令牌只存于 CRIT-10 运行目录，Git 回执只保存状态、Content-Type、JSON `code` 和响应摘要。认证引导后的非空表范围检查见 `post-replay-data-scope.json`：两端主库仅 `master_tenant` 2 行、tenant1 仅 `sys_dept`/`sys_role`/`sys_user`/`sys_user_role` 各 1 行和 `sys_logininfor` 各 4 行，tenant2 零行。行级差分、数据库写后补偿仍 `NOT_RUN`。

## 29 条基线 oracle 的目标回放

`docs/crit04/baseline-oracles/` 包含 9 条未认证权限负向 oracle 与 20 条认证只读 GET oracle，均由固定源版真实 HTTP 采集，记录源提交、Controller 源码摘要及响应摘要。运行脚本从私有目录注入两端令牌后执行：

```bash
CRIT04_BASELINE_TOKEN="$BASELINE_DISPOSABLE_TOKEN" CRIT04_TARGET_TOKEN="$TARGET_DISPOSABLE_TOKEN" \
python3 scripts/crit04_replay.py replay \
  --baseline http://127.0.0.1:62240 --target http://127.0.0.1:51203 \
  --fixtures docs/crit04/local-fixtures.json \
  --output docs/crit04/dual-replay-receipt.json
```

结果为 `planned=150, passed=20, failed=9, not_run=121`，命令按设计退出码 `2`。20 条认证只读 GET 在源版与目标版之间 HTTP 状态、Content-Type、JSON 值和**原始响应字节**全部一致。此前目标默认 Jackson 3 HTTP 转换器产生的字段顺序差异和 8 小时时间值差异，在目标显式采用 Jackson 2 HTTP 转换器后均消失。9 条负向用例保持 `BEHAVIOR_DRIFT`：源版 HTTP 200 / JSON `code:401`，目标 HTTP 401 / JSON `code:401`；每条响应体 SHA-256 完全相同，目标 Content-Type 的 `charset=UTF-8` 与源版 `charset=utf-8` 大小写不同。HTTP 401 是可观察的安全 API 变更，比较器未豁免；`ruoyi-ui/src/utils/request.js` 已兼容 Axios 的 HTTP 401 错误分支。剩余 121 条因 41 条缺 fixture 和 80 条非 GET 尚未满足安全写入回放条件而 `NOT_RUN`。原始字节比较、仅用于定位的规范化 JSON 摘要与差异维度都保存在同一回执；规范化诊断不会改变失败判定。

## Actuator 与 CRIT-02 启动探针

对最终目标 JAR 执行无令牌 GET：

| 路径 | HTTP | JSON 状态 / 业务码 | 判读 |
| --- | ---: | --- | --- |
| `/actuator/health` | 200 | `status:UP` | 本地根健康探针可用 |
| `/actuator/health/liveness` | 200 | `status:UP` | 本地应用存活 |
| `/actuator/health/readiness` | 200 | `status:UP` | 本地就绪 |
| `/actuator/info` | 401 | `code:401` | 未公开 |
| `/actuator/prometheus` | 401 | `code:401` | 未公开；回滚监控若依赖此端点需要另建授权/采集方案 |

静态 SCC 审计见 `docs/crit02/scc-audit.json`；本次目标在显式禁止 Spring 循环依赖时完成物理启动，补强了静态解析结果，但仅证明当前装配路径。该证据为 `LOCAL_EXECUTED_SELF_ATTESTED`；独立/外部验证 `NOT_RUN`，生产认证 `NOT_CERTIFIED`。
