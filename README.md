# 面面是 - 面试刷题平台

> 基于 Next.js + Spring Boot + Redis + MySQL + Elasticsearch 的全栈在线面试刷题平台

## 项目简介

面面是是一个功能完善的在线面试刷题平台，支持用户注册登录、题目分类浏览、在线代码编辑与提交、自动判题反馈等核心功能。

**核心功能模块：**
- 用户系统：注册、登录、个人中心、刷题日历
- 题库管理：题库创建、题目分类、批量管理
- 在线刷题：代码编辑器、在线提交、自动判题、结果反馈
- 题目搜索：基于 Elasticsearch 的分词搜索
- 管理后台：用户管理、题库管理、题目管理

## 技术栈

### 后端
| 技术 | 用途 |
|------|------|
| Spring Boot 2.7 | 后端框架 |
| MyBatis-Plus + MySQL | 数据持久化 |
| Redis + Caffeine | 多级缓存 |
| Redisson | 分布式锁 |
| Elasticsearch | 全文搜索 |
| Sa-Token | 权限认证 |
| Druid | 数据库连接池 |
| Sentinel | 流量控制 |
| Nacos | 配置中心 |

### 前端
| 技术 | 用途 |
|------|------|
| Next.js 14 | 服务端渲染框架 |
| React 18 | UI 框架 |
| Ant Design | 组件库 |
| Redux Toolkit | 状态管理 |
| TypeScript | 类型安全 |

## 项目架构

```
interview_platform/
├── mianmianshi-backend/          # Spring Boot 后端
│   ├── src/main/java/com/mianmianshi/platform/
│   │   ├── controller/           # API 控制器
│   │   ├── service/              # 业务逻辑层
│   │   ├── mapper/               # 数据访问层
│   │   ├── model/                # 实体/DTO/VO
│   │   ├── config/               # 配置类
│   │   ├── aop/                  # 切面（鉴权、日志）
│   │   ├── job/                  # 定时任务
│   │   └── utils/                # 工具类
│   ├── sql/                      # 数据库脚本
│   └── pom.xml                   # Maven 配置
├── mianmianshi-frontend/         # Next.js 前端
│   ├── src/
│   │   ├── app/                  # 页面（App Router）
│   │   ├── components/           # 通用组件
│   │   ├── api/                  # API 调用层
│   │   ├── layouts/              # 布局组件
│   │   └── stores/               # Redux Store
│   └── package.json
└── README.md
```

## 快速开始

### 环境要求
- JDK 1.8+
- Node.js 18+
- MySQL 5.7+
- Redis 6.0+
- Elasticsearch 7.x

### 后端启动
```bash
cd mianmianshi-backend

# 初始化数据库
mysql -u root -p < sql/create_table.sql

# 修改 application.yml 中的数据库配置后启动
mvnw spring-boot:run
```

### 前端启动
```bash
cd mianmianshi-frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

访问 http://localhost:3000 即可使用。

## 核心功能详解

### 在线代码提交与判题
- 支持 Java、Python 编程语言
- 代码模板预填充
- 多测试用例自动判题
- 编译错误、运行错误、超时等状态反馈
- 详细的测试用例通过情况展示

### 题目管理
- 题目 CRUD
- 难度标签（简单/中等/困难）
- 标签分类
- 批量关联题库

### 安全性保障
- Sa-Token 权限控制
- Sentinel 流量控制
- IP 黑白名单
- 同端登录检测
- 反爬虫策略

## 许可证

本项目仅用于学习交流和个人简历展示。

## RabbitMQ 部署步骤
# 1. 启动 RabbitMQ
docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 `
  -e RABBITMQ_DEFAULT_USER=admin -e RABBITMQ_DEFAULT_PASS=admin123 `
  rabbitmq:3.11-management

# 2. 导入 mq_sync_record 建表
mysql -u root -p mianmianshi < sql/create_table.sql

# 3. 启动项目
mvn spring-boot:run