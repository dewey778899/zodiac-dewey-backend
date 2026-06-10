# Zodiac Dewey Backend

占星报告、深度解析支付、返现账户与运营后台的统一后端服务。

## 当前状态

当前仓库已经不再使用 H2，默认数据库为 SQLite 文件数据库。

- 默认数据库：`SQLite`
- 默认数据库文件：`./data/zodiac_dewey.db`
- 默认后端端口：`8080`
- 默认后台账号：`dewey`
- 默认后台密码：`dewey`

核心能力：

- 爱情 / 事业 / 财运三主题报告生成
- 免费版与深度解析版双链路
- 微信 / 支付宝 / 抖音支付订单能力
- 报告分享查询
- 深度解析解锁记录
- 返现账户、邀请绑定、返现记录、提现审核
- 管理后台 API

## 目录结构

```text
zodiac-dewey-backend/
├─ src/main/java/com/zodiac/api/
│  ├─ config/         # 配置
│  ├─ controller/     # API 控制器
│  ├─ dto/            # 请求响应 DTO
│  ├─ entity/         # JPA 实体
│  ├─ exception/      # 异常
│  ├─ repository/     # 数据访问层
│  ├─ service/        # 业务服务
│  └─ util/           # 工具类
├─ src/main/resources/
│  ├─ prompts/        # 各主题 prompt 资源
│  ├─ application.yml # 主配置
│  ├─ schema.sql      # 初始化表结构
│  └─ birth-place-coordinates.json
├─ data/              # SQLite 数据目录
├─ target/
├─ pom.xml
└─ README.md
```

## 技术栈

- Java 17
- Spring Boot 3.2.0
- Spring Data JPA
- SQLite
- Hibernate Community Dialects
- Caffeine Cache
- Lombok

## 数据库说明

默认配置见：

- [src/main/resources/application.yml](./src/main/resources/application.yml)

当前默认配置：

```yaml
spring:
  datasource:
    url: jdbc:sqlite:./data/zodiac_dewey.db
    driver-class-name: org.sqlite.JDBC
```

说明：

- 这是文件数据库，不是内存数据库
- 服务重启后数据会保留
- 报告、订单、解锁、返现、提现后台数据都使用同一个 SQLite 文件库

## 本地启动

### 1. 打包

```bash
mvn clean package -DskipTests
```

### 2. 启动

```bash
java -jar target/zodiac-dewey.jar
```

启动后访问：

- 健康检查：`http://127.0.0.1:8080/api/health`
- 后台登录接口：`http://127.0.0.1:8080/api/admin/login`

## 环境变量

常用环境变量：

```bash
SERVER_PORT=8080
DB_URL=jdbc:sqlite:./data/zodiac_dewey.db
DB_DRIVER=org.sqlite.JDBC

AI_API_KEY=your-deepseek-key
AI_API_URL=https://api.deepseek.com/chat/completions
AI_MODEL=deepseek-chat

CLAUDE_API_KEY=your-claude-key
CLAUDE_API_URL=https://api.anthropic.com/v1/messages
CLAUDE_MODEL=claude-sonnet-4-6

ADMIN_USERNAME=dewey
ADMIN_PASSWORD=dewey
ADMIN_SESSION_HOURS=12
```

建议复制：

- [.env.example](./.env.example)

然后按你的环境单独配置，不要把真实密钥提交到 Git。

## 主要接口

### 报告与分享

- `POST /api/compatibility`
- `GET /api/compatibility/report/{uid}`

### 深度解析与支付

- `POST /api/pay/create-order`
- `GET /api/pay/status/{outTradeNo}`
- `POST /api/premium/douyin-unlock`

### 返现账户

- `POST /api/referral/bind`
- `POST /api/referral/visit`
- `GET /api/referral/me`
- `GET /api/referral/summary`
- `GET /api/referral/records`
- `POST /api/referral/withdrawals`

### 后台管理

- `POST /api/admin/login`
- `GET /api/admin/overview`
- `GET /api/admin/reports`
- `GET /api/admin/orders`
- `GET /api/admin/premium-unlocks`
- `GET /api/admin/referral/users`
- `GET /api/admin/referral/bindings`
- `GET /api/admin/referral/rewards`
- `GET /api/admin/referral/withdrawals`

## 关联前端

当前项目关联两个前端：

- Web/H5：`D:/codex/zodiac/zodiac-dewey-frontend`
- 小程序：`D:/codex/zodiac/zodiac-dewey-miniapp`

说明：

- H5 后台页地址通常是 `http://127.0.0.1:3000/admin.html`
- H5 主站与小程序都复用当前后端 API

## 注意事项

- 当前默认数据库是 SQLite，不再使用 H2
- 本地如需重新打包，请先停止占用中的 `target/zodiac-dewey.jar`
- 生产环境建议单独配置：
  - `ADMIN_USERNAME`
  - `ADMIN_PASSWORD`
  - `AI_API_KEY`
  - `CLAUDE_API_KEY`
  - `CORS_ALLOWED_ORIGINS`

## License

MIT
