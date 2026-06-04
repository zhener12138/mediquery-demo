<h1 align="center">🏥 医问通 MediQuery</h1>
<p align="center"><strong>智慧医药查询系统 — Spring Boot 3 + Vue 3 全栈项目</strong></p>
<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring_Boot-3.4-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.4">
  <img src="https://img.shields.io/badge/Vue-3.5-4FC08D?logo=vuedotjs&logoColor=white" alt="Vue 3.5">
  <img src="https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white" alt="MySQL 8.0">
  <img src="https://img.shields.io/badge/Redis-7.0-DC382D?logo=redis&logoColor=white" alt="Redis 7">
  <img src="https://img.shields.io/badge/Docker-✓-2496ED?logo=docker&logoColor=white" alt="Docker">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="License">
</p>

<p align="center">
  <a href="#-功能概览">功能概览</a> •
  <a href="#-技术栈">技术栈</a> •
  <a href="#-快速启动">快速启动</a> •
  <a href="#-项目结构">项目结构</a> •
  <a href="#-接口文档">接口文档</a>
</p>

---

## 📋 功能概览

### 🌐 公共模块
| 功能 | 说明 |
|------|------|
| 疾病百科 | 按科室分类浏览，支持全文搜索（MySQL Fulltext + ngram），病因、症状、特殊症状详览 |
| 药品查询 | 药品模糊搜索，功效 / 用法 / 禁忌查看，疾病关联推荐 |
| 找医生 | 医生卡片展示，按科室筛选，详情页面 |
| 用户认证 | 注册 / 登录，JWT 双 Token 无状态认证（Access + Refresh），邮箱验证码 |

### 👤 用户模块
| 功能 | 说明 |
|------|------|
| 🤖 AI 智能医生 | 集成阿里通义千问 (DashScope)，**SSE 流式输出**，7×24 在线健康咨询 |
| 👤 个人中心 | 修改资料、头像上传（阿里云 OSS）、修改密码 |
| 📝 意见反馈 | 提交系统建议 & 问题反馈 |

### 🛡️ 管理员后台
| 功能 | 说明 |
|------|------|
| 📊 数据大屏 | ECharts 可视化统计（用户增长、疾病分布、访问趋势） |
| 🏥 疾病管理 | CRUD、发布 / 草稿 / 删除、分类管理 |
| 💊 药品管理 | CRUD、药品类型分类、疾病关联 |
| 👨‍⚕️ 医生管理 | CRUD、科室 / 职称维护 |
| 📮 反馈管理 | 查看用户反馈并回复 |
| 👥 用户管理 | 启用 / 禁用账号、角色管理 |

---

## 🛠 技术栈

| 层级 | 技术选型 |
|------|----------|
| **后端框架** | Spring Boot 3.4, Spring Security, Spring Data Redis, Spring Mail |
| **持久层** | MyBatis-Plus 3.5, MySQL 8.0, Druid 连接池 |
| **认证** | JWT (jjwt 0.12), BCrypt 密码加密, 双 Token 刷新机制 |
| **API 文档** | Knife4j (OpenAPI 3.0), 中文界面 |
| **工具库** | Hutool 5.8, Lombok, EasyExcel 3.3 |
| **AI 集成** | 阿里通义千问 (DashScope SDK), SSE 流式对话 |
| **存储** | 阿里云 OSS（头像上传） |
| **前端框架** | Vue 3 (Composition API), TypeScript, Vite 6 |
| **UI 组件** | Element Plus 2.9, @element-plus/icons-vue |
| **状态管理** | Pinia 2.3 |
| **图表** | ECharts 5.5 |
| **部署** | Docker, docker-compose, Nginx 反向代理 |

### 架构图

```
┌─────────────────────────────────────────────────────┐
│                     Browser                          │
│              localhost:80 (Nginx)                    │
└─────────────────┬───────────────────────────────────┘
                  │
    ┌─────────────┴──────────────┐
    │                            │
    ▼                            ▼
┌─────────┐              ┌──────────────┐
│  Vue 3  │── REST API ─▶│ Spring Boot 3│
│  Front  │              │   Backend    │
│  :80    │◀── JSON ─── │   :8088      │
└─────────┘              └──┬───┬───┬──┘
                            │   │   │
              ┌─────────────┘   │   └─────────────┐
              ▼                 ▼                 ▼
        ┌──────────┐    ┌──────────┐    ┌──────────────┐
        │  MySQL   │    │  Redis   │    │  External API │
        │  :3307   │    │  :6380   │    │ DashScope/OSS │
        └──────────┘    └──────────┘    └──────────────┘
```

---

## 🚀 快速启动

### 前置环境

- Java 21+
- Maven 3.9+
- MySQL 8.0+
- Redis 7+
- Node.js 20+
- Docker & docker-compose（可选，用于一键部署）

### 方式一：Docker 一键部署（推荐）

```bash
# 1. 克隆项目
git clone https://github.com/YOUR_USERNAME/mediquery-demo.git
cd mediquery-demo

# 2. 配置密钥（非常重要！）
cp .env.example .env
# 编辑 .env 文件，填入你的 MySQL 密码、邮箱授权码、DashScope API Key、OSS AccessKey 等
# 每一项都有注释说明去哪里获取

# 3. 启动
docker-compose up -d

# 4. 访问
# 前端: http://localhost
# 后端 API 文档: http://localhost:8088/doc.html
```

### 方式二：本地开发

```bash
# 1. 克隆 & 配置密钥（同上）
git clone https://github.com/YOUR_USERNAME/mediquery-demo.git
cd mediquery-demo
cp .env.example .env
# 编辑 .env

# 2. 导入数据库
# 执行 backend/src/main/resources/db/init.sql

# 3. 启动后端
cd backend
mvn spring-boot:run
# API 文档: http://localhost:8088/doc.html

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

> ⚠️ **`.env` 文件已加入 `.gitignore`，不会被提交。** `.env.example` 作为模板可以安全提交。

---

## 📂 项目结构

```
mediquery-demo/
├── backend/                         # Spring Boot 后端
│   ├── src/main/java/com/mediquery/
│   │   ├── MediQueryApplication.java
│   │   ├── common/                  # 通用组件（DTO, 全局异常处理, 拦截器）
│   │   ├── config/                  # 配置（Security, JWT, Knife4j, Redis, CORS）
│   │   ├── security/                # JWT 工具类 & Spring Security 过滤器
│   │   ├── module/
│   │   │   ├── auth/                # 认证模块（注册/登录/验证码）
│   │   │   ├── user/                # 用户模块（资料/密码）
│   │   │   ├── illness/             # 疾病模块（分类/搜索/CRUD）
│   │   │   ├── medicine/            # 药品模块（搜索/关联/CRUD）
│   │   │   ├── doctor/              # 医生模块（科室/职称/CRUD）
│   │   │   ├── ai_doctor/           # AI 医生（通义千问对话，SSE 流式）
│   │   │   ├── feedback/            # 反馈模块
│   │   │   └── dashboard/           # 数据大屏（统计接口）
│   │   └── integration/             # 第三方集成
│   │       ├── ai/DashScopeClient.java      # 通义千问 SDK 封装
│   │       ├── email/EmailClient.java       # 邮件发送
│   │       └── oss/OssClient.java           # 阿里云 OSS 上传
│   └── src/main/resources/
│       ├── application.yml          # 主配置（密钥通过环境变量注入）
│       └── db/init.sql              # 数据库初始化脚本
│
├── frontend/                        # Vue 3 前端
│   ├── src/
│   │   ├── api/                     # Axios 封装 & 接口层
│   │   ├── components/layout/       # 布局组件
│   │   ├── pages/
│   │   │   ├── public/              # 公开页面（首页/搜索/疾病/药品/医生/登录/注册）
│   │   │   ├── user/                # 用户页面（AI医生/个人中心/反馈）
│   │   │   └── admin/               # 管理后台（数据大屏/疾病/药品/医生/用户/反馈管理）
│   │   ├── router/                  # Vue Router 路由配置
│   │   └── stores/                  # Pinia 状态管理（auth）
│   └── nginx.conf                   # Nginx 生产环境配置
│
├── docker-compose.yml               # Docker 一键编排
├── .env.example                     # 环境变量模板
├── .gitignore
└── LICENSE
```

---

## 📖 接口文档

启动后端后访问：**http://localhost:8088/doc.html**

Knife4j 提供了中文界面的 Swagger UI，可直接在线调试 API。

---

## 📝 默认账户

| 角色 | 用户名 | 密码 |
|------|--------|------|
| 管理员 | admin | admin123 |
| 普通用户 | test | test123 |

---

## 📄 License

[MIT](./LICENSE) © 2026
