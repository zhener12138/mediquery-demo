# 医问通 (MediQuery) — 智慧医药查询系统

基于 SpringBoot 3 + Vue 3 的现代化智慧医药查询系统，集成阿里通义千问大模型作为智能医生。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | SpringBoot 3.4, MyBatis-Plus 3.5, Spring Security, JWT, Redis, MySQL 8.0 |
| 前端 | Vue 3, TypeScript, Vite, Element Plus, Pinia, ECharts |
| AI | 阿里通义千问 (DashScope SDK), SSE 流式输出 |
| 部署 | Docker, docker-compose, Nginx, GitHub Actions |

## 功能特性

### 公共功能
- 疾病百科 — 按科室分类浏览，全文搜索，病因症状详览
- 药品查询 — 药品搜索，功效用法禁忌查看，疾病关联推荐
- 找医生 — 医生卡片展示，按科室筛选
- 用户注册/登录 — JWT 无状态认证，邮箱验证码

### 用户功能
- 智能医生 — 通义千问 AI 对话，7x24 在线健康咨询
- 个人资料管理 — 修改资料、密码
- 意见反馈 — 提交系统建议

### 管理员功能
- 数据大屏 — ECharts 可视化统计面板
- 疾病管理 — CRUD，发布/编辑/删除
- 药品管理 — CRUD，药品类型分类，关联疾病
- 医生管理 — CRUD，科室/职称信息维护
- 反馈管理 — 查看反馈并回复
- 用户管理 — 启用/禁用账号

## 快速开始

### 环境要求
- Java 17+
- Maven 3.9+
- MySQL 8.0+
- Redis 7+
- Node.js 20+

### 1. 初始化数据库
```sql
CREATE DATABASE mediquery CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```
然后执行 `mediquery-backend/src/main/resources/db/init.sql`

### 2. 配置
修改 `mediquery-backend/src/main/resources/application.yml`:
- 数据库连接信息
- 阿里云通义千问 API Key
- 阿里云 OSS 配置
- 邮箱 SMTP 配置

### 3. 启动后端
```bash
cd mediquery-backend
mvn spring-boot:run
```
访问 http://localhost:8080/doc.html 查看 API 文档

### 4. 启动前端
```bash
cd mediquery-frontend
npm install
npm run dev
```
访问 http://localhost:5173

### 5. Docker 一键部署
```bash
docker-compose up -d
```

## 默认账户

| 角色 | 用户名 | 密码 |
|------|--------|------|
| 管理员 | admin | admin123 |
| 用户 | test | test123 |

## 项目结构

```
mediquery-backend/
├── src/main/java/com/mediquery/
│   ├── common/          # 通用组件 (DTO, 异常, 拦截器)
│   ├── config/          # 配置类 (Security, JWT, Knife4j, Redis)
│   ├── security/        # JWT 工具类 & 认证过滤器
│   ├── module/
│   │   ├── auth/        # 认证模块
│   │   ├── user/        # 用户模块
│   │   ├── illness/     # 疾病模块
│   │   ├── medicine/    # 药品模块
│   │   ├── ai-doctor/   # AI 医生模块
│   │   ├── doctor/      # 医生管理模块
│   │   ├── feedback/    # 反馈模块
│   │   └── dashboard/   # 数据大屏模块
│   └── integration/     # 第三方集成 (OSS, Email, AI)
└── src/main/resources/
    ├── application.yml
    └── db/init.sql

mediquery-frontend/
├── src/
│   ├── api/             # Axios 封装
│   ├── components/      # 公共组件
│   ├── pages/           # 页面
│   │   ├── public/      # 公开页面
│   │   ├── user/        # 用户页面
│   │   └── admin/       # 管理后台
│   ├── router/          # Vue Router
│   └── stores/          # Pinia 状态管理
└── Dockerfile
```

## License

MIT License
