import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 一个不依赖 Spring 的最小连通性检查。
 * <p>
 * 用法：
 *   java tools/SmokeTest.java                                        # 只检查 JDK HttpClient 能否创建
 *   java tools/SmokeTest.java &lt;baseUrl&gt; &lt;apiKey&gt; &lt;model&gt;             # 测对话接口 /chat/completions
 *   java tools/SmokeTest.java &lt;baseUrl&gt; &lt;apiKey&gt; &lt;model&gt; embedding   # 测向量接口 /embeddings
 */
public class SmokeTest {

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        System.out.println("[1/2] JDK HttpClient 创建成功");

        if (args.length < 3) {
            System.out.println("[2/2] 未提供 baseUrl / apiKey / model，跳过真实请求");
            return;
        }

        String baseUrl = args[0].replaceAll("/+$", "");
        String apiKey = args[1];
        String model = args[2];
        boolean embeddingMode = args.length > 3 && args[3].equalsIgnoreCase("embedding");

        String path = embeddingMode ? "/embeddings" : "/chat/completions";
        String body = embeddingMode
                ? """
                {"model":"%s","input":"这是一条连通性测试文本"}
                """.formatted(model)
                : """
                {"model":"%s","messages":[{"role":"user","content":"ping"}],"max_tokens":8}
                """.formatted(model);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("[2/2] " + path + " -> HTTP " + response.statusCode());

        if (embeddingMode && response.statusCode() == 200) {
            // 向量是一千多个浮点数，全打印出来没法看，这里只做摘要
            System.out.println("返回体大小: " + response.body().length() + " 字符");
            System.out.println("包含 embedding 字段: " + response.body().contains("\"embedding\""));
            System.out.println("片段预览: "
                    + response.body().substring(0, Math.min(200, response.body().length())) + " ...");
        } else {
            System.out.println(response.body());
        }
    }
}
