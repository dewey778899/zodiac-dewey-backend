# 小登哥灵魂合盘 - 后端 API

这是一个基于 `Spring Boot 3` 和 `Java 17` 的后端服务，负责：

- 合盘 / 事业 / 财运报告接口
- AI 模型调用与降级
- 支付、埋点、后台管理接口
- SQLite 本地数据存储

## 目录结构

```text
zodiac-dewey-backend/
|-- src/main/java              后端源码
|-- src/main/resources         配置与静态资源
|-- src/test/java              测试代码
|-- data/                      本地数据库目录
|-- logs/                      本地运行日志目录
|-- docs/                      接口与部署文档
|-- Dockerfile
|-- docker-compose.yml
`-- pom.xml
```

## 本地运行

先复制环境变量模板：

```bash
cp .env.example .env
```

然后启动：

```bash
mvn spring-boot:run
```

默认端口：

- 应用端口：`8080`
- 管理端口：`59999`

默认路径：

- 日志：`./logs/backend.log`
- 数据库：`./data/zodiac_dewey.db`

## Docker 镜像

- `registry.cn-shanghai.aliyuncs.com/dewey_zodiac/zodiac-dewey-backend:latest`

## 相关文档

- [API.md](./docs/API.md)
- [DEPLOY.md](./docs/DEPLOY.md)
- [NGINX.md](./docs/NGINX.md)
