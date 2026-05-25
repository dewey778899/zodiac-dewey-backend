# Production Deploy

This project is deployed with SQLite only.

## Stack

- Frontend: static files served by Nginx
- Backend: Spring Boot jar on port `8080`
- Database: SQLite file on local disk

## Server requirements

- 2 CPU / 2 GB RAM or above
- Java 17
- Nginx
- A domain name with HTTPS if the page is used inside WeChat

## 1. Install dependencies

Ubuntu or Debian:

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk nginx git
```

CentOS / Rocky / AlmaLinux:

```bash
sudo yum install -y java-17-openjdk-devel nginx git
```

## 2. Get the code

```bash
git clone https://github.com/dewey778899/zodiac_dewey.git
cd zodiac_dewey
cp .env.example .env
```

Fill in `.env`:

```bash
AI_API_KEY=your_deepseek_key
CLAUDE_API_KEY=your_claude_key
CLAUDE_MODEL=claude-opus-4-7
DB_URL=jdbc:sqlite:./data/zodiac_dewey.db
DB_DRIVER=org.sqlite.JDBC
HIBERNATE_DIALECT=org.hibernate.community.dialect.SQLiteDialect
HIBERNATE_USE_GET_GENERATED_KEYS=false
JPA_DDL_AUTO=update
ADMIN_USERNAME=admin
ADMIN_PASSWORD=replace_with_a_strong_password
CORS_ALLOWED_ORIGINS=https://your-domain.com
SERVER_PORT=8080
```

## 3. Build

```bash
cd backend
mvn clean package -DskipTests
cd ..
```

## 4. Start backend

```bash
java -jar backend/target/zodiac-dewey.jar
```

The SQLite database file is created automatically the first time the app starts.

## 5. Run with systemd

Create `/etc/systemd/system/zodiac-dewey.service`:

```ini
[Unit]
Description=Zodiac Dewey Backend
After=network.target

[Service]
Type=simple
User=zodiac
WorkingDirectory=/home/zodiac/zodiac_dewey
EnvironmentFile=/home/zodiac/zodiac_dewey/.env
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /home/zodiac/zodiac_dewey/backend/target/zodiac-dewey.jar
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable it:

```bash
sudo systemctl daemon-reload
sudo systemctl enable zodiac-dewey
sudo systemctl start zodiac-dewey
sudo systemctl status zodiac-dewey
```

## 6. Nginx

Example config:

```nginx
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;

    root /var/www/zodiac;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
}
```

## 7. Verify

```bash
curl http://127.0.0.1:8080/api/health
```

Expected result:

```json
{"status":"ok"}
```

## 8. Backup

Back up the SQLite file regularly:

```bash
cp backend/data/zodiac_dewey.db /backup/zodiac_dewey_$(date +%F).db
```
