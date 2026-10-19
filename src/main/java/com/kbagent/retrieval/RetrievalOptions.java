package com.kbagent.retrieval;

/**
 * 检索管线的开关组合，专门为消融实验设计。
 * 四种组合跑同一套评测集，就能分别量化出"关键词召回贡献了多少""重排贡献了多少"。
 * 这比笼统地说"我做了混合检索"有说服力得多。
 */
public record RetrievalOptions(boolean keywordEnabled, boolean rerankEnabled) {

    public static final RetrievalOptions BASELINE = new RetrievalOptions(false, false);
    public static final RetrievalOptions KEYWORD = new RetrievalOptions(true, false);
    public static final RetrievalOptions RERANK = new RetrievalOptions(false, true);
    public static final RetrievalOptions FULL = new RetrievalOptions(true, true);

    public static RetrievalOptions[] ablationSet() {
        return new RetrievalOptions[]{BASELINE, KEYWORD, RERANK, FULL};
    }

    public String label() {
        if (!keywordEnabled && !rerankEnabled) {
            return "A 纯向量（基线）";
        }
        if (keywordEnabled && !rerankEnabled) {
            return "B 向量 + 关键词 + RRF";
        }
        if (rerankEnabled) {
            return keywordEnabled ? "D 完整（混合 + 重排）" : "C 向量 + 重排";
        }
        return "未知组合";
    }
}
