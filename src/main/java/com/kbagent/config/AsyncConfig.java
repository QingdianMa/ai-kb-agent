package com.kbagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步入库用的线程池。
 *
 * 三个参数为什么这么定：
 *   corePoolSize=2 —— 入库是 IO 密集型（时间都花在等嵌入接口返回），不需要很多线程。
 *                      线程开太多反而会因为并发请求过多被服务端限流。
 *   maxPoolSize=4  —— 队列满了之后临时扩容的上限。
 *   queueCapacity=50 —— 缓冲队列长度。
 *
 * 拒绝策略用 AbortPolicy（抛异常）而不是 CallerRunsPolicy（让调用线程自己跑）：
 * 后者会让 Tomcat 的请求线程去执行 6 秒的入库任务，把 Web 线程池也拖垮，
 * 一个组件的过载会扩散成整个服务不可用。宁可明确拒绝，也不要雪崩。
 */
@Configuration
public class AsyncConfig {

    public static final String DOCUMENT_EXECUTOR = "documentExecutor";

    @Bean(name = DOCUMENT_EXECUTOR)
    public ThreadPoolTaskExecutor documentExecutor(AppProperties properties) {
        AppProperties.Ingest config = properties.getIngest();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCorePoolSize());
        executor.setMaxPoolSize(config.getMaxPoolSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        executor.setThreadNamePrefix("doc-ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
