# 📡 API 接口文档

> Base URL: `https://你的域名.com/api`(本地:`http://localhost:8080/api`)

## 接口列表

### 1. POST `/api/compatibility` — 生成合盘报告

**调用流程**:用户填完表 → 前端调用此接口 → 后端调 Claude → 返回报告

**请求**:

```json
{
  "personA": {
    "name": "dewey",
    "gender": "male",
    "birthDate": "1986-12-10",
    "birthTime": "14:30",
    "birthPlace": "北京"
  },
  "personB": {
    "name": "snow",
    "gender": "female",
    "birthDate": "1988-11-06",
    "birthTime": "10:15",
    "birthPlace": "上海"
  }
}
```

字段说明:

| 字段 | 必填 | 类型 | 说明 |
|---|---|---|---|
| name | ✅ | string | 姓名,1-20 字 |
| gender | ✅ | string | `male` / `female` |
| birthDate | ✅ | string | 生日 `YYYY-MM-DD` |
| birthTime | ⭐ | string | 出生时间 `HH:mm`,推荐填(算月亮/上升) |
| birthPlace | ⭕ | string | 出生城市,可选 |

**成功响应** `200`:

```json
{
  "score": 88,
  "relationshipType": "灵魂深度互补型",
  "tagline": "你们最爱对方的地方,恰好是最容易伤到对方的地方。",
  "chapters": [
    {
      "title": "你们的星座基因",
      "emoji": "✨",
      "content": "snow,开始分析你们之前..."
    }
    // 共 6 章
  ],
  "essence": [
    "不要测试他,直接说\"我希望你抱我\",射手男 100% 会执行明示。",
    // 共 6 条
  ],
  "reportUid": "N° SASC-260520-RT93",
  "zodiacA": {
    "sun": "射手座",
    "moon": "双鱼座",
    "rising": "狮子座"
  },
  "zodiacB": {
    "sun": "天蝎座",
    "moon": "巨蟹座",
    "rising": "水瓶座"
  }
}
```

**限流响应** `429`:

```json
{
  "error": "rate_limited",
  "message": "你今天已经测了 3 次了,明天再来吧 💕"
}
```

**参数错误响应** `400`:

```json
{
  "error": "validation_failed",
  "message": "名字不能为空"
}
```

**服务错误响应** `500`:

```json
{
  "error": "generation_failed",
  "message": "AI 服务暂时不可用,请稍后再试"
}
```

---

### 2. POST `/api/wechat` — 用户提交微信号

**调用流程**:用户看完报告后,可选填微信号 → 前端调用此接口存入 DB

**请求**:

```json
{
  "reportUid": "N° SASC-260520-RT93",
  "wechatId": "xiaodengge_001"
}
```

**响应**:

```json
{
  "status": "ok",
  "message": "提交成功,小登哥会看到你的消息 💕"
}
```

**报告不存在** `404`:

```json
{
  "error": "not_found",
  "message": "报告编号不存在"
}
```

---

### 3. POST `/api/share/{uid}` — 记录分享次数

**调用流程**:用户点击分享卡片下载时调用,用于统计

**请求**:无请求体

**响应**:

```json
{
  "status": "ok"
}
```

---

### 4. GET `/api/health` — 健康检查

**响应**:

```json
{
  "status": "ok",
  "service": "zodiac-api",
  "global_used": 12,
  "global_total": 200
}
```

字段说明:
- `global_used`:今天已生成多少份报告
- `global_total`:每日上限

可以用这个接口监控 API 是否正常 + 今天用量。

---

## 限流规则

| 维度 | 默认值 | 配置项(.env) |
|---|---|---|
| 全局每日 | 200 次 | `RATELIMIT_DAILY_TOTAL` |
| 单 IP 每日 | 3 次 | `RATELIMIT_PER_IP` |

**重置时机**:每天凌晨 00:00 自动重置。

**触发限流时的用户体验**:返回 HTTP 429,前端会显示友好提示。

---

## 错误码总览

| HTTP 状态 | error 字段 | 含义 |
|---|---|---|
| 400 | validation_failed | 请求参数错误 |
| 404 | not_found | 资源不存在 |
| 429 | rate_limited | 触发限流 |
| 500 | generation_failed | AI 生成失败 |
| 500 | internal_error | 服务异常 |

---

## 调用示例

### cURL

```bash
curl -X POST https://你的域名.com/api/compatibility \
  -H "Content-Type: application/json" \
  -d '{
    "personA":{"name":"dewey","gender":"male","birthDate":"1986-12-10","birthTime":"14:30"},
    "personB":{"name":"snow","gender":"female","birthDate":"1988-11-06","birthTime":"10:15"}
  }'
```

### JavaScript (前端)

```javascript
const resp = await fetch('/api/compatibility', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({
    personA: {name:'dewey', gender:'male', birthDate:'1986-12-10', birthTime:'14:30'},
    personB: {name:'snow', gender:'female', birthDate:'1988-11-06', birthTime:'10:15'}
  })
});
const data = await resp.json();
console.log(data);
```
