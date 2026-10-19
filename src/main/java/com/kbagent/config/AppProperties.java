package com.kbagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 所有外部依赖（模型服务、向量库）都通过配置注入，方便换供应商和做对比实验。
 */
@ConfigurationProperties(prefix = "kb")
public class AppProperties {

    private Chat chat = new Chat();
    private Embedding embedding = new Embedding();
    private Retrieval retrieval = new Retrieval();
    private Rerank rerank = new Rerank();
    private Storage storage = new Storage();
    private Security security = new Security();
    private Eval eval = new Eval();
    private Cache cache = new Cache();
    private RateLimit rateLimit = new RateLimit();
    private Ingest ingest = new Ingest();

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public Retrieval getRetrieval() {
        return retrieval;
    }

    public void setRetrieval(Retrieval retrieval) {
        this.retrieval = retrieval;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public void setRerank(Rerank rerank) {
        this.rerank = rerank;
    }

    /** 重排模型配置。复用向量模型的地址和密钥（同一家服务），只换模型名。 */
    public static class Rerank {
        private String model = "BAAI/bge-reranker-v2-m3";
        private Integer timeoutSeconds = 30;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Eval getEval() {
        return eval;
    }

    public void setEval(Eval eval) {
        this.eval = eval;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Ingest getIngest() {
        return ingest;
    }

    public void setIngest(Ingest ingest) {
        this.ingest = ingest;
    }

    /** 异步入库的线程池参数。 */
    public static class Ingest {
        /**
         * 知识库语料目录。
         * 注意不要指向 docs/ —— 那里是开发文档，包含面试策略、服务器信息等内部内容，
         * 入库之后会被访客检索到。
         */
        private String sourceDir = "knowledge";
        private Integer corePoolSize = 2;
        private Integer maxPoolSize = 4;
        private Integer queueCapacity = 50;

        public String getSourceDir() {
            return sourceDir;
        }

        public void setSourceDir(String sourceDir) {
            this.sourceDir = sourceDir;
        }

        public Integer getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(Integer corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public Integer getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(Integer maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public Integer getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(Integer queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    /** 接口限流配置。 */
    public static class RateLimit {
        private Boolean enabled = true;
        /** 时间窗口（秒） */
        private Integer windowSeconds = 60;
        /** 窗口内允许的提问次数 */
        private Integer limit = 20;
        /** 是否信任 X-Forwarded-For。只有部署在可信代理后面才能打开 */
        private Boolean trustForwardedHeader = false;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(Integer windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }

        public Boolean getTrustForwardedHeader() {
            return trustForwardedHeader;
        }

        public void setTrustForwardedHeader(Boolean trustForwardedHeader) {
            this.trustForwardedHeader = trustForwardedHeader;
        }
    }

    /** 缓存配置。 */
    public static class Cache {
        /** 是否启用问题向量缓存 */
        private Boolean enabled = true;
        /** 缓存有效期（小时），默认 7 天 */
        private Integer ttlHours = 168;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getTtlHours() {
            return ttlHours;
        }

        public void setTtlHours(Integer ttlHours) {
            this.ttlHours = ttlHours;
        }
    }

    /** 评测相关配置。 */
    public static class Eval {
        /** 生成结果的输出路径 */
        private String outputPath = "eval/qa-set-generated.json";
        /** 一次生成多少条候选题目 */
        private Integer generateLimit = 40;

        public String getOutputPath() {
            return outputPath;
        }

        public void setOutputPath(String outputPath) {
            this.outputPath = outputPath;
        }

        public Integer getGenerateLimit() {
            return generateLimit;
        }

        public void setGenerateLimit(Integer generateLimit) {
            this.generateLimit = generateLimit;
        }
    }

    /** 访问控制。部署到公网必须设置口令，否则任何人都能白嫖接口。 */
    public static class Security {
        /** 访问口令。留空表示不启用访问控制（本地开发用）。 */
        private String accessToken = "";
        /** 管理员口令。后台的写操作必须带它，上线后务必设置。 */
        private String adminToken = "";

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public String getAdminToken() {
            return adminToken;
        }

        public void setAdminToken(String adminToken) {
            this.adminToken = adminToken;
        }
    }

    /** 对话模型配置（DeepSeek，OpenAI 兼容协议）。 */
    public static class Chat {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Double temperature = 0.2;
        private Integer timeoutSeconds = 120;
        /**
         * DeepSeek 的思考模式默认开启且强度为 high，回答前会先输出一大段思维链，
         * 既慢又按输出 token 计费。知识库问答是"照资料答"，用不上它，默认关掉。
         * 可选值：disabled / enabled
         */
        private String thinking = "disabled";
        private boolean logRequests = false;
        private boolean logResponses = false;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getThinking() {
            return thinking;
        }

        public void setThinking(String thinking) {
            this.thinking = thinking;
        }

        public boolean isLogRequests() {
            return logRequests;
        }

        public void setLogRequests(boolean logRequests) {
            this.logRequests = logRequests;
        }

        public boolean isLogResponses() {
            return logResponses;
        }

        public void setLogResponses(boolean logResponses) {
            this.logResponses = logResponses;
        }
    }

    /** 向量模型配置（DeepSeek 不提供 embedding 接口，需单独配置）。 */
    public static class Embedding {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Integer dimension = 1024;
        private Integer timeoutSeconds = 60;
        /**
         * 是否在请求体里带 dimensions 参数。
         * OpenAI 的 text-embedding-3 系列需要它来裁剪输出维度；
         * 硅基流动、百炼这类 OpenAI 兼容服务不认这个字段，带上会直接报参数错误，所以默认关闭。
         */
        private boolean sendDimensionParameter = false;
        /**
         * 一次 HTTP 请求里塞多少个片段。
         * 整份文档一次性发过去容易被服务端限流或超时，分批更稳。
         */
        private Integer batchSize = 16;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Integer getDimension() {
            return dimension;
        }

        public void setDimension(Integer dimension) {
            this.dimension = dimension;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isSendDimensionParameter() {
            return sendDimensionParameter;
        }

        public void setSendDimensionParameter(boolean sendDimensionParameter) {
            this.sendDimensionParameter = sendDimensionParameter;
        }

        public Integer getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(Integer batchSize) {
            this.batchSize = batchSize;
        }
    }

    /** 召回参数，后面做 A/B 实验时主要改这里。 */
    public static class Retrieval {
        private Integer maxResults = 6;
        private Double minScore = 0.35;
        /** 向量通道召回多少条候选 */
        private Integer vectorTopK = 20;
        /** 关键词通道召回多少条候选 */
        private Integer keywordTopK = 20;
        /** 融合后保留多少条候选，交给重排 */
        private Integer fusionTopK = 20;
        /** RRF 公式里的平滑常数，取经典默认值 */
        private Integer rrfK = 60;
        /** 是否启用关键词通道 */
        private Boolean keywordEnabled = true;
        /** 是否启用重排 */
        private Boolean rerankEnabled = true;

        public Integer getMaxResults() {
            return maxResults;
        }

        public void setMaxResults(Integer maxResults) {
            this.maxResults = maxResults;
        }

        public Double getMinScore() {
            return minScore;
        }

        public void setMinScore(Double minScore) {
            this.minScore = minScore;
        }

        public Integer getVectorTopK() {
            return vectorTopK;
        }

        public void setVectorTopK(Integer vectorTopK) {
            this.vectorTopK = vectorTopK;
        }

        public Integer getKeywordTopK() {
            return keywordTopK;
        }

        public void setKeywordTopK(Integer keywordTopK) {
            this.keywordTopK = keywordTopK;
        }

        public Integer getFusionTopK() {
            return fusionTopK;
        }

        public void setFusionTopK(Integer fusionTopK) {
            this.fusionTopK = fusionTopK;
        }

        public Integer getRrfK() {
            return rrfK;
        }

        public void setRrfK(Integer rrfK) {
            this.rrfK = rrfK;
        }

        public Boolean getKeywordEnabled() {
            return keywordEnabled;
        }

        public void setKeywordEnabled(Boolean keywordEnabled) {
            this.keywordEnabled = keywordEnabled;
        }

        public Boolean getRerankEnabled() {
            return rerankEnabled;
        }

        public void setRerankEnabled(Boolean rerankEnabled) {
            this.rerankEnabled = rerankEnabled;
        }
    }

    /** 向量库连接信息。 */
    public static class Storage {
        private String host;
        private Integer port = 5432;
        private String database;
        private String user;
        private String password;
        private String vectorTable = "kb_embedding";
        /** 上传文件的存储目录 */
        private String uploadDir = "data/uploads";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getVectorTable() {
            return vectorTable;
        }

        public void setVectorTable(String vectorTable) {
            this.vectorTable = vectorTable;
        }

        public String getUploadDir() {
            return uploadDir;
        }

        public void setUploadDir(String uploadDir) {
            this.uploadDir = uploadDir;
        }
    }
}
