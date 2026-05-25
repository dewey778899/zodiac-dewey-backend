# 星座生肖运势分析系统

基于 Java Spring Boot + 前端静态页面的星座/生肖运势分析项目。

## 🌟 功能特性

### 核心功能
- ✅ **星座兼容性计算** - 基于瑞士星历表的精确计算
- ✅ **AI 聊天服务** - 深度解析星座运势
- ✅ **支付系统集成** - 支持静态收款码 + 后台手动确认模式
- ✅ **管理后台** - 订单管理、数据分析、报告查询
- ✅ **前端页面** - 响应式设计，支持移动端访问

### 支付模式
当前采用 **静态收款码 + 手动确认** 模式：
- 零成本：无开通费、无手续费
- 钱直接到你的微信/支付宝
- 管理员在后台确认每笔订单

## 🚀 快速开始

### 后端启动

```bash
cd zodiac-dewey-backend
mvn clean package -DskipTests
java -jar target/zodiac-dewey.jar
```

服务启动在：`http://localhost:8080`

### 前端访问

直接访问后端提供的静态资源：
- 首页：`http://localhost:8080`
- 管理后台：`http://localhost:8080/admin.html`

### 手机访问

确保手机和电脑在同一 WiFi 下，访问：
```
http://你的电脑IP:8080
```

默认管理员账号：`dewey` / `dewey`

## 📁 项目结构

```
zodiac/
├── AGENTS.md              # AI 协作入口文件
├── ai-docs/               # AI 协作文档
│   ├── CURRENT_STATUS.md  # 当前项目状态
│   ├── AI_HANDOFF.md     # AI 交接记录
│   └── RECENT_FILES.md   # 最近修改文件
├── zodiac-dewey-backend/  # 后端服务
│   ├── src/main/java/
│   │   └── com/zodiac/api/
│   │       ├── controller/    # REST API 控制器
│   │       ├── service/       # 业务逻辑
│   │       ├── entity/        # 数据实体
│   │       ├── repository/    # 数据访问层
│   │       ├── util/          # 工具类
│   │       └── config/        # 配置类
│   ── src/main/resources/
│       ── application.yml    # 应用配置
└── zodiac-dewey-frontend/ # 前端页面
    ── frontend/
        ├── index.html         # 首页
        ├── admin.html         # 管理后台
        ├── assets/
        │   ├── scripts/       # JavaScript
        │   ── styles/        # CSS 样式
        ── img/               # 图片资源
            ├── alipay_qr.jpg  # 支付宝收款码
            └── wechat_qr.jpg  # 微信收款码
```

##  技术栈

### 后端
- Java 17
- Spring Boot 3.2.0
- Spring Data JPA
- H2 Database (内存数据库)
- Lombok
- Caffeine Cache

### 前端
- 原生 HTML/CSS/JavaScript
- 响应式设计
- 移动端适配

##  支付流程

```
用户填写信息 → 选择"深度解析" → 显示收款码
→ 用户扫码付款 (19.9 元) → 管理员后台确认 → 用户获得权限
```

### API 端点

| 接口 | 方法 | 说明 |
|-----|------|------|
| `/api/pay/create-manual` | POST | 创建手动订单 |
| `/api/pay/status/{订单号}` | GET | 查询订单状态 |
| `/api/admin/login` | POST | 管理员登录 |
| `/api/admin/orders` | GET | 订单列表 |
| `/api/admin/orders/{订单号}/confirm` | POST | 确认订单已支付 |

## 🤖 AI 协作系统

本项目使用 AI 协作系统，让多个 AI 工具能共享项目上下文。

### 指令说明

- `@read` - 读取 AI 协作文档（CURRENT_STATUS.md、AI_HANDOFF.md、RECENT_FILES.md）
- `@recent` - 快速查看最近修改的文件

### 文档说明

- `AGENTS.md` - AI 入口文件，定义协作指令
- `ai-docs/CURRENT_STATUS.md` - 当前项目状态
- `ai-docs/AI_HANDOFF.md` - AI 交接记录
- `ai-docs/RECENT_FILES.md` - 最近修改的文件列表

##  配置说明

### 环境变量

复制 `.env.example` 为 `.env` 并填入真实值：

```bash
DEEPSEEK_API_KEY=sk-your-deepseek-key
CLAUDE_API_KEY=sk-your-claude-key
ADMIN_PASSWORD=your-admin-password
```

### 收款码配置

将你的个人收款码放到：
- `zodiac-dewey-frontend/frontend/img/alipay_qr.jpg`
- `zodiac-dewey-frontend/frontend/img/wechat_qr.jpg`

## 🔐 安全说明

- 管理员账号密码请在 `.env` 文件中配置
- 切勿将 `.env` 文件提交到 Git
- 生产环境请配置 HTTPS

## 📄 许可证

MIT License

---

**开发团队**: 小登哥团队  
**最后更新**: 2026-05-25
