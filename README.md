<h1 align="center">🩺 医问通 MediQuery</h1>
<p align="center"><strong>一个只做在线问诊的 AI 分诊机器人 — Spring Boot 3 + Vue 3</strong></p>
<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring_Boot-3.4-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.4">
  <img src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white" alt="Vue 3.5">
  <img src="https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white" alt="MySQL 8.0">
  <img src="https://img.shields.io/badge/Redis-7.0-DC382D?logo=redis&logoColor=white" alt="Redis 7">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="License">
</p>

<p align="center">
  <a href="#-它解决什么">它解决什么</a> •
  <a href="#-问诊流程">问诊流程</a> •
  <a href="#-技术栈">技术栈</a> •
  <a href="#-快速启动">快速启动</a> •
  <a href="#-项目结构">项目结构</a>
</p>

---

## 🎯 它解决什么

去医院之前那段最难熬的时间：症状刚出现，不知道该不该看、该挂哪个科、能不能先观察两天。

这个项目把这段路收敛成一件事：**你把症状说清楚，AI 追问关键细节，最后给你一份结构化小结。**

小结里包含：主诉、病程、症状清单、可能的方向、建议科室及理由、居家护理建议、用药提示、危险信号、严重程度，以及一条不可移除的免责声明。

### 边界

| 做 | 不做 |
|----|------|
| 多轮追问，把模糊症状收敛成清晰问题 | 不下诊断结论 |
| 危险信号优先识别，第一句话就提示就医 | 不给具体剂量、疗程、处方 |
| 给出方向性判断与建议科室 | 替代医生面诊 |
| 生成可回看的结构化小结 | 人工接诊、视频、支付、药品库存 |

---

## 🔄 问诊流程

```
发起问诊（一句话主诉）
      │
      ▼
异步分诊（纯内存规则，微秒级）
      │  ├─ 命中危险信号 → SSE 第一帧就带红色横幅，先于 AI 第一个字
      │  └─ 命中症状词   → 记录建议科室
      ▼
SSE 多轮对话（逐字流式）
      │  ├─ RAG 召回：症状图谱两跳（症状 → 疾病 → 科室），未命中退回全文索引
      │  ├─ 打分：症状权重 × IDF，患者明确否认的症状一分不得
      │  ├─ 追问：按鉴别力挑 1-2 个最能分开候选的点，让模型用自己的话问
      │  └─ 历史上下文 + 会话上下文缓存（Redis，24 小时）
      ▼
完结问诊
      │
      ▼
异步生成小结（独立线程池，不占前台请求）
      │  └─ 失败可重试，成功即终态（医疗记录不被反复覆盖）
      ▼
结构化小结（12 个字段 + 固定免责声明）
```

**30 分钟无活动自动关闭**，由定时任务扫描，单条独立事务——一条脏数据不会卡死整批。

---

## 🛠 技术栈

| 层级 | 技术选型 |
|------|----------|
| **后端框架** | Spring Boot 3.4, Spring Security, Spring Data Redis, Spring Mail |
| **持久层** | MyBatis-Plus 3.5, MySQL 8.0, Druid 连接池 |
| **认证** | JWT (jjwt 0.12), BCrypt, 双 Token 刷新机制 |
| **AI 集成** | 阿里通义千问 (DashScope SDK), SSE 流式对话, Redis 会话上下文 |
| **知识召回** | MySQL FULLTEXT + ngram 分词器，双策略实现（全文索引 / LIKE 回落） |
| **API 文档** | SpringDoc OpenAPI (Swagger UI) |
| **前端框架** | Vue 3 (Composition API), TypeScript, Vite 6 |
| **UI 组件** | Element Plus 2.9 |
| **状态管理** | Pinia 2.3 |
| **存储** | 阿里云 OSS（头像上传） |
| **部署** | Docker, docker-compose, Nginx 反向代理, 多阶段构建 |

### 关键设计

- **双线程池隔离**：SSE 对话池（8/32）与小结生成池（2/4）分开。小结调用 AI 要 30 秒，放同一个池会被后台任务拖死前台对话。
- **小结不加事务**：`SummaryService` 类上明确禁止 `@Transactional`——AI 调用 30 秒会占死 Druid 的连接（max-active 20），把整个应用拖垮。
- **分诊在存储之前**：危险信号判定放在写消息之前，才能保证 SSE 第一帧就带横幅，而不是等 AI 说完才补。
- **severity 宁低勿高**：脏数据宁可判低不可判高，绝不发出假告警。
- **免责声明后端强制覆盖**：不信任模型输出的免责声明，`SummaryVO` 里写死并覆盖。
- **增量输出必须开**：`GenerationResult.incrementalOutput(true)`。不设会返回累积文本，实测 37113 字 vs 681 字，差 54 倍。

---

## 🚀 快速启动

### 前置环境

- Java 21+
- Maven 3.9+
- MySQL 8.0+
- Redis 7+
- Node.js 20+
- Docker & docker-compose（可选）

### 方式一：Docker 一键部署

```bash
git clone https://github.com/zhener12138/mediquery-demo.git
cd mediquery-demo

# 配置密钥
cp .env.example .env
# 编辑 .env：MySQL 密码、邮箱授权码、DashScope API Key、OSS AccessKey
# spring-dotenv 会自动加载，无需手动 export

docker-compose up -d

# 前端: http://localhost
# API 文档: http://localhost:8088/swagger-ui.html
```

### 方式二：本地开发

```bash
# 1. 配置密钥
cp .env.example .env

# 2. 建库（Flyway 会在启动时自动建表，这里只需要库本身存在）
mysql -h127.0.0.1 -P3306 -uroot -p -e "CREATE DATABASE IF NOT EXISTS mediquery \
  DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;"

# 2.1 可选：灌种子数据（10 条疾病 / 7 条药品 / 两个测试账户）
# 不给也能跑，只是 AI 没有知识库可召回、首页看不出东西
mysql -h127.0.0.1 -P3306 -uroot -p mediquery < backend/src/main/resources/db/seed.sql

# 2.2 可选：灌症状图谱（53 症状 / 144 同义词 / 61 疾病-症状边 / 14 科室边）
# 这一步才是「拉不出来，蹲半天」能召回到便秘的原因；不灌就只能走老的全文索引
mysql -h127.0.0.1 -P3306 -uroot -p mediquery < backend/src/main/resources/db/seed-knowledge.sql

# 3. 启动后端
cd backend
mvn spring-boot:run
# API 文档: http://localhost:8080/swagger-ui.html

# 4. 启动前端（新终端）
cd frontend
npm install
npm run dev
# 前端页面: http://localhost:5173
```

### 🔑 密钥配置说明

| 配置项 | 说明 | 获取方式 |
|--------|------|----------|
| `JWT_SECRET` | JWT 签名密钥 | `openssl rand -base64 64` 生成 |
| `AI_DASHSCOPE_API_KEY` | 通义千问 API Key | [DashScope 控制台](https://dashscope.console.aliyun.com/apiKey) |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | 邮箱 & SMTP 授权码 | QQ邮箱 → 设置 → 账户 → POP3/SMTP → 开启 |
| `ALIYUN_OSS_*` | 阿里云 OSS 配置 | [RAM 访问控制](https://ram.console.aliyun.com/manage/ak) |

> ⚠️ **`.env` 已加入 `.gitignore`，不会被提交。** `.env.example` 作为模板可以安全提交。

---

## 📂 项目结构

```
mediquery-demo/
├── backend/                          # Spring Boot 后端
│   ├── src/main/java/com/mediquery/
│   │   ├── MediQueryApplication.java
│   │   ├── common/                   # 通用组件（Result, PageResult, 全局异常, TraceId）
│   │   ├── config/                   # Security, JWT, OpenAPI, Redis, CORS, 线程池
│   │   ├── security/                 # JWT 工具类 & 认证过滤器
│   │   ├── module/
│   │   │   ├── auth/                 # 注册 / 登录 / 验证码
│   │   │   ├── user/                 # 个人资料 / 密码 / 头像
│   │   │   ├── consultation/         # ★ 问诊核心
│   │   │   │   ├── triage/           # 异步分诊规则引擎
│   │   │   │   ├── statemachine/     # 状态机（进行中 / 完成 / 取消 / 超时）
│   │   │   │   ├── service/          # 对话流、小结生成
│   │   │   │   ├── job/              # 超时关闭扫描
│   │   │   │   ├── security/         # 归属校验（@consultGuard）
│   │   │   │   └── vo/               # 问诊单 / 小结
│   │   │   ├── illness/              # 疾病知识底座（RAG 召回用，无对外页面）
│   │   │   ├── knowledge/            # 症状图谱：同义词归一 / 疾病-症状边 / 科室优先级
│   │   │   ├── medicine/             # 药品知识底座（同上）
│   │   │   ├── stats/                # 公开统计（首页用）
│   │   │   └── operationlog/         # 审计日志
│   │   └── integration/
│   │       ├── ai/                   # DashScope 封装 + 知识召回 + 双检索策略
│   │       ├── email/                # 邮件发送
│   │       └── oss/                  # 阿里云 OSS
│   └── src/main/resources/
│       ├── application.yml
│       └── db/
│           ├── migration/            # Flyway 迁移（V1 基线 / V2 信息卡 / V3 清遗留 / V4 症状图谱）
│           ├── seed.sql              # 种子数据（10 疾病 / 7 药品 / 2 账户）
│           └── seed-knowledge.sql    # 症状图谱数据（53 症状 / 144 同义词 / 61 症状边 / 14 科室边）
│
├── frontend/                         # Vue 3 前端
│   ├── src/
│   │   ├── api/                      # Axios 封装（401 单飞续期）
│   │   ├── components/layout/        # AppHeader / AppFooter
│   │   ├── pages/
│   │   │   ├── public/               # 首页 / 登录 / 注册 / 404
│   │   │   └── user/                 # 问诊页 / 个人中心
│   │   ├── router/                   # 6 条路由
│   │   └── stores/                   # Pinia（auth）
│   └── nginx.conf
│
├── docker-compose.yml
├── .env.example
└── LICENSE
```

> **知识库退到后端**：疾病库和药品库不再是用户能「逛」的页面，它们变成 RAG 的召回底座。用户不需要知道有这两张表。
>
> **症状是独立的实体**：`symptom` + `symptom_alias` 把「拉不出来 / 蹲半天 / 排便费劲」归一到「排便费力」，再经 `disease_symptom` 走两跳到疾病，最后按 `disease_department.priority` 出科室。老的全文索引作为兜底保留——图谱没命中时才走它。

---

## 📖 接口文档

启动后端后访问 **http://localhost:8080/swagger-ui.html**。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/login` | 登录 |
| POST | `/api/v1/auth/register` | 注册 |
| POST | `/api/v1/auth/refresh` | 刷新 Token |
| POST | `/api/v1/consultations` | 发起问诊 |
| GET | `/api/v1/consultations` | 我的问诊列表 |
| GET | `/api/v1/consultations/{id}` | 问诊详情（含小结） |
| POST | `/api/v1/consultations/{id}/messages/stream` | **SSE 流式对话** |
| GET | `/api/v1/consultations/{id}/messages` | 历史消息 |
| PUT | `/api/v1/consultations/{id}/complete` | 完结并生成小结 |
| PUT | `/api/v1/consultations/{id}/cancel` | 取消问诊 |
| PUT | `/api/v1/consultations/{id}/summary/retry` | 重试小结生成 |
| DELETE | `/api/v1/consultations/{id}` | 删除问诊单 |
| GET | `/api/v1/stats` | 公开统计 |
| GET | `/api/v1/user/me` | 当前用户 |
| PUT | `/api/v1/user/profile` | 修改资料 |
| PUT | `/api/v1/user/password` | 修改密码 |
| POST | `/api/v1/files/upload` | 上传头像 |

### SSE 事件流

`POST /consultations/{id}/messages/stream` 返回的每一帧都是 `event: delta`：

| type | 时机 | 载荷 |
|------|------|------|
| `start` | 立刻，AI 吐第一个字之前 | `triageSeverity` / `redFlags` / `suggestDept` / `advice` / `sources`（本次召回的病名） |
| `chunk` | 流式过程中 | `chunk`（增量文本） |
| `meta` | 回复结束 | `triageSeverity` / `suggestDept`（`severity` 恒为 null，严重度由小结回顾性判断） |
| `end` | 完全结束 | `summarizing` |
| `error` | 异常 | `message` / `code` / `retryable` |

---

## 🎯 检索质量怎么衡量

知识库这条链路不是"接了个 RAG 就完了"，它有一套可复现的尺子：

| 指标 | 当前值 | 说明 |
|---|---|---|
| Recall@3 | 100.0%（30/30） | Top3 里出现正确疾病的用例占比 |
| MRR | 0.983 | 第一个正确疾病排名的倒数均值 |
| 科室一致率 | 100.0%（30/30） | 榜首疾病的建议科室与期望一致 |
| 红旗召回 | 7/7 | 危险信号必须**全部**命中，漏一个算失败 |
| 负例误报 | 0/5 | 知识库没有的病，不许硬凑 |

评测集在 `backend/src/test/resources/eval/retrieval-cases.json`（35 条，含 5 条负例），
跑 `mvn test -Dtest=RetrievalEvalTest` 后看 `backend/target/retrieval-eval.txt`。

**为什么要有这个文件**：没有它，任何"我优化了检索"的说法都无法验证。第 20 章那个
"打对折的否定机制其实是空实现"就是靠控制实验抓出来的 —— 指标涨了 0.7 个百分点，
把系数改回 1.0 一跑，一模一样。

**这些数字是怎么来的**：93.3% → 100.0% 不是靠"调参调出来的"，是两次控制实验分离出来的——
补同义词别名贡献 1 条（c18），归一化容忍虚词夹字再贡献 1 条（c08），两个都要有才到 100%。
关掉任一个，指标立刻掉回 96.7% / 93.3%。详见设计文档第 21 章。

```bash
# 想自己验证「虚词夹字」到底值多少，把下面这个开关打开重跑评测
# （改 SymptomNormalizer 加一行 return; 到 matchLoose 开头，然后对比 EVAL 行）
```

---

## 📝 默认账户

| 角色 | 用户名 | 密码 |
|------|--------|------|
| 管理员 | admin | admin123 |
| 普通用户 | test | test123 |

---

## 📄 License

[MIT](./LICENSE) © 2026
