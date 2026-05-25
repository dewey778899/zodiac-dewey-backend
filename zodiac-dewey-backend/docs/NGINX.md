# 🔧 Nginx 配置参考

> 完整可用的 Nginx 配置示例,复制粘贴 + 替换域名即可。

## 标准配置(HTTPS + 反向代理)

```nginx
# /etc/nginx/sites-available/zodiac

# HTTP 跳 HTTPS
server {
    listen 80;
    listen [::]:80;
    server_name 你的域名.com;
    return 301 https://$host$request_uri;
}

# HTTPS 主站
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name 你的域名.com;

    # SSL
    ssl_certificate /etc/letsencrypt/live/你的域名.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/你的域名.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;

    # 安全头
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    # 前端静态
    root /var/www/zodiac;
    index index.html;

    # 主入口
    location / {
        try_files $uri $uri/ /index.html;
        add_header Cache-Control "no-cache, no-store, must-revalidate";
    }

    # 静态资源(如果将来加 CSS/JS 文件)
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    # API 转发
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";

        # Claude 慢,给 200 秒
        proxy_read_timeout 200s;
        proxy_send_timeout 200s;
        proxy_connect_timeout 30s;

        # 不要缓存 API
        proxy_cache_bypass $http_upgrade;
        add_header Cache-Control "no-store";
    }

    # 请求体大小
    client_max_body_size 2M;

    # gzip
    gzip on;
    gzip_vary on;
    gzip_proxied any;
    gzip_comp_level 6;
    gzip_types text/plain text/css text/javascript text/xml application/json application/javascript application/xml+rss application/atom+xml image/svg+xml;

    # 访问日志(可选)
    access_log /var/log/nginx/zodiac.access.log;
    error_log /var/log/nginx/zodiac.error.log;
}
```

## 启用配置

### Ubuntu / Debian

```bash
sudo ln -s /etc/nginx/sites-available/zodiac /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

### CentOS / Rocky / AlmaLinux

```bash
# 直接放到 conf.d
sudo cp zodiac.conf /etc/nginx/conf.d/
sudo nginx -t
sudo systemctl reload nginx
```

## Cloudflare 套 CDN(选用)

如果你的 ECS 是境外服务器、或者想加速,可以套 Cloudflare:

1. 把域名 DNS 改到 Cloudflare
2. SSL/TLS 模式选 **Full (strict)** 或 **Flexible**
3. Cloudflare 后台开启:
   - Always Use HTTPS ✅
   - Auto Minify (CSS/JS) ✅
   - Brotli ✅
   - Rocket Loader ❌(可能影响 Canvas)

## 安全加固

### 1. 屏蔽常见恶意 UA

```nginx
# 加到 server 块里
if ($http_user_agent ~* (curl|wget|libwww|python-requests|scrapy)) {
    return 403;
}
```

⚠️ 这会屏蔽合法的 curl 测试,慎用。

### 2. 限制单 IP 请求频率(Nginx 层,后端也有限流双保险)

```nginx
# 加到 http 块(/etc/nginx/nginx.conf 顶部)
limit_req_zone $binary_remote_addr zone=api:10m rate=10r/m;

# 加到 location /api/ 里
limit_req zone=api burst=5 nodelay;
limit_req_status 429;
```

### 3. 屏蔽特定国家(选用)

需要 GeoIP 模块,这里略。

## 验证 SSL 等级

部署后去 https://www.ssllabs.com/ssltest/ 测试,应该是 A 或 A+ 评级。
