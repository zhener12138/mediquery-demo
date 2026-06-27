# 智慧医药系统 (Smart Medicine System)

基于 FastAPI + LangChain 的智能寻药就医系统，提供疾病查询、药品查询、AI 智能问诊等功能。

## 功能概览

- **AI 智能医生**：基于通义千问 (Qwen) 的医疗咨询对话，支持流式 SSE 响应
- **医疗安全护栏**：LangGraph 驱动的意图分类，自动拒绝非医疗问题
- **疾病百科**：疾病分类浏览、全文搜索、病因/症状/治疗方案展示
- **药品查询**：药品关键字搜索、药品详情（功效/禁忌/用法/价格）、疾病-药品关联
- **用户系统**：注册/登录（Session 认证）、邮箱验证码、个人中心
- **搜索历史**：记录用户搜索行为，支持历史回看
- **用户反馈**：提交意见反馈
- **管理后台**：疾病/药品/反馈的增删改查
- **文件上传**：基于阿里云 OSS 的图片上传

## 技术栈

| 层级 | 技术 |
|------|------|
| Web 框架 | FastAPI 0.115 |
| 异步服务器 | Uvicorn |
| 模板引擎 | Jinja2 |
| ORM | SQLAlchemy 2.0 |
| 数据库 | MySQL (PyMySQL) |
| AI 引擎 | LangChain + LangGraph + DashScope (通义千问) |
| 邮件 | aiosmtplib (QQ 邮箱) |
| 对象存储 | 阿里云 OSS (oss2) |
| 密码哈希 | bcrypt + passlib |
| 配置管理 | pydantic-settings |

## 项目结构

```
mediquery-demo/
├── app/
│   ├── main.py               # FastAPI 应用入口
│   ├── config.py             # 配置管理 (pydantic-settings)
│   ├── database.py           # SQLAlchemy 引擎 & Session
│   ├── dependencies.py       # 认证依赖 (get_current_user / get_current_admin)
│   ├── ai/
│   │   ├── llm.py            # LLM 构建 (ChatTongyi)
│   │   ├── graph.py          # LangGraph 医疗对话图 (分类 → 生成)
│   │   ├── prompts.py        # Prompt 模板 & 护栏提示词
│   │   └── memory.py         # 对话历史管理
│   ├── models/               # SQLAlchemy 数据模型
│   ├── schemas/              # Pydantic 响应 Schema
│   ├── routers/              # API 路由 & 页面路由
│   ├── services/             # 业务逻辑层 (通用 CRUD BaseService)
│   └── utils/                # 工具函数
├── templates/                # Jinja2 前端模板
├── static/                   # 静态资源 (CSS/JS/Fonts/Images)
├── requirements.txt          # Python 依赖
└── .env.example              # 环境变量模板
```

## 快速开始

### 1. 环境要求

- Python 3.10+
- MySQL 8.0+
- 阿里云 OSS Bucket（用于文件上传）
- 通义千问 API Key（[DashScope 控制台](https://dashscope.console.aliyun.com/) 免费获取）

### 2. 安装依赖

```bash
pip install -r requirements.txt
```

### 3. 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env` 填入你的真实配置：

```ini
# Database
DB_HOST=127.0.0.1
DB_PORT=3306
DB_USER=root
DB_PASSWORD=your_db_password
DB_NAME=smart-medicine

# AI (DashScope)
AI_KEY=sk-your-dashscope-api-key

# Email (用于发送验证码)
EMAIL_SENDER=your-email@qq.com
EMAIL_PASSWORD=your-smtp-auth-code
EMAIL_HOST=smtp.qq.com
EMAIL_PORT=465

# Alibaba Cloud OSS
OSS_BUCKET_NAME=your-bucket-name
OSS_END_POINT=oss-cn-beijing.aliyuncs.com
OSS_ACCESS_KEY=your-oss-access-key
OSS_ACCESS_SECRET=your-oss-access-secret

# App
SECRET_KEY=your-random-secret-string
DEBUG=false
```

### 4. 初始化数据库

在 MySQL 中创建数据库后，启动应用时 SQLAlchemy 会自动建表。

### 5. 启动服务

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

访问 http://localhost:8000

## AI 对话架构

```
用户消息 → LangGraph StateGraph
              ├─ classify_medical (Qwen 分类器: YES/NO)
              ├─ YES → generate_response (Qwen 医生回答)
              └─ NO  → handle_non_medical (礼貌拒绝)
```

- **护栏**：非医疗问题直接拒绝，只回答健康/疾病/药物/症状相关问题
- **流式输出**：`/api/chat/stream` 通过 SSE (Server-Sent Events) 逐 token 返回
- **上下文记忆**：基于 `session_id` 的对话历史管理，保留最近 10 轮对话

## API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/login/register` | 用户注册（邮箱验证码） |
| POST | `/api/login/login` | 用户登录 |
| POST | `/api/login/send-email-code` | 发送邮箱验证码 |
| POST | `/api/chat/query` | AI 医生对话（同步） |
| POST | `/api/chat/stream` | AI 医生对话（SSE 流式） |
| GET | `/illnesses` | 疾病搜索 & 分类浏览 |
| GET | `/illness/{id}` | 疾病详情 + 关联药品 |
| GET | `/medicines` | 药品搜索 |
| GET | `/medicine/{id}` | 药品详情 |
| GET | `/global-select` | 全局多关键词搜索 |
| POST | `/api/feedback/save` | 提交反馈 |
| POST | `/api/file/upload` | 上传图片到 OSS |
| GET | `/admin/*` | 管理后台页面 |

## 安全注意事项

### 密钥轮换

如果你曾将包含真实凭证的 `.env` 文件推送到了远程仓库（包括已删除的提交），请立即前往以下控制台轮换密钥：

| 服务 | 控制台 | 需轮换的凭证 |
|------|--------|-------------|
| DashScope (通义千问) | [dashscope.console.aliyun.com](https://dashscope.console.aliyun.com) | API Key |
| 阿里云 RAM (OSS) | [ram.console.aliyun.com](https://ram.console.aliyun.com) | AccessKey + AccessSecret |
| QQ 邮箱 SMTP | [mail.qq.com](https://mail.qq.com) → 设置 → 账户 → POP3/SMTP | 授权码 |

### 密码存储

用户密码使用 **bcrypt** 哈希存储，不可逆。即使数据库泄露，攻击者也无法获取明文密码。

### JWT 认证

- 管理 API (疾病/药品/反馈/文件 的增删改) 需要管理员 JWT token
- Token 有效期 24 小时，登录/注册时签发
- 前端通过 `Authorization: Bearer <token>` 头传递

## 部署注意事项

1. `.env` 文件**切勿提交到 Git**，已加入 `.gitignore`
2. 生产环境请将 `DEBUG` 设为 `false`，并设置强随机 `SECRET_KEY` 和 `JWT_SECRET`
3. 邮件密码建议使用 QQ 邮箱的 SMTP 授权码（非登录密码）
4. OSS Bucket 建议设置访问权限和防盗链
5. DashScope API Key 可在[阿里云灵积控制台](https://dashscope.console.aliyun.com/)免费申请

## License

MIT
