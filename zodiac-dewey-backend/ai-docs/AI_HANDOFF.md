AI Handoff

最近修改：
- 2026-05-25 16:30 Bug修复：事业/财运不再校验TA信息（修改 validateThemeForm 函数）
- 2026-05-25 16:20 UI优化：深度解析按钮点击弹窗提示（修改 initModelUi 函数）
- 2026-05-25 16:10 UI优化：事业/财运按钮布局调整（设置最小高度 480px）
- 2026-05-25 16:00 UI优化：出生时间和城市一行显示（CSS Grid布局）
- 2026-05-25 15:50 UI优化：城市选择改为内联下拉框（省/市两级联动）
- 2026-05-25 10:48 移除 XorPay，保持纯静态收款码模式
- 2026-05-25 10:48 修复 SwissEphemerisCalculator Bean 定义（添加 @Component 注解）
- 2026-05-25 10:48 创建 README.md 项目文档
- 2026-05-25 10:48 更新 AI 协作文档

待处理：
- 防火墙配置（需要管理员权限）
- 生产环境部署（HTTPS、域名）
- 前端交互优化

重要提示：
- 服务启动方式：cd zodiac-dewey-backend && java -jar target/zodiac-dewey.jar
- 不要使用 mvn spring-boot:run（会被 aegis agent 拦截导致崩溃）
- 收款码文件位置：zodiac-dewey-frontend/frontend/img/
- 前端配置文件：zodiac-dewey-frontend/frontend/assets/scripts/config.js