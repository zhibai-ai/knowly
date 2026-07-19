# 知了 knowly · 测试样例文档

本目录提供一套**自造、零版权**的中文测试样例，用于验证知了 v0.1 的各项能力。

> ⚠️ **版权声明**：所有样例内容均为知了项目自造（如《知了示例·茶文化入门》《知了示例·常见算法速查》），不包含任何真实版权内容（如倪海厦教学资料等不会进入开源仓库）。开源协议为 Apache 2.0。

## 样例清单与验证能力

| 样例文件 | 主题 | 格式特征 | 验证的能力点 |
|---|---|---|---|
| `tea-single-column.pdf` | 茶文化入门 | 单栏文字版 PDF，纯中文，含 H1/H2/段落/列表 | PDF 文本提取、标题层级识别、结构感知分段（F1.1, F2.5, F2.6） |
| `tea-two-column.pdf` | 茶文化入门 | **双栏**文字版 PDF，含页眉页脚 | **版面分析（F1.7）**：双栏识别、按"左栏→右栏"正确拼接不串栏、页眉页脚去噪 |
| `tea.docx` | 茶文化入门 | Word，含标题样式、列表 | Word 解析（F1.2）、样式/层级保留 |
| `tea-scanned.pdf` | 茶文化入门 | **扫描版 PDF（图片型，无文本层）** | **扫描版检测 + OCR（F1.1, F1.4）**：字符数 < 阈值 → 判定扫描版 → 转 OCR |
| `tea-types.png` | 茶文化（六大茶类） | PNG 图片，含中文 | **图片 OCR（F1.4）**；含实体名（铁观音/正山小种/龙井/祁门红茶），可为 v0.2 图谱抽取测试 |
| `tea-copy-duplicate.docx` | 茶文化入门 | 与 `tea.docx` **内容完全相同**，仅文件名不同 | **文件级去重（F2.4）**：内容哈希相同 → 识别为重复 |
| `tea-traditional.pdf` | 茶文化入门（繁体版） | 繁体中文，内容与简体版高度相似 | **繁简统一 + 段落级近似去重（F2.4）**：繁转简后 MinHash 相似度 > 阈值 → 标记近似重复 |
| `algo-single-column.pdf` | 常见算法速查 | 中英混排，含代码块、O(n log n) 等特殊符号 | 多语言/特殊字符处理、代码块识别、中英混合分段 |
| `algo.docx` | 常见算法速查 | Word，中英混排 + 代码 | Word 解析 + 多语言 |
| `corrupted-broken.pdf` | （损坏） | 伪造的损坏 PDF（头存在但内容损坏） | **失败隔离（A7）**：单个文件解析失败不中断整体流程，记录到错误报告 |

## 验收对照

对应 `02-需求规格说明书.md` 第 7 章验收标准：

| 验收项 | 用哪些样例验证 |
|---|---|
| A1 能解析 PDF 和 Word | `tea-*.pdf` + `tea.docx` |
| A2 文本质量过关 | 检查产出 Markdown 无乱码 |
| A3 分段合理 | 检查 `algo.docx` 的代码块没被切碎 |
| A5 默认产 Markdown | 跑 clean-only 配置 |
| A7 单文件失败不中断 | `corrupted-broken.pdf` |
| A11 版面分析生效 | `tea-two-column.pdf` 不串栏 |

## 重新生成（重要）

样例**由 `generate_samples.py` 程序化生成，二进制文件本身不入 Git 仓库**（见 `.gitignore`）。这样仓库只保留几 KB 的脚本，避免几十 MB 的 PDF（CJK 字体嵌入）污染仓库。

```bash
# 1. 安装依赖（fonttools 用于首次从系统 Noto CJK 抽取 SC 子字体为 otf）
pip install python-docx Pillow PyMuPDF fonttools

# 2. 生成所有样例（首次会自动抽取字体到 /data/knowly/.fonts/）
cd knowly-dist/examples/samples
python generate_samples.py
```

> **为什么用脚本生成而非手工制作？**
> - **可重现**：任何人 clone 后一条命令即可得到完全相同的样例，便于 CI 测试
> - **可演进**：内容修改在 `TEA_CONTENT` / `ALGO_CONTENT` 常量里改，重跑即可
> - **零版权风险**：内容是程序里自造的字符串，与真实资料完全无关
> - **省仓库空间**：PDF/图片是大文件，脚本只占几 KB
>
> PDF 用 PyMuPDF (fitz) 生成 —— 它原生支持 CJK 字体插入且有真实文本层（可被知了提取验证），比 fpdf2/reportlab 在中文字体处理上更稳定。
