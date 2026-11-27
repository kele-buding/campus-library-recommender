# 校园图书借阅行为分析与推荐系统（项目 17）

基于 **Java + Spring Boot 3 + MyBatis-Plus + Flyway + PostgreSQL（本地免安装 H2 兜底）** 构建的校园图书借阅分析与推荐系统后端。

## 1. 环境要求

- JDK 21+（本机已验证 OpenJDK 27 + `--release 21`）
- Apache Maven 3.9+
- 数据库：
  - **本地快速开发（默认 `h2`）**：零配置，自动在 `./data/campus_library` 创建本地文件库
  - **目标部署与并发验收（`postgres`）**：通过 `docker compose up -d postgres` 一键启动 PostgreSQL 16

## 2. 环境配置

```bash
# 复制环境变量模板（真实云密钥写入 .env，严禁提交到 Git）
cp .env.example .env
```

如需切换为 PostgreSQL：

```bash
docker compose up -d postgres
export SPRING_PROFILES_ACTIVE=postgres
```

## 3. 启动项目与装载初始化数据（DU-10 / 第 13.1 节样例）

启动时 Flyway 会自动执行建表脚本（`V1__init_schema.sql`）。可通过启动参数装载课程标准初始化数据：

```bash
# 启动并自动装载初始化数据（幂等，重复执行不会产生重复数据或库存漂移）
mvn spring-boot:run -Dspring-boot.run.arguments="--seed"

# 清空当前演示库并重置为第 13.1 节标准样例（用于 SIM-01 ~ SIM-11 独立对照验证）
mvn spring-boot:run -Dspring-boot.run.arguments="--seed-reset"
```

启动后访问健康检查接口验证运行状态：
- 健康检查：`http://127.0.0.1:8080/health`
- H2 数据库控制台（仅 `h2` 模式）：`http://127.0.0.1:8080/h2-console`

### 初始化数据集摘要（模拟当天 `2026-11-20`）

- **专业（2 个）**：软件工程（SE：A1、A2、A5）、信息管理（IM：A3、A4、A6）
- **账号（9 个）**：
  - 学生：`a1` ~ `a6`
  - 图书馆管理员：`librarian`
  - 采购员：`buyer`
  - 系统管理员：`sysadmin`
  - 默认演示密码由环境变量 `SEED_DEFAULT_PASSWORD` 控制（默认 `CampusLib#2026`，以 BCrypt 哈希存储）
- **馆藏书目（15 种，`book_id` 101–115）**：含 `115 人工智能概论`（可借 1 本，有效预约 3 人：A2/A3/A5）及 `104 算法设计`（在借 1 本，可借 0 本）
- **借阅记录（17 条，L01–L17）**：含 4 条在借记录（L02/L05/L09/L10）覆盖到期差值 `-1 / 0 / 3 / 4` 天边界
- **对象存储元数据（16 条）**：每本书 1 条私有封面对象，101 号书另含 1 条私有附件对象

## 4. 运行自动化测试

```bash
mvn test
```
