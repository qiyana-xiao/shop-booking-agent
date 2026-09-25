# 店小约（Shop Booking Agent）

> 给小微实体店用的 AI 预约客服：顾客像聊天一样约时间、约人数、改期取消，
> 模型说的每一句"已帮您订好"，数据库里都真的发生了。

```text
顾客说: "明天晚上 6 点, 4 个人, 有包间吗?"
          |
          v
   AI 客服(DeepSeek + Function Calling)
          |
          v  模型只负责"想", 代码负责"做"
   查档期 -> 锁座15分钟 -> 确认建单 -> 出预约卡片
          |
          v
   数据库真的锁了那个时段, 别的顾客同时看到的是"已订满"
```

## 核心能力

- **顾客端（无需注册即可聊）**：自然语言查档期、锁座、建预约单、改期、取消；三层分级应答（店铺事实直接答 → 查知识库 → 留言转达），投诉等特殊情况才转人工，工单带着完整会话摘要，顾客不用重讲一遍。
- **人工接管**：转人工后 AI 自动闭嘴不抢答，店员/老板在工单里回复直达顾客对话窗（店家气泡），工单解决后 AI 恢复接待并能接上人工聊过的上下文。
- **老板端**：4 步开店向导（填一次规则，30 天档期自动生成）；档期日历（点哪改哪，改前可预览影响范围）；AI 接不住的问题一键采纳为知识库标准答案。
- **店员端**：今日预约列表、到店核销、处理转人工工单（含直接回复顾客）。
- **可靠性**：两人同时抢最后一间包间不超卖（乐观锁 + 唯一索引）、弱网重复提交不重复扣库存（幂等键）、锁座 15 分钟超时自动释放。
- **降级可用**：DeepSeek 未配置或挂掉时，自动降级为 FAQ 关键词匹配 + 转人工，不会白屏；Redis 不装也能跑，订单不丢。

## 技术栈（详见《docs/代码技术架构.md》）

```text
前端  React 18 + Vite 5 (纯手绘 CSS, 无 UI 组件库/图表库)
后端  Java 17 + Spring Boot 3.3.5 + MyBatis-Plus
数据库 MySQL 8.0 (唯一业务数据源, 库名 shop_booking)
缓存  Redis 7 (可选, 只存可重建状态, 清空不丢订单)
AI    DeepSeek Chat Completions + Function Calling (strict 模式)
```

## 快速启动（Windows）

**唯一前置条件**：本机安装并启动了 MySQL 8.0（[官方安装包](https://dev.mysql.com/downloads/installer/)）。

环境策略：**优先使用系统已安装的 JDK 17+ / Node 18+ / Redis**；检测不到或版本过低时，
脚本才会询问是否自动下载便携版到项目 `runtime\` 文件夹（仅本项目可用、不写注册表；
删除项目文件夹后，下载的运行时随之清除，不会残留或污染本机已有环境）。
Maven 也**不需要**：仓库自带已构建的后端 jar；仓库不含 `node_modules`，首次启动会自动 `npm install`。

Redis 为**可选组件**（对话记忆/限流/幂等加速，未装订单不丢）：启动时若未检测到，
脚本会说明降级影响并询问是否一键安装便携版（约 12MB）——选"是"自动下载启动，
选"否"跳过照常运行；也可随时手动运行 `scripts\setup-redis.ps1 -Start`（`-Uninstall` 可彻底删除）。

双击 `启动-店小约.bat`，首次运行会依次引导：

1. 检测 Java / Node / Redis（系统已装则直接用，缺失时询问是否自动下载便携版，一次即可）
2. 交互式生成 `backend-java\.env`（填 MySQL 密码；JWT 密钥自动随机生成）
3. 检测到数据库未初始化时，可一键自动执行 `database\sql\01_schema.sql` 建库
4. 启动后端(8080)与前端(5173)，就绪后自动打开浏览器

```text
启动-店小约.bat   双击启动（首次自动引导上述步骤）
停止-店小约.bat   双击停止
体检-店小约.bat   双击做环境体检（只读诊断，缺什么一目了然）
```

启动成功后桌面自动生成快捷方式（已存在则刷新为最新）：

```text
店小约.lnk   双击 = 一键切换：未运行则启动，已运行则停止（日常只用这个）
```

## 默认老板账号

```text
账号：boss
密码：Boss123456
```

开店向导、服务项目、知识库、店铺设置（含 AI 客服密钥）都只能由老板完成，
请用以上账号登录后台。交付给店员或其他店家前，建议登录后尽快修改默认密码。
也可以在登录页自行注册新账号（角色自选，老板请选 owner）。

## AI 密钥配置（DeepSeek，界面内填写）

AI 客服依赖 DeepSeek 大模型。密钥**不写进任何文件**，由老板在网页内配置：

1. 用 boss 登录 → 进入「店铺设置」→ 底部「AI 客服设置」卡片
2. 填写 DeepSeek API Key（在 [DeepSeek 开放平台](https://platform.deepseek.com/) 申请）→ 保存密钥
3. 保存后**立即生效，无需重启**；密钥经 AES-256-GCM 加密存入数据库，
   任何文件、日志、接口返回中都只出现掩码（如 `sk-****2148`）
4. 「清除」后自动回退到 `backend-java\.env` 中的密钥（如有）；两者都未配置时，
   对话自动降级为 FAQ 关键词匹配 + 转人工，预约等功能不受影响

**首次使用流程**：用 boss 账号登录 → 完成开店向导 → 档期自动生成 → 在「店铺设置 → AI 客服设置」填好密钥 → 顾客端开始对话。
之后日常启停只需双击桌面「店小约」快捷方式，无需再敲命令。

手动方式（等价的一步步操作）：

```powershell
mysql -u root -p < database\sql\01_schema.sql       # 第一步：导入数据库（只需一次）
copy backend-java\.env.example backend-java\.env    # 第二步：准备配置（也可由启动向导自动生成）
# 编辑 .env: 填 DB_PASSWORD 和 JWT_SECRET(至少32字节随机串)
# DEEPSEEK_API_KEY 可留空 -> 对话降级为 FAQ 匹配 + 转人工
powershell -ExecutionPolicy Bypass -File .\scripts\start.ps1   # 第三步：启动
```

手动启动 / 生产部署 / 常见问题：见《docs/部署运行说明.md》。

## 目录结构

```text
shop-booking-agent/
├─ frontend/                 React 前端（顾客对话窗 + 老板/店员后台）
│  └─ src/pages/             11 个页面：对话、我的预约、看板、今日预约、
│                            转人工工单、档期日历、开店向导、店铺设置、
│                            服务项目、知识库、数据导出
├─ backend-java/             Spring Boot 后端
│  └─ src/main/java/com/shopbooking/
│     ├─ agent/              ★ Agent 循环 + 8 个工具（项目心脏）
│     ├─ controller/         12 组 REST 接口
│     ├─ service/            预约事务 / 档期生成 / 知识库 / 幂等 / 降级
│     ├─ security/           JWT 认证与注销黑名单
│     └─ ...
├─ database/sql/01_schema.sql   建库建表脚本（无业务数据）
├─ branding/                    品牌图标（shop-booking.ico + favicon）与生成脚本
├─ docs/                    项目介绍 / 代码技术架构 / 部署运行说明
├─ logs/                    运行日志：每次启动自动写入 backend.log、frontend.log 等，
│                           排查问题用；可随时整目录删除，不影响业务数据
├─ 启动-店小约.bat / 停止-店小约.bat / 体检-店小约.bat   双击入口
├─ runtime/                 仅当本机没有 JDK 17+/Node 18+ 时脚本自动下载的便携运行时；
│                           只放在本项目内、不写注册表，删除项目文件夹即彻底清除
└─ scripts/                     全部辅助脚本
   ├─ start.ps1                 启动（环境自检/便携运行时自举/首次配置向导）
   ├─ stop.ps1                  停止（释放端口，结束进程树）
   ├─ toggle.ps1                一键切换（未运行启动 / 已运行停止，桌面快捷方式指向这里）
   ├─ init-database.ps1         自动建库（执行 database\sql\ 下全部 SQL）
   ├─ check-env.ps1             环境体检（只读诊断）
   ├─ setup-runtime.ps1         下载便携 JDK 17 / Node 22 到 runtime\
   └─ verify-e2e.ps1            真实链路端到端验证脚本（直答/转人工/接管/恢复/防重）
```

## 测试

后端 10 个测试类共 41 个用例，覆盖 Agent 循环、人工接管（AI 暂停/店员回复/恢复接上下文/防重建单）、
并发抢座不超卖、幂等、锁座超时释放、AI 降级、API 安全契约、档期生成、开店向导、档期编辑安全：

```powershell
cd backend-java ; mvn clean test
cd frontend     ; npm ci ; npm run build
```

服务启动后可跑真实链路验证（会调用真实 DeepSeek）：

```powershell
powershell -ExecutionPolicy Bypass -File .\verify-e2e.ps1
```

## 文档索引

```text
docs/
├─ 项目介绍.md       产品定位、目标用户、功能清单（先读这个）
├─ 代码技术架构.md   架构决策、Agent 循环、并发/幂等设计、API 契约
└─ 部署运行说明.md   环境要求、手动部署、验证清单、常见问题
```
