package com.kbagent.config;

import com.kbagent.infra.FileStorage;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把上传目录映射成静态资源路径。
 * 上传后的文件通过 /uploads/xxx.jpg 访问，但不经过 jar 打包，
 * 所以重新部署应用不会覆盖用户上传的图片。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FileStorage fileStorage;

    public WebConfig(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(fileStorage.root().toUri().toString());
    }
}
