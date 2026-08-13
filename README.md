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

- **JDK 17+**（必装）
- **Maven 3.8+**（必装）
- Tesseract OCR（可选，扫描版 PDF / 图片 OCR 需要）
- PostgreSQL + PgVector 或 Qdrant（可选，向量库产出需要）
- DashScope API Key（可选，向量化需要）

> **最小试用**：只装 JDK + Maven 即可。知了默认产出 Markdown + JSONL，无需任何数据库。

### 构建

```bash
git clone https://github.com/zhibai-ai/knowly.git
cd knowly/knowly-server
mvn clean install -DskipTests
```

产出：
- `knowly-cli/target/knowly-cli-0.1.0-SNAPSHOT.jar`（CLI 可执行 fat jar）
- `knowly-api/target/knowly-api-0.1.0-SNAPSHOT.jar`（Web API）

### ⚡ 30 秒跑通（零数据库、零 API Key）

仓库自带零版权测试样例（`knowly-dist/examples/quickstart/`），无需准备文件，直接体验清洗：

```bash
# 在仓库根目录执行
java -jar knowly-server/knowly-cli/target/knowly-cli-0.1.0-SNAPSHOT.jar run \
  --config knowly-server/knowly-core/src/main/resources/knowly-defaults.yaml \
  --input knowly-dist/examples/quickstart \
  --output ./output
```

跑通后查看产出：

```bash
ls ./output/00-clean/      # 清洗后的 Markdown（按章节组织）
ls ./output/01-chunks/     # 结构化 JSONL（每行一个 chunk）
ls ./output/02-reports/    # 处理报告 + 错误报告
```

> 测试样例包含：`tea-intro.md`（中文茶文化）、`algorithms.txt`（中英混排算法）、`python-basics.html`、`corrupted.pdf`（损坏文件，验证失败隔离）。

### CLI 使用

```bash
# 运行清洗（默认产 Markdown + JSONL，不装数据库也能跑）
java -jar knowly-cli/target/knowly-cli-0.1.0-SNAPSHOT.jar run \
  --config <配置文件或模板> \
  --input ./docs \
  --output ./kb

# 生成配置文件
java -jar knowly-cli/target/knowly-cli-0.1.0-SNAPSHOT.jar init

# 查看报告
java -jar knowly-cli/target/knowly-cli-0.1.0-SNAPSHOT.jar report ./kb
```

`--config` 可用模板名：`clean-only`（默认，纯文件产出）、`rag-pgvector`、`rag-qdrant`（需向量库）。

### Web UI 使用（清洗调优工作台）

```bash
# 启动后端
java -jar knowly-api/target/knowly-api-0.1.0-SNAPSHOT.jar

# 启动前端（另一个终端）
cd knowly-web
npm install
npm run dev
```

访问 http://localhost:5173，默认账号 `admin / admin123`

### 配置参数

清洗行为通过 YAML 配置驱动，常用参数（见 `knowly-dist/templates/` 各模板）：

| 参数 | 说明 | 默认值 |
|---|---|---|
| `stages.clean.chunking.max_size` | 单个 chunk 最大字符数 | 500 |
| `stages.clean.chunking.overlap` | 相邻 chunk 重叠字符数 | 50 |
| `stages.clean.chunking.strategy` | 分段策略（structure_aware/semantic） | structure_aware |
| `stages.clean.normalize.traditional_to_simplified` | 繁体转简体（中文默认开） | true |
| `stages.clean.dedup.paragraph_level.threshold` | 段落级近似去重阈值 | 0.85 |
| `stages.ingest.layout_analysis` | 版面分析（light/off） | light |
| `stages.ingest.ocr.enabled` | OCR 开关（扫描版 PDF/图片） | true |

改 YAML 后重跑即可生效，无需改代码。

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
│   ├── knowly-graph/       # 图谱层（v0.2，当前仅预留 SPI 接口骨架）
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
