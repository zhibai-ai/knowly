# 知了 knowly

> **Turn raw documents into AI-ready knowledge. 把原始文档变成 AI 可用的知识。**

知了是一个开源的数据 AI 化清洗工具。把任意一批原始文档（PDF / Word / 图片 / 视频），自动转换成 AI 可用的结构化知识（干净文本 + 向量 + 知识图谱）。

蝉饮露高鸣，古喻清通透；"知了"即"知晓了悟"——让沉睡的资料变成 AI 能消化的知识。

## ✨ 功能特性

- 📄 **多格式解析**：PDF / Word / PPT / Excel / HTML / TXT / Markdown，基于 Apache Tika
- 🔍 **OCR 识别**：扫描版 PDF / 图片文字识别，基于 Tesseract（中英文）
- 🧹 **智能清洗**：编码修复、繁简统一（OpenCC）、水印去除、重复段落去重
- ✂️ **两阶段分段**：按结构边界切粗 → 按 max_size 切细，保留章节标题
- 🔮 **语义分段**（可选）：基于 embedding 余弦相似度的语义切分
- 📦 **多 Sink 产出**：Markdown（人类可读）/ JSONL（结构化）/ Qdrant / PgVector（向量库）
- 🖼️ **图片提取**：图片型 PDF（卦图 / 画册）自动提取图片 + Markdown 引用
- ⚡ **双入口**：CLI（批处理/自动化）+ Web 调优工作台（可视化交互）
- 🔌 **全链路 SPI**：解析器 / 分段策略 / 向量库 / 产出格式均可插拔

## 🚀 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Tesseract OCR（可选，扫描版 PDF 需要）
- PostgreSQL + PgVector（可选，向量库产出需要）
- DashScope API Key（可选，向量化需要）

### 构建

```bash
git clone https://github.com/AImusic/knowly.git
cd knowly/knowly-server
mvn clean install -DskipTests
```

### CLI 使用

```bash
# 生成配置文件
java -jar knowly-cli/target/knowly-cli.jar init

# 运行清洗（默认产 Markdown + JSONL）
java -jar knowly-cli/target/knowly-cli.jar run \
  --config pipeline.yaml \
  --input ./docs \
  --output ./output
```

### Web UI 使用

```bash
# 启动后端
java -jar knowly-api/target/knowly-cli.jar

# 启动前端
cd knowly-web
npm install
npm run dev
```

访问 http://localhost:5173，默认账号 `admin / admin123`

## 🏗️ 技术栈

| 层 | 技术 |
|---|---|
| 语言 | Java 17+ |
| 框架 | Spring Boot 3.x |
| 文档解析 | Apache Tika 2.x + PDFBox 2.x |
| OCR | Tesseract 5.x（tess4j） |
| 繁简转换 | OpenCC4j |
| 向量化 | DashScope text-embedding-v3（1024 维） |
| 向量库 | Qdrant / PostgreSQL + PgVector |
| 状态存储 | SQLite |
| 前端 | React + Vite + TypeScript |

## 📦 项目结构

```
knowly/
├── knowly-server/          # 后端（Maven 多模块）
│   ├── knowly-common/      # 公共工具类
│   ├── knowly-core/        # 内核引擎（纯库）
│   ├── knowly-ingest/      # 摄取层（Tika/OCR/版面分析）
│   ├── knowly-clean/       # 清洗层（去噪/去重/分段）
│   ├── knowly-embed/       # 向量化层（DashScope + 各 Sink）
│   ├── knowly-api/         # Web API（Spring Boot）
│   └── knowly-cli/         # CLI 入口
├── knowly-web/             # 前端（React + Vite）
├── knowly-dist/            # 分发包（示例/quickstart）
└── docs/                   # 文档（8 份）
```

## 📖 文档

- [立项背景](docs/01-立项背景.md)
- [竞品调研报告](docs/02-竞品调研报告.md)
- [需求分析](docs/03-需求分析.md)
- [系统架构](docs/04-系统架构.md)
- [开发规范](docs/05-开发规范.md)
- [后端开发文档](docs/06-后端开发文档.md)
- [部署文档](docs/07-部署文档.md)
- [测试文档](docs/08-测试文档.md)

## 📄 License

Apache License 2.0
