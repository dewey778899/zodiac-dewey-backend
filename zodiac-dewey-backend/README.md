# 小登哥灵魂合盘 - 后端 API

Spring Boot + Java 17 后端服务，提供 AI 星座合盘 API。

## Docker 镜像

- **ACR**: `registry.cn-shanghai.aliyuncs.com/dewey_zodiac/zodiac-dewey-backend:latest`

## 配置

复制 `.env.example` 为 `.env` 并填写配置：

```bash
cp .env.example .env
```

## 部署

```bash
docker pull registry.cn-shanghai.aliyuncs.com/dewey_zodiac/zodiac-dewey-backend:latest
```

配合 `docker-compose.yml` 和前端服务一起使用。
