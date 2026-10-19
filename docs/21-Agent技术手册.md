# Agent / RAG 技术手册

> **和后端那本同一个格式，每个技术点六段**：
> ① 场景（真实问题 + 实测数字）
> ② 代码（项目里的真实实现）
> ③ 原理（为什么这样能解决问题）
> ④ 边界（什么情况下不该用）
> ⑤ **简历怎么写**（可以直接抄的措辞）
> ⑥ 面试怎么答（带参考答案）
>
> 更新：2026-10-08

---

# 一、RAG 链路：为什么不做微调

## ① 场景

大模型有两个硬伤：

1. **不知道私有数据**——实验室的招生要求、设备清单，模型训练时没学过
2. **会编造**——不知道也会编一个看起来合理的答案，这在知识库场景是致命的

而且第二个问题更隐蔽：编造的答案往往还带着引用角标，**看起来更可信**。

## ② 代码

入口是这句话：**把检索和生成串起来**（`agent/KbAgentService.java`）。

```java
public AgentContext prepare(String question, Integer topK) {
    List<RetrievedChunk> chunks = retrievalService.retrieve(question, topK);  // 检索
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(SystemMessage.from(SYSTEM_PROMPT));                          // 规则
    messages.add(UserMessage.from(buildUserPrompt(question, chunks)));        // 资料 + 问题
    return new AgentContext(List.copyOf(messages), chunks, question);
}
```

资料是这样拼进提示词的：

```java
prompt.append("【参考资料】\n");
for (int i = 0; i < chunks.size(); i++) {
    prompt.append('[').append(i + 1).append("] 文件：").append(chunk.fileName());
    if (chunk.section() != null && !chunk.section().isBlank()) {
        prompt.append("｜章节：").append(chunk.section());   // 带上章节名，引用更可追溯
    }
    prompt.append('\n').append(truncate(chunk.text())).append("\n\n");
}
prompt.append("【用户问题】\n").append(question);
```

## ③ 原理

RAG 的核心思想是**把"知识"和"能力"解耦**：

- **能力**（理解、推理、表达）留在模型权重里
- **知识**（会持续变化的事实）放在外部知识库里

提问时临时把相关资料取出来、拼进提示词。三个好处：

| 好处 | 怎么来的 |
|---|---|
| 答案可追溯 | 要求模型标注引用编号，用户能核对原文 |
| 更新成本低 | 改文档就行，不用重新训练 |
| 能防幻觉 | 资料里没有就明确说不知道 |

## ④ 边界：什么时候该微调而不是 RAG

| 需求 | RAG | 微调 |
|---|---|---|
| 让模型了解**会变化**的事实 | 适合 | 不适合（每次更新都要重训） |
| 固定某种**输出风格/格式** | 提示词能解决 | 适合（更稳定） |
| 让模型学会**新的任务能力**（如特定领域的推理） | 不适合 | 适合 |
| 数据量少、没有 GPU | 适合 | 不适合 |

**两者不互斥**：可以先微调让模型熟悉领域术语，再用 RAG 提供实时知识。

## ⑤ 简历怎么写

> 独立设计并实现基于 RAG 的知识库问答系统，将知识存储与模型能力解耦，支持文档动态更新无需重训；通过引用编号实现答案可追溯，通过资料约束与拒答策略抑制幻觉。

## ⑥ 面试怎么答

**Q：为什么用 RAG 而不是微调？**

> 微调适合"让模型学会一种风格或能力"，不适合"让模型记住一批会持续变化的事实"。知识库内容每周都可能变，微调一次要几小时到几天，而且新知识可能覆盖旧知识。
> RAG 把知识和能力分离：模型能力不变，知识放外部库随时更新。**这是架构上的解耦**，也是 RAG 成为主流的根本原因。

**Q：RAG 和"把全文塞进提示词"有什么区别？**

> 一是上下文长度限制，文档可能几十万字塞不进去；二是成本，提示词按 token 计费；三是效果，**无关内容会稀释模型注意力**，反而不如只给最相关的几段。
> 检索的本质是"用最小的上下文换最高的答案质量"。

**Q：RAG 的完整链路有哪几步？**

> 离线：解析文档 → 切分成片段 → 向量化 → 入库。在线：问题向量化 → 检索召回 → 融合与重排 → 拼装提示词 → 模型生成 → 标注引用。
> 每一段都可以独立优化和评测，这也是我把它拆成独立模块的原因。

---

# 二、结构感知切分

## ① 场景

一份 68,484 字的文档不能整篇塞给模型。但切得不好会直接毁掉检索效果：

- 切太碎：每段只有一句话，没有上下文，检索出来也没用
- 切太大：一段里混了多个主题，语义被稀释
- **按固定长度硬切**：标题和正文被切开，检索到的片段经常"不知道在讲什么"

实测：68,484 字 → **111 个片段**。

## ② 代码

**第一步，按标题切成章节**（`ingest/ContentSectioner.java`）：

```java
private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+\\S.*$");

public static List<Section> split(String rawText) {
    List<Section> sections = new ArrayList<>();
    String currentTitle = null;
    StringBuilder buffer = new StringBuilder();

    for (String line : normalized.split("\n", -1)) {
        if (isHeading(line)) {
            flush(sections, currentTitle, buffer);   // 遇到标题，先把上一章收尾
            currentTitle = line.trim();
        } else {
            buffer.append(line).append('\n');
        }
    }
    flush(sections, currentTitle, buffer);
    // 整篇没有标题时，退化成"一个巨大章节"，交给后面的递归切分处理
    if (sections.isEmpty()) sections.add(new Section(null, normalized));
    return sections;
}
```

**第二步，章节内定长递归切分，并把章节名写进元数据**：

```java
for (ContentSectioner.Section section : ContentSectioner.split(text)) {
    Document sectionDocument = Document.from(section.text());
    for (TextSegment raw : splitter.split(sectionDocument)) {   // 800 字、重叠 120 字
        Metadata metadata = Metadata.from(Map.of(
                "document_id", documentId.toString(),
                "file_name", fileName,
                "section", section.title() == null ? "" : section.title(),  // 关键：章节名进元数据
                "chunk_index", String.valueOf(chunkIndex)));
        segments.add(TextSegment.from(raw.text(), metadata));
        chunkIndex++;
    }
}
```

## ③ 原理

**为什么带章节名能提升召回质量。** 片段本身是割裂的——"需要 JDK 17 和 Docker 24"这句话，单独看不知道在说什么。但加上元数据里的章节名"环境要求"之后：

1. 检索阶段的相似度计算更准（元数据参与匹配）
2. 回答时的引用位置更可信（能说出"来自哪个文件的哪一节"）

**为什么要有 120 字重叠。** 防止一句话正好被切断在两段之间，导致两边都读不全。重叠比例一般取片段长度的 10%~20%，我们 120/800 = 15%。

## ④ 边界

| 切分策略 | 特点 |
|---|---|
| **先结构后长度（当前方案）** | 兼顾上下文完整和语义聚焦 |
| 纯固定长度 | 最简单，但会切散结构 |
| 按语义切分 | 效果最好，但要用嵌入模型判断边界，慢且贵 |
| Parent Document Retrieval | **小块检索、大块喂给模型**，解决"粒度两难" |

**粒度两难的解法**：我们的 800/120 是经验值，还没有实验依据。**正确做法是用评测集做 A/B 实验来确定**——这是我知道但还没做的一步。

## ⑤ 简历怎么写

> 设计「章节感知 + 定长递归」两段式切分策略：先按 Markdown/编号标题切分章节，再在章节内按 800 字、120 字重叠细切，并将章节名写入向量元数据，使召回片段携带结构化上下文、引用可定位到具体章节。

## ⑥ 面试怎么答

**Q：切分粒度怎么权衡？**

> 粒度小检索更精准，但片段缺上下文；粒度大上下文完整，但语义被稀释、命中率下降。
> 工业界的解法是"**小块检索、大块喂给模型**"——用小块做向量匹配，命中后把它所在的父块或相邻块一起给模型，这叫 Parent Document Retrieval。

**Q：800 字和 120 字重叠是怎么定的？**

> 目前是经验值，800 字大约是一段完整论述的长度。**这是个诚实的回答**：当时没有数据支撑，所以我把它列进了待验证项，等评测集建好后可以用 A/B 实验确定最优值。面试时这么说比编一个理由好。

**Q：还有什么切分策略？**

> 按固定长度（最简单最差）、按递归字符（我们用的）、按文档结构（PDF 页、Word 段落）、按语义（用嵌入模型判断句子边界，效果最好但最慢）。实际系统常常组合使用。

**Q：重叠会不会导致重复内容？**

> 会。所以重叠不能太大，一般取片段长度的 10%~20%。重复内容对答案影响不大（模型能识别重复），但会增加 token 成本。

**Q：扫描件怎么处理？**

> 扫描件（图片型 PDF）没有文字层，解析出来是空的。我们加了自动识别——如果解析出的字符数明显少于文件体积（比如 265KB 的 PDF 只抽出 1028 个字符），就告警提示"这可能是扫描件，检索效果会很差"。**真正解决需要接 OCR**，这是已知缺口。

---

# 三、向量检索：原理与两个短板

## ① 场景

**它强在哪**：语义匹配。用户问"怎么部署"，文档里写的是"安装步骤"——没有共同的字，向量检索照样能匹配上。

**它弱在哪**：两个短板，其中一个是我们实测发现的。

## ② 代码

`retrieval/RetrievalService.java`：

```java
private List<RetrievedChunk> vectorSearch(String query) {
    // 走缓存：热点问题的向量不用重复计算
    Embedding queryEmbedding = queryEmbeddingCache.embed(query);

    EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(properties.getRetrieval().getVectorTopK())   // 召回 20 条候选
            .minScore(properties.getRetrieval().getMinScore())       // 低于 0.35 的直接丢
            .build();

    return embeddingStore.search(request).matches().stream()
            .map(match -> new RetrievedChunk(
                    match.embeddingId(),
                    match.embedded().text(),
                    match.embedded().metadata().getString("file_name"),   // 元数据一并取回
                    match.embedded().metadata().getString("section"),
                    parseInt(match.embedded().metadata().getString("chunk_index")),
                    match.score()))
            .toList();
}
```

## ③ 原理

**向量检索靠什么工作**：嵌入模型把文本映射成高维空间里的一点（bge-m3 是 1024 维），语义相近的文本，向量方向也相近。

**为什么用余弦相似度**：它算的是两个向量的**方向夹角**，不看长度。所以长文本和短句不会因为字数差异影响相似度——这对检索很重要。

**两个短板的本质**：

**短板一：它给每个片段单独打分，取前 K 名。** 但"这篇文章分几个章节""一共列举了几种方法"这类问题，答案**不在任何单个片段里**，而在于所有片段的整体结构。我们只取前 6 段，最多看到 6 个标题，而文档有十几个章节——**模型看到的资料本身就是残缺的**。

实测证据：压测时问"文章分为哪几个章节"，回答质量评为"差"。

**短板二：对标识符不敏感。** `KB_EMBED_BATCH_SIZE`、`dimensions`、`20015` 这类内容在语义空间里"不显著"——向量模型会把它们泛化成相近的意思。更典型的是 `kb_embedding` 和 `kb_chunk`：**语义相似、字面不同，向量模型几乎编码成一样的东西**，但用户要的是精确的那一个。

## ④ 边界

| 场景 | 向量检索是否合适 |
|---|---|
| 语义匹配、换个说法问同一件事 | 适合，这是它的强项 |
| 精确的标识符、编号、错误码 | 不适合，需要关键词通道 |
| 全局性/聚合性问题（分几章、共几条） | 不适合，需要结构化索引或 Agent 多步检索 |
| 需要严格按时间/权限过滤 | 不适合，应该用元数据过滤 + 结构化查询 |

## ⑤ 简历怎么写

> 通过压测定位出纯向量检索的两类能力边界——全局性聚合问题与精确标识符召回——并据此设计混合检索架构。

## ⑥ 面试怎么答

**Q：向量检索和关键词检索的本质区别？**

> 向量检索比的是**语义相似度**，"怎么部署"和"安装步骤"能匹配上，即使没有共同的字。关键词检索比的是**字面匹配**，词不一样就匹配不上。
> 所以向量检索能处理"换个说法问同一件事"，但精确的标识符、编号反而容易漏。**两者是互补的，不是替代关系。**

**Q：为什么用余弦相似度，不用欧氏距离？**

> 余弦只看向量方向，对长度不敏感；欧氏距离同时受方向和大小影响。文本向量的"长度"往往和文本长度或词频相关，不是我们关心的信息。另外，如果向量做过归一化，两者是等价的。

**Q：`minScore` 设成 0.35 合理吗？**

> 这是经验值，而且**强烈依赖嵌入模型**——bge-m3 的相似度分布和 OpenAI 的不一样，换模型必须重新标定。
> 调高的后果是可能一段都召回不到（系统变哑巴），调低的后果是召回大量噪声，模型会拿不相关的资料**硬答**，产出"带着引用、看起来可信、实际是错的"答案——**这比"没资料时编造"更危险**。

**Q：怎么发现系统答不了哪类问题？**

> 靠压测 + 评测集里的刻意覆盖。我在评测集里专门放了一道"文档一共几份"的全局性问题，它在所有配置下都会失分——**这是刻意留的，用来暴露能力边界**，而不是为了刷高分。

---

# 四、混合检索：自研 BM25 与中文分词

## ① 场景

向量检索对标识符不敏感（见上一节）。解决办法是**再加一路关键词检索**，两路结果融合。

但中文有个特殊问题：**没有天然的空格分隔**，关键词检索必须先解决分词。

## ② 代码

**中文用字符二元组分词**（`retrieval/KeywordIndex.java`）：

```java
static List<String> tokenize(String text) {
    List<String> terms = new ArrayList<>();
    StringBuilder ascii = new StringBuilder();
    List<Character> cjk = new ArrayList<>();

    for (int i = 0; i < text.length(); i++) {
        char ch = text.charAt(i);
        if (isCjk(ch)) {                       // 中文：按二元组
            flushAscii(ascii, terms);
            cjk.add(ch);
            continue;
        }
        boolean digitDot = ch == '.' && i > 0 && i + 1 < text.length()
                && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1));
        if (Character.isLetterOrDigit(ch) || digitDot) {
            flushCjk(cjk, terms);
            ascii.append(Character.toLowerCase(ch));   // 英文数字：整词
        } else {
            flushAscii(ascii, terms);
            flushCjk(cjk, terms);
        }
    }
    ...
}

private static void flushCjk(List<Character> run, List<String> terms) {
    // "任务分配" → "任务"、"务分"、"分配"
    for (int i = 0; i + 1 < run.size(); i++) {
        terms.add("" + run.get(i) + run.get(i + 1));
    }
    if (run.size() == 1) terms.add(String.valueOf(run.get(0)));
    run.clear();
}
```

> `digitDot` 那几行是刻意加的：让 `17.0.13` 这样的版本号保持完整，不被小数点切碎。

**BM25 打分**：

```java
private static final double K1 = 1.2;   // 词频饱和
private static final double B = 0.75;   // 长度归一化

for (String term : new HashSet<>(tokenize(query))) {
    List<Posting> postings = current.postings.get(term);
    if (postings == null) continue;
    // IDF：词越稀有，权重越高
    double idf = Math.log(1 + (current.docCount - postings.size() + 0.5) / (postings.size() + 0.5));
    for (Posting posting : postings) {
        int docLength = current.lengths.getOrDefault(posting.chunkId, 1);
        // 词频饱和 + 长度归一化：长文档不会因为词多就占便宜
        double denominator = posting.tf + K1 * (1 - B + B * docLength / current.avgLength);
        scores.merge(posting.chunkId, idf * posting.tf * (K1 + 1) / denominator, Double::sum);
    }
}
```

## ③ 原理

**BM25 的三个组成部分**：

| 部分 | 作用 | 不做的后果 |
|---|---|---|
| **IDF**（逆文档频率） | 词越稀有权重越高 | "的"这种词权重过高，把真正有信息量的词淹没 |
| **词频饱和**（k1） | 一个词出现 1 次和 10 次的差别，远大于 10 次和 100 次 | 关键词堆砌的文档会霸榜 |
| **长度归一化**（b） | 长文档不会因为词多占便宜 | 长文档天然得分高，短而精准的文档被埋没 |

**为什么中文用二元组而不是词典分词**：词典分词的召回依赖词典覆盖度，专业术语（比如 `ContentSectioner`、`群智感知`）往往不在通用词典里，需要维护自定义词表。二元组不需要词典、对未登录词天然友好，在**召回阶段**效果稳定。代价是索引体积更大（词项数量约等于字数），但几百个片段的规模完全承受得起。

**索引怎么维护**：用"脏标记 + 懒重建"。

```java
private volatile boolean dirty = true;

public void markDirty() { this.dirty = true; }        // 文档增删时打标记

private Snapshot ensureFresh() {
    if (dirty || snapshot == null) {                  // 下次检索时才真正重建
        synchronized (this) {
            if (dirty || snapshot == null) {
                snapshot = build();
                dirty = false;
            }
        }
    }
    return snapshot;
}
```

为什么这样做：文档增删是**低频操作**，检索是**高频操作**。如果每次增删都立刻重建，用户上传完要等；用脏标记推迟到下次检索，写入路径就变快了。**快照整体替换而不是原地修改，读的时候就不用加锁**——这是"写时复制"的思路。

## ④ 边界：为什么不用 Lucene 或 Elasticsearch

| 方案 | 适用规模 | 我们为什么不用 |
|---|---|---|
| **自研 BM25（当前方案）** | 几百到几万片段 | —— |
| Lucene | 十万级以上，需要复杂查询 | 引入索引生命周期管理，收益不足以抵消复杂度 |
| Elasticsearch | 百万级以上，分布式、需要聚合分析 | 需要独立部署和集群运维，严重不匹配 |

**判断标准不是"哪个技术更强"，而是"这个规模下哪个更合适"。**

## ⑤ 简历怎么写

> 针对向量检索在编号、专有名词上召回不准的问题，实现基于字符二元组分词与 BM25 的关键词检索通道，无需词典即可处理中文与未登录词；索引采用脏标记懒重建 + 不可变快照，读写无需加锁。

## ⑥ 面试怎么答

**Q：中文分词为什么不用 jieba 这类词典分词？**

> 词典分词的召回依赖词典覆盖度，专业术语往往不在通用词典里。二元组分词不需要词典、对未登录词友好，在召回阶段效果稳定。代价是索引更大，但这个规模完全承受得起。
> 顺带一提，Lucene 的 CJKAnalyzer 也是二元组分词，这是 CJK 检索的经典做法。

**Q：BM25 里的 k1 和 b 是什么？**

> k1 控制**词频饱和**——一个词出现 1 次和 10 次的差别，要远大于 10 次和 100 次的差别，否则关键词堆砌就能霸榜。b 控制**长度归一化**——长文档词多，如果不归一化，它天然得分更高。
> 我们用的是经典默认值 k1=1.2、b=0.75。

**Q：索引怎么保证和数据库一致？**

> 用"脏标记 + 懒重建"。文档增删时只打一个标记，下次检索时才真正重建索引。因为增删是低频、检索是高频，这样把成本挪到了不影响用户的地方。
> 重建时整体替换快照而不是原地修改，所以读路径不用加锁——这是写时复制的思路。

**Q：为什么不用 Elasticsearch？**

> 规模不匹配。ES 需要独立部署、集群运维、JVM 调优，而我们的片段只有几百条，一个几百行的 BM25 实现就够，查询延迟还更低。如果文档涨到十万级、需要复杂聚合查询，就该换 ES 或 Lucene。

---

# 五、RRF 融合：把两路结果合成一路

## ① 场景

现在有两路召回：向量给了 20 条，关键词给了 20 条。**怎么合成一个最终排名？**

直接相加是不行的——向量给的是余弦相似度（0~1 的小数），关键词给的是 BM25 分（可以是几十）。**量纲完全不同，相加毫无意义。**

## ② 代码

`retrieval/RetrievalService.java`：

```java
/**
 * RRF（Reciprocal Rank Fusion）融合两路结果。
 * 公式：score = Σ 1 / (k + 排名)，k 取 60。
 * 它只看排名不看分数，所以不用操心余弦相似度和 BM25 分量纲不同的问题——
 * 这也是 RRF 在工业界被广泛使用的原因。
 */
private List<RetrievedChunk> fuse(List<RetrievedChunk> first,
                                  List<RetrievedChunk> second,
                                  int topK) {
    int k = properties.getRetrieval().getRrfK();          // 60
    Map<String, Double> scores = new LinkedHashMap<>();
    Map<String, RetrievedChunk> byId = new LinkedHashMap<>();
    accumulate(scores, byId, first, k);
    accumulate(scores, byId, second, k);

    return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(entry -> byId.get(entry.getKey()))
            .toList();
}

private void accumulate(Map<String, Double> scores,
                        Map<String, RetrievedChunk> byId,
                        List<RetrievedChunk> hits,
                        int k) {
    for (int rank = 0; rank < hits.size(); rank++) {
        RetrievedChunk hit = hits.get(rank);
        // 只用到 rank，完全不用 hit.score()——这就是 RRF 不敏感于分数量纲的原因
        scores.merge(hit.embeddingId(), 1.0 / (k + rank + 1), Double::sum);
        byId.putIfAbsent(hit.embeddingId(), hit);
    }
}
```

## ③ 原理

**公式的含义**：`1 / (k + 排名)`。排名越靠前，得分越高，而且是**非线性衰减**——第 1 名和第 2 名的差距，大于第 10 名和第 11 名的差距。

**k 的作用**：k 越大，衰减越平缓（更"民主"）；k 越小，头部结果优势越明显。60 是原论文的经验值，也是一个被广泛验证的折中。

**最关键的性质：一个片段如果两路都召回了，分数自然叠加。** 比如某片段在向量里排第 3、在关键词里排第 1：

```
score = 1/(60+3) + 1/(60+1) = 0.01587 + 0.01639 = 0.03226
```

而只在向量里排第 1 的片段只有 `1/61 = 0.01639`。**两路共识的片段排名自然上升**，这正是融合想要的效果。

## ④ 边界

| 融合方式 | 适用 | 代价 |
|---|---|---|
| **RRF（当前方案）** | 多路召回、无法确定权重 | 丢弃了分数里的大小信息，只保留排名 |
| 加权求和 | 分数已经归一化、有权重依据 | 需要归一化和调权，两路分数分布不同时很难调 |
| 学习排序（LTR） | 有大量标注数据 | 需要训练数据，冷启动做不了 |

**RRF 的代价值得说清楚**：它丢掉了"分数的大小信息"。比如向量检索里第 1 名是 0.92、第 2 名是 0.31——差距巨大，但 RRF 只看排名，两者只差 `1/61 - 1/62`。**在分数区分度很重要的场景，应该考虑加权求和。**

## ⑤ 简历怎么写

> 采用 RRF（Reciprocal Rank Fusion）融合向量与关键词双路召回结果，规避两类分数（余弦相似度与 BM25）量纲不可比的问题，无需人工调权；双路共识片段因分数叠加自然获得更高排名。

## ⑥ 面试怎么答

**Q：两路召回结果怎么融合？**

> 用 RRF：`score = Σ 1/(k + 排名)`，k 取 60。它只看排名不看分数，所以天然解决了余弦相似度和 BM25 分量纲不同的问题，也不需要人工调权重。
> 一个片段如果两路都召回了，分数自然叠加，排名上升——这正是融合想要的效果。

**Q：为什么不直接加权求和？**

> 加权求和需要解决两个问题：分数归一化，以及权重的确定依据。两路的分数分布完全不同，归一化之后仍然未必可比。RRF 用排名替代分数，直接绕开了这两个问题。
> 代价是丢掉了分数的大小信息。如果某一路的分数区分度很重要，加权求和会更合适——这是个取舍。

**Q：RRF 的 k 为什么取 60？**

> 这是原论文的经验值。k 越大衰减越平缓，k 越小头部优势越明显。60 是被广泛验证的折中值。理论上它也是可调超参数，可以用评测集验证。

**Q：融合之后取多少条交给下一步？**

> 取 20 条交给重排。整体设计是"**检索要宽、喂给模型要精**"：两路各召回 20 条 → 融合后保留 20 条 → 重排后取前 6 条给模型。
> 检索宽是为了不漏，喂模型精是为了不让无关内容稀释注意力、也控制 token 成本。

---

# 六、重排：粗排精排的两级架构

## ① 场景

向量检索的精度有天花板。原因是它属于**双塔结构**：问题和文档**分别**编码成向量，然后只比较两个向量——**问题和文档之间从来没有交互过**，细节信息在编码时就已经丢了。

实测效果对比（问"部署需要什么环境"）：

| 内容 | 重排得分 |
|---|---|
| 服务器需要 JDK 17 和 Docker 24 以上版本 | **0.2050** |
| 部署时先执行 docker compose up -d | 0.1102 |
| 数据库使用的是 PostgreSQL 17 | 0.0514 |
| 论文第三章讨论了系统模型 | 0.0004 |
| 今天的天气不错 | 0.00002 |

**最相关和完全跑题的差了四个数量级**，区分度远好于向量相似度。

## ② 代码

`retrieval/RerankClient.java`：

```java
public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
    if (candidates.size() <= 1) return candidates;
    try {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getRerank().getModel());   // bge-reranker-v2-m3
        body.put("query", query);
        body.put("documents", candidates.stream().map(RetrievedChunk::text).toList());
        body.put("top_n", candidates.size());
        ...
        JsonNode results = objectMapper.readTree(response.body()).path("results");
        List<RetrievedChunk> ordered = new ArrayList<>(results.size());
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index >= 0 && index < candidates.size()) {
                // 把重排分数写回，页面显示的就是这个分
                ordered.add(candidates.get(index).withScore(item.path("relevance_score").asDouble(0)));
            }
        }
        return ordered.isEmpty() ? candidates : ordered;
    } catch (Exception e) {
        // 容错：重排是外部调用，它挂了不该让整个问答挂掉
        log.warn("[重排] 调用失败，本次跳过重排：{}", e.getMessage());
        return candidates;
    }
}
```

## ③ 原理

**双塔 vs 交叉编码**：

| | 双塔（向量检索） | 交叉编码（重排） |
|---|---|---|
| 编码方式 | 问题和文档**分别**编码 | 问题和文档**一起**送入模型 |
| 精度 | 有天花板（无交互） | 高得多（逐词比较） |
| 速度 | 快——文档向量可离线预计算 | 慢——每对组合都要跑一次模型 |
| 用途 | 全库粗排 | 小候选集精排 |

**为什么两级架构不是妥协，而是唯一可行方案**：假设有 10 万个片段，重排要为每个片段做一次联合编码，是 O(N) 次模型推理，延迟和成本都无法接受。双塔可以预计算文档向量，检索时只算一次问题向量，是近似最近邻查找。

```
全库（10 万片段）
  ↓ 向量召回（快、粗）
20 条候选
  ↓ 重排（慢、精）
6 条给模型
```

## ④ 边界

| 情况 | 是否用重排 |
|---|---|
| 候选集小（几十条）、对精度要求高 | 适合 |
| 全库直接重排 | 不可行，复杂度不允许 |
| 对延迟极敏感（如实时补全） | 要权衡，重排会增加几百毫秒 |
| 有 GPU 且预算充足 | 可以扩大候选集，进一步提升召回 |

**容错是必须的**：重排是一次外部调用，它挂了不该让整个问答挂掉。所以失败时原样返回候选，只记一条警告。**外部依赖必须容错**，这是工程基本功。

## ⑤ 简历怎么写

> 接入交叉编码重排模型构建「向量召回 → RRF 融合 → 重排精排」三级检索管线，将候选集从 20 条精排至 6 条供生成使用；重排调用失败时自动降级为原序返回，保证外部依赖故障不影响主链路可用性。

## ⑥ 面试怎么答

**Q：既然重排更准，为什么不只用重排？**

> 因为复杂度不允许。假设 10 万个片段，重排要为每个片段算一次"问题加文档"的联合编码，是 O(N) 次模型推理。双塔可以预计算文档向量，检索时只算一次问题向量。
> **所以两级架构不是妥协，是唯一可行的方案。**

**Q：重排带来的延迟增加多少？**

> 我们实测 20 条候选的重排调用在几百毫秒量级，相对生成阶段（1~2 秒）可接受。候选数增加延迟会线性增长，所以要控制在几十条以内。

**Q：重排分数能和向量相似度比较吗？**

> 不能。两者来自不同的模型、不同的训练目标，分数含义和分布都不一样。所以在管线里重排是**最后一步**——它输出的顺序就是最终顺序，不会再和前面的分数加权。

**Q：外部服务挂了怎么办？**

> 降级。重排失败时原样返回候选，只记一条警告日志，问答照常进行。同理，Redis 挂了就当作缓存未命中、限流放行。
> **原则是：能不因为外部依赖失败而中断核心链路，就不要中断。** 但如果是支付这类系统，就必须反过来——宁可拒绝服务也不能算错。

---

# 七、提示词工程与防幻觉

## ① 场景

知识库问答最怕两件事：**答错**和**编造**。而且编造还带着引用角标，看起来更可信、危害更大。

实测的两个极端情况：

- 问"作者邮箱是什么"→ 模型正确回答"根据现有资料无法确定"，**没有编造**
- 问"文章分为哪几个章节"→ 答得差，因为检索到的资料本身就残缺

前者说明防幻觉提示词有效，后者说明**提示词解决不了检索的问题**。

## ② 代码

系统提示词的五条规则（`agent/KbAgentService.java`）：

```java
private static final String SYSTEM_PROMPT = """
        你是企业知识库问答助手，只依据用户消息中的【参考资料】回答问题。

        必须遵守的规则：
        1. 只使用参考资料中的信息，不得引入外部知识，不得凭常识推测。
        2. 如果资料不足以回答，直接说明"根据现有资料无法确定"，并指出还缺什么信息。
        3. 每个结论后面用 [1]、[2] 这样的编号标注来源，编号必须与参考资料序号一致。
        4. 多条资料互相冲突时，指出冲突并分别标注来源，不要擅自选一个。
        5. 用简洁的中文回答，先给结论，再给依据，不要重复资料原文。
        """;
```

检索为空时的处理（这一行很容易漏）：

```java
if (chunks.isEmpty()) {
    prompt.append("（没有检索到任何相关资料）\n");
}
```

**为什么必须显式写出来**：如果检索为空时什么都不给，模型会以为自己在做普通问答，直接凭自身知识回答——**这时候提示词里的"只依据参考资料"反而被忽略了，因为根本没有参考资料可依据**。

## ③ 原理

**五条规则，每条解决一个具体问题**：

| 规则 | 解决的问题 | 实测 |
|---|---|---|
| 1 只用参考资料 | 幻觉（模型拿自己的知识补） | 配合规则 2 生效 |
| 2 资料不足就说无法确定 | 硬答（不知道也编一个） | 问邮箱时正确拒答 |
| 3 编号标注来源 | 无法追溯 | 页面上的 [1][2] 角标 |
| 4 冲突时指出冲突 | 多来源矛盾时擅自选一个 | 多文档场景才明显 |
| 5 先结论后依据 | 输出啰嗦、复述原文 | 答案更短更清爽 |

**完整的防幻觉应该是三层，我们只做了中间一层**：

| 层 | 做什么 | 我们的状态 |
|---|---|---|
| 检索层 | 保证召回的资料真的相关 | 部分（minScore + 混合检索 + 重排） |
| 生成层 | 提示词约束 | **有** |
| 后置校验层 | 程序检查引用是否真的支撑结论 | **完全没有** |

**为什么后置校验重要**：提示词是**请求，不是强制**——模型可以不遵守。而且它压得住"资料不足时的编造"，压不住"资料不相关时的强行关联"（资料就在那儿，模型为了完成任务会强行关联，产出的答案还带着引用，更难发现）。

## ④ 边界：一个容易被追问的细节

页面上的 `[1]` **没有存在任何地方**，它就是"检索结果列表的下标加一"，而且**被独立计算了两次**：拼提示词时一次，回前端时一次，靠"下标 + 1"这个约定对齐。

```java
// 拼提示词时
prompt.append('[').append(i + 1).append("] 文件：")...

// 回前端时（ChatController.toSourceRefs）
IntStream.range(0, chunks.size()).mapToObj(i -> SourceRef.of(i + 1, chunks.get(i)))
```

**好处**：没有共享状态，简单。
**代价**：一边改了另一边没改就会**静默出错**，不会有任何报错。

**隐患**：编号是"本次检索结果的下标"，同一个问题问两次，如果召回顺序变了，`[1]` 指向的片段可能不同。单轮问答没问题，但**加多轮对话记忆时，历史回答里的 `[1]` 和新检索的 `[1]` 含义就冲突了**——届时要么换成稳定 ID，要么每轮重新编号。

## ⑤ 简历怎么写

> 设计五条约束式系统提示词（只用资料、不足则拒答、编号引用、冲突提示、结论先行），实测可在资料不足时正确拒答而非编造；明确识别当前防幻觉仅覆盖提示词层，并规划后置引用校验作为下一步。

## ⑥ 面试怎么答

**Q：防幻觉你做了什么？**

> 提示词约束加引用溯源。要求模型只依据检索资料回答，资料不足就说"无法确定"，每条结论标注来源编号。
> 但我要说清楚：**这是软约束，属于提示词层面，模型可以不遵守**。完整方案应该有三层——检索层保证召回质量、生成层做提示词约束、后置校验层做一致性检查。我们现在只有中间一层，后置校验是明确的改进方向。

**Q：怎么判断幻觉？**

> 简单做法是检查答案里的引用编号是否都在检索结果范围内。更严格的做法是把答案拆成若干结论，逐条判断能否在对应片段里找到支撑，可以用另一个模型做裁判（LLM-as-a-judge）。指标上对应 RAGAS 的 Faithfulness。

**Q：提示词里为什么给资料编号？**

> 两个作用：一是让模型能引用，用户能追溯原文；二是当多条资料冲突时，模型可以明确指出"资料 [1] 和 [3] 说法不一致"，而不是自己选一个。**编号把"引用"变成了一种可验证的契约。**

**Q：如果检索到的资料本身是错的呢？**

> 模型会忠实地把错误复述出来，而且因为有引用，看起来更可信。**这是 RAG 的本质局限：它不能修复源数据的错误。**
> 我们在自己项目上就踩到了——评测集生成时发现文档里的方案已经变了但文档没更新，导致生成了一道错误题目。这说明知识库需要文档生命周期管理，光有检索不够。

**Q：怎么处理多轮对话？**

> 目前是单轮。加多轮要考虑三件事：一是历史消息怎么拼进提示词（token 会快速膨胀），二是指代消解（"它"指的是什么），三是刚才说的**引用编号冲突**问题。常见做法是只保留最近几轮 + 对历史做摘要压缩。

---

# 八、流式输出：SSE 与首字延迟

## ① 场景

如果等模型生成完再一次性返回，用户要盯着空白界面等 1~2 秒，内容长了更久。

**流式输出让感知延迟从"总耗时"变成"首字延迟"。**

实测数据（三个问题）：

| 问题 | 首字延迟 | 总耗时 |
|---|---|---|
| 论文作者是谁 | 1.006s | 2.063s |
| 作者邮箱是什么 | 0.752s | 1.330s |
| 文章分为哪几个章节 | 0.547s | 1.160s |

**一个反直觉的发现**：答得最差的那个问题（章节划分）**反而最快**。原因是拒绝回答或答得很短，生成时间自然短。

**所以延迟数据必须和答案质量一起看**——看到"秒回但答非所问"，要意识到那可能是**检索没找到东西**，而不是模型很高效。

## ② 代码

**服务端**（`web/ChatController.java`）：

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestParam("question") String question, ...) {
    SseEmitter emitter = new SseEmitter(0L);          // 0 = 不设超时
    long startedAt = System.currentTimeMillis();
    try {
        AgentContext context = agentService.prepare(question, topK);
        emit(emitter, "sources", toSourceRefs(context));   // ① 先推引用来源

        agentService.stream(context, new StreamingChatResponseHandler() {
            private Long firstTokenAt = null;

            public void onPartialResponse(String partialResponse) {   // ② 模型每吐一小段
                if (firstTokenAt == null) {
                    firstTokenAt = System.currentTimeMillis();
                    log.info("[问答] 首字延迟: {} ms", firstTokenAt - startedAt);
                }
                emit(emitter, "delta", partialResponse);
            }
            public void onCompleteResponse(ChatResponse response) {   // ③ 结束
                emit(emitter, "done", Map.of("totalMs", ...));
                emitter.complete();
            }
            public void onError(Throwable error) { ... }
        });
    } catch (Exception e) {
        emitter.completeWithError(e);
    }
    return emitter;
}

private void emit(SseEmitter emitter, String event, Object data) {
    try {
        emitter.send(SseEmitter.event().name(event).data(data));
    } catch (Exception e) {
        // 客户端断开是常态不是异常，只记 debug
        log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
    }
}
```

**前端不用 EventSource，自己解析**（`frontend/src/views/AssistantView.vue`）：

```js
// 用 fetch 自己解析 SSE，而不是 EventSource。
// 原因：EventSource 拿不到 HTTP 状态码，401（口令错误）和 429（限流）
// 都只会变成一句"连接错误"，用户不知道发生了什么。
const res = await fetch('/api/chat/stream?question=' + encodeURIComponent(text), { headers })
if (res.status === 429) throw new Error('提问太频繁了，请稍等一会儿再试')
if (res.status === 401) throw new Error('需要访问口令')

const reader = res.body.getReader()
const decoder = new TextDecoder()
let buffer = ''
while (true) {
  const { done, value } = await reader.read()
  if (done) break
  buffer += decoder.decode(value, { stream: true })
  let boundary
  while ((boundary = buffer.indexOf('\n\n')) >= 0) {     // SSE 帧以空行分隔
    const frame = buffer.slice(0, boundary)
    buffer = buffer.slice(boundary + 2)
    if (frame.trim()) handleFrame(frame, markFirstToken)
  }
}
```

## ③ 原理

**为什么用 SSE 不用 WebSocket**：SSE 是**单向**的（服务端到浏览器），我们的场景只需要服务端推、浏览器收。WebSocket 是双向的，需要协议升级握手、心跳和重连处理——**对这个场景是过度设计**。

SSE 还有个优势：它是基于 HTTP 的普通请求，**穿代理和防火墙比 WebSocket 容易**。

**为什么要先推 `sources`**：检索只要 200 毫秒，模型生成要一到两秒。先推来源，用户能立刻看到"系统找到了哪些资料"，**把等待时间用有用的信息填满**。这是产品体验设计，不是技术必需。

## ④ 边界

| 场景 | 选择 |
|---|---|
| 只需要服务端推送（流式输出、进度、通知） | **SSE** |
| 需要双向实时（聊天室、协同编辑、游戏） | WebSocket |
| 需要二进制流（文件传输、音视频） | WebSocket 或专门的流协议 |

## ⑤ 简历怎么写

> 基于 SSE 实现流式问答，将用户感知延迟从总耗时（1~2 秒）降至首字延迟（实测 0.55~1.01 秒）；前端采用 fetch + ReadableStream 手动解析 SSE 帧以获取 HTTP 状态码，实现对 401/429 的准确提示。

## ⑥ 面试怎么答

**Q：SSE 和 WebSocket 怎么选？**

> 看是否需要客户端到服务端的实时通信。只需要服务端推送（流式输出、进度更新、消息通知）→ SSE，简单且穿代理容易；需要双向（聊天室、协同编辑、游戏）→ WebSocket。

**Q：前端为什么不用 EventSource？**

> 因为 `EventSource` **读不到 HTTP 状态码**。被限流（429）和口令错误（401）都会变成笼统的"连接错误"，用户不知道发生了什么。改用 `fetch` + `ReadableStream` 手动解析 SSE 帧后，就能读到状态码和错误消息。

**Q：客户端断开连接了怎么办？**

> 服务端要能感知并停止生成，否则会白白消耗 token。我们的实现里，向已断开的连接写数据会抛异常，而这个异常被吞掉并记 debug 日志——**客户端断开是常态不是异常**，用户切标签页、关浏览器都会断。

**Q：首字延迟和总耗时哪个更重要？**

> 首字延迟，因为那是用户感知的"快不快"。实测我们三个问题首字在 0.55~1.01 秒，总耗时 1.2~2.1 秒。
> 但要注意：**延迟必须和答案质量一起看**。我们有个问题答得最差却最快（0.547s），因为答得短或直接拒答。秒回但答非所问，说明检索没找到东西。

---

# 九、评测方法论：消融实验与评测集构建

## ① 场景

"效果不错""优化了很多"这类说法在面试里等于没说。**没有数字就没有优化。**

更现实的问题是：**冷启动阶段没有真实用户日志**，评测集从哪来？

## ② 代码

**第一步，反向生成评测集**（`eval/EvalSetGenerator.java`）。核心是让模型「看着一段文档出题」：

```java
private static final String PROMPT_TEMPLATE = """
        你是一名测试工程师，正在为一个检索系统构建评测集。
        下面是一段技术文档的内容。请你完成两件事：
        1. 提出一个「真实用户可能会问」的问题，答案必须完全来自这段内容
        2. 从这段内容中挑出 2 到 4 个关键词，它们必须原样出现在内容里
        要求：
        - 关键词要短，每个不超过 10 个字，优先用单个词或标识符
        - 只针对文档里的技术内容提问，不要问"这份文档讲了什么"这类关于文档本身的问题
        """;
```

**但光靠提示词不够——提示词是请求不是强制**，所以加了三道程序硬校验：

```java
// 校验一：关键词必须在原文里找得到，否则说明模型在编
// 校验二：关键词不能太长，否则会跨片段被切断、永远匹配不上
List<String> keywords = rawKeywords.stream()
        .filter(keyword -> !keyword.isEmpty()
                && keyword.length() <= MAX_KEYWORD_CHARS
                && chunk.text().contains(keyword))
        .distinct()
        .toList();

// 校验三：题目去重。用「包含度」而不是 Jaccard——
// 当一条是另一条的子集时（"为什么先按章节切" vs "为什么先按章节切再按长度切"），
// Jaccard 只有 0.48 会漏判，包含度是 1.0 能抓住
private double containment(Set<String> a, Set<String> b) {
    Set<String> intersection = new HashSet<>(a);
    intersection.retainAll(b);
    return (double) intersection.size() / Math.min(a.size(), b.size());
}
```

**第二步，跑消融实验**（`eval/EvalRunner.java`）：

```java
public List<Map<String, Object>> run(...) {
    for (RetrievalOptions options : RetrievalOptions.ablationSet()) {
        results.add(evaluate(set, options, topK));
    }
}

// 四种配置，量化每一层的贡献
public static final RetrievalOptions BASELINE = new RetrievalOptions(false, false);  // 纯向量
public static final RetrievalOptions KEYWORD  = new RetrievalOptions(true,  false);  // + 关键词 + RRF
public static final RetrievalOptions RERANK   = new RetrievalOptions(false, true);   // + 重排
public static final RetrievalOptions FULL     = new RetrievalOptions(true,  true);   // 完整
```

评分不看模型，**只看检索结果是否覆盖了标注的关键词**：

```java
String context = hits.stream().map(RetrievedChunk::text).collect(Collectors.joining("\n"));
int hitKeywords = 0;
for (String keyword : item.keywords()) {
    if (context.contains(keyword)) hitKeywords++;
}
coveredKeywords += hitKeywords;
totalKeywords += item.keywords().size();
if (hitKeywords == item.keywords().size()) fullyHitQuestions++;
```

## ③ 原理

**评测数据只有四个来源，业界的共识是**：

| 来源 | 真实性 | 什么时候用 |
|---|---|---|
| 真实用户日志 | 最高 | 产品上线后 |
| 人工标注（黄金集） | 高 | 建立基准 |
| **大模型反向生成 + 人工审核** | 中 | **冷启动的主流做法** |
| 公开基准（MS MARCO/BEIR） | 与你的领域无关 | **只能用于模型选型** |

**六条通识**：评测集必须来自自己的领域；检索层和生成层要分开标注；LLM 生成 + 人工审核是主流；规模 30~50 条够做 A/B、100~500 条做正式评测；**必须留 hold-out 防止过拟合评测集**；评测集是活的，上线后从 bad case 持续补充。

**为什么用关键词覆盖率，不人工标注"正确片段"**：降低标注成本。人工标注"该召回哪几段"要逐条查看上百个片段；标注关键词只需要读完文档就能写，还能程序自动校验。

**消融实验的价值**：跑同一套评测集，就能分别量化出"关键词召回贡献了多少""重排贡献了多少"。**"我做了消融实验"这句话在面试里的分量，远远超过"我做了混合检索"。**

实测规模：**56 条评测集、142 个关键词、平均每题 2.5 个**。

## ④ 边界：这套评测的局限

| 局限 | 说明 | 改进方向 |
|---|---|---|
| 关键词出现 ≠ 答案正确 | 覆盖只是**近似指标** | 加 LLM-as-a-judge 评估忠实度 |
| 只测了检索层 | 没测生成质量 | 补 Faithfulness、答案相关性 |
| 56 条规模偏小 | 一题正负会让总分跳 2 个点 | 扩到 100 条以上 |
| 评测集自带偏差 | 反向生成的题偏"文档中心" | 混入手工题（我们已混了 25 条） |
| 没有 hold-out | 调参和汇报用同一套 | 留出 15~20% 不参与调参 |

## ⑤ 简历怎么写

> 构建 56 条领域评测集（手工出题 + 大模型反向生成，含三道程序化校验与去重），并以消融实验量化混合检索与重排各自的收益；指标覆盖 Recall@K 与按题型分组的 HitRate，可定位每项技术在哪类问题上生效。

## ⑥ 面试怎么答

**Q：你的评测集是怎么构建的？**

> 混合构建。没有真实用户日志，所以一部分我按真实用户会问的方式手工出题，另一部分用大模型从文档片段反向生成、再人工审核。生成时加了三道程序校验：关键词必须能在原文找到、长度受限、题目去重。
> 我还刻意覆盖了单跳和多跳两类问题，其中多跳问题（比如"文档一共有几份"）在纯向量检索下大概率答不对——**这是刻意留的，用来暴露系统边界，不是为了刷高分**。

**Q：怎么保证评测结果可信？**

> 三点：一是有基线，所有对比基于同一套评测集和同一份文档；二是**分工况统计**，按题型分组，能看出某个技术到底在哪类问题上起作用，而不是只看一个总分；三是**留 hold-out**，最终汇报用没调过参的部分。

**Q：为什么不用 MS MARCO 这类公开数据集？**

> 领域不匹配。公开数据集用来**选型**——比如挑嵌入模型时看 BEIR 或 MTEB 的排名。但要证明自己的系统有效，必须用自己的领域数据，因为提问方式、文档结构、用户意图都不一样。
> **知道通用基准在哪一步有用、在哪一步没用，比会用更重要。**

**Q：为什么用 0.35 这个 minScore，有实验依据吗？**

> 没有，是经验值。正确做法是在评测集上扫描 0.2 到 0.6，找召回率和准确率的平衡点。这正是我建评测集的动机之一——**没有评测集，所有参数都是拍脑袋**。

---

# 十、成本控制与模型参数

## ① 场景

大模型是按 token 计费的。而且有个容易被忽略的点：**思维链也算输出 token**。

实测同一个问题（"9.11 和 9.8 哪个大"）：

| 配置 | 输出 token | 思维链 | 回答 |
|---|---|---|---|
| 关闭思考模式 | 5 | 无 | 9.11 大（**错**） |
| 默认（开启） | 74（含 68 推理） | 114 字符 | 9.8 大（对） |

**输出 token 差约 15 倍。**

## ② 代码

DeepSeek 的思考模式开关不在标准协议里，要通过 `customParameters` 塞到请求体顶层（`config/LangChainConfig.java`）：

```java
private void applyThinkingMode(Object builder, String thinking) {
    Map<String, Object> custom = new LinkedHashMap<>();
    // DeepSeek 扩展字段：{"thinking":{"type":"disabled"}}
    custom.put("thinking", Map.of("type", thinking.trim().toLowerCase()));
    if (builder instanceof OpenAiChatModel.OpenAiChatModelBuilder chatBuilder) {
        chatBuilder.customParameters(custom);
    } else if (builder instanceof OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder streamBuilder) {
        streamBuilder.customParameters(custom);
    }
}
```

控制提示词长度也很关键：

```java
private static final int MAX_CHUNK_CHARS_IN_PROMPT = 1200;   // 单个片段截断
```

## ③ 原理

**三层成本优化**：

| 层 | 做法 | 收益 |
|---|---|---|
| 模型选型 | deepseek-flash 而非 v4-pro | 输出价格差约 3 倍 |
| 参数 | 关闭思考模式 | 输出 token 差约 15 倍 |
| 缓存 | 热点问题向量不重复计算 | 省掉每次 200ms 的调用 |

**但比优化更重要的是先算清账**：一次问答约 5500 token 输入（6 个片段各最多 1200 字 + 系统提示词），按 deepseek-flash 空闲时段算**不到一分钱**。在这个量级下，与其纠结成本，不如把精力放在答案质量上。

**成本优化要有前提——先知道成本结构，再决定优化哪里。**

## ④ 边界：什么时候该把思考模式打开

| 问题类型 | 思考模式 |
|---|---|
| "照资料回答"（我们的场景） | **关闭**——不需要多步推理 |
| 跨文档对比、数值计算、逻辑推导 | 打开 |
| 需要严格遵循指令格式 | 打开（推理模式对指令遵循更好） |

**关掉思考模式的真实代价**（实测）：数学陷阱题会答错。但在引用格式和防幻觉这两个业务指标上**完全没有损失**。

**另一个必须知道的点**：思维链走的是 `reasoning_content` 字段，**不进入 `content`**。而我们的流式代码只读 `content`——所以开着思考模式时，用户会**盯着空白界面等十几秒**，然后答案整段蹦出来。这不只是成本问题，是**产品体验问题**。

## ⑤ 简历怎么写

> 通过实测对比模型参数对成本与体验的影响：关闭思考模式后输出 token 降低约 15 倍，同时避免了思维链占用非流式字段导致的首屏空白；结合模型选型与热点缓存形成三层成本控制。

## ⑥ 面试怎么答

**Q：怎么控制大模型成本？**

> 三个层面：模型选型（知识库问答不需要最强模型）、参数（关闭思考模式，输出 token 差 15 倍）、缓存（热点问题向量不重复计算）。
> 但更重要的是**先算清账**：一次问答约 5500 token 输入，按当前价格不到一分钱。这个量级下与其纠结成本，不如把精力放在答案质量上。

**Q：关闭思考模式有什么代价？**

> 损失多步推理能力。我实测过一道数学陷阱题，关闭时答错、开启时答对。但在引用格式和防幻觉这两个真正的业务指标上完全没有损失。
> **取舍的依据是"这个场景需要什么能力"，不是"哪个模型更强"。**

**Q：temperature 设多少？**

> 我们设 0.2，偏低。知识库问答要求"照着资料说"，温度高会让模型更"有创意"，也就是更容易脱离资料发挥。**高温度是 RAG 的反面。**
> 另外要注意：DeepSeek 的思考模式下 `temperature`、`presence_penalty`、`frequency_penalty` 会被服务端忽略，设置无效。

**Q：token 和成本怎么估算？**

> 中文一个字大约 0.6~1 个 token。我们一次问答约 5500 token 输入、400 token 输出，主要成本在输入的检索片段上——这也是为什么要把单个片段截断到 1200 字、并且只给模型 6 条。

---

# 附：这份手册怎么用

**学习顺序建议**：一（RAG 全貌）→ 二（切分）→ 三（向量检索）→ 四（关键词）→ 五（融合）→ 六（重排）→ 七（提示词）→ 八（流式）→ 九（评测）→ 十（成本）。

前三节建立整体认知，中间四节是检索优化的核心，最后三节是工程与验证。

**每个技术点走三步**：

1. **读代码**——打开对应文件，对着手册里的片段看真实实现
2. **讲原理**——遮住"原理"，自己讲一遍"为什么这样做能解决问题"
3. **答面试**——遮住答案过一遍问答，答不上来的地方就是盲区

**和前端的配合**：这份手册讲的是"为什么"，`docs/10-系统设计说明.md` 讲的是"整体怎么设计"，`docs/03-代码导读.md` 讲的是"代码怎么读"。三份配合看，能建立起完整的认知。
