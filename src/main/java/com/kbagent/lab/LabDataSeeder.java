package com.kbagent.lab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时写入站点的示例内容。
 *
 * 这里全部是**演示数据**，人名、邮箱、论文都是虚构的，方便你 clone 下来直接看到效果。
 * 换成真实内容有两条路：
 *   1. 启动后进 /#/admin 用管理后台改（推荐，改完永久生效）；
 *   2. 直接改本文件的初始值，再清空数据库重启。
 *
 * 为什么用 ApplicationRunner 而不是 @PostConstruct：
 * 建表脚本在容器启动过程中执行，@PostConstruct 有可能跑在建表之前而失败。
 * ApplicationRunner 在整个容器就绪后执行，顺序是安全的。
 *
 * 只在仓库为空时写入，所以重启不会覆盖你在后台改过的内容。
 */
@Component
public class LabDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LabDataSeeder.class);

    private final LabRepository repository;

    public LabDataSeeder(LabRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!repository.listMembers().isEmpty() || !repository.loadContent().isEmpty()) {
            return;
        }
        seedContent();
        seedMembers();
        seedPublications();
        log.info("[门户] 仓库为空，已写入示例内容。后续可在管理后台直接修改。");
    }

    private void seedContent() {
        repository.saveContent("home.title", "示例实验室知识库");
        repository.saveContent("home.subtitle", "示例大学计算机学院");
        repository.saveContent("home.intro",
                "本实验室围绕群智感知、边缘计算、服务计算与智能化软件开展研究，"
                        + "面向城市级感知数据的采集、任务分配与优化问题。"
                        + "团队主持国家自然科学基金、省自然科学基金等课题多项，"
                        + "在 IEEE TMC、TSC、IoT Journal 等期刊和会议发表论文。"
                        + "（以上为演示数据，可在管理后台替换为真实内容）");
        repository.saveContent("research.intro",
                "主要研究方向：群智感知、边缘计算、服务计算、智能化软件、数据挖掘。");
        repository.saveContent("contact.intro",
                "欢迎对相关研究方向感兴趣的同学联系。邮箱：contact@example.com");

        // 首页的指标、方向、获奖、项目等都用"一行一条、竖线分隔"的文本存，
        // 这样后台一个普通文本框就能改，不用为每种列表单独加字段
        repository.saveContent("home.stats", """
                60|学术论文（篇）
                20|CCF A 类 / 一区
                1500|论文总引用（次）
                8|主持科研课题（项）
                6|授权发明专利（件）
                2|ESI 高被引论文（篇）""");
        repository.saveContent("research.directions", """
                群智感知|利用大量移动设备与车载传感器协同完成城市级感知任务，核心是任务分配与激励机制设计
                边缘计算|把计算与存储下沉到网络边缘，降低时延并缓解骨干网压力
                服务计算|面向服务的软件体系结构与服务组合优化，关注服务发现与质量管理
                智能化软件|把人工智能能力融入软件系统，研究智能化设计、自适应机制与智能决策
                数据挖掘|从大规模异构数据中提取模式与知识，支撑决策与预测""");
        repository.saveContent("home.awards", """
                2025|省级科技进步奖 一等奖
                2023|省级自然科学奖 一等奖
                2022|学会青年人才奖
                2021|省级教学成果奖 二等奖""");
        repository.saveContent("home.projects", """
                2026|国家自然科学基金面上项目：面向多源数据的任务分配机制研究
                2024|省自然科学基金青年项目：边缘环境下的协同计算方法研究
                2022|校企合作项目：行业知识库问答系统""");
        repository.saveContent("home.book", "《示例专著：多源数据协同计算》，示例作者 著，示例出版社");
        repository.saveContent("home.patents", "授权发明专利 6 件，已转化 1 件；制定团体标准 1 项。");
        // 留空表示不显示：换成你自己的学术主页 / 备案号即可
        repository.saveContent("publications.scholarUrl", "");
        repository.saveContent("footer.icp", "");
        repository.saveContent("footer.police", "");
    }

    private void seedMembers() {
        repository.saveMember(new LabMember(
                null,
                "张三",
                "教授",
                "博士生导师 · 计算机学院院长",
                null,
                "示例成员：长期从事群智感知与边缘计算方向的研究，主持国家自然科学基金等课题多项。"
                        + "这里是一段示例简介，可在管理后台替换成真实内容。",
                "zhangsan@example.com",
                0));
        repository.saveMember(new LabMember(
                null,
                "李四",
                "副教授",
                "硕士生导师 · 软件工程系",
                null,
                "示例成员：研究方向为服务计算与智能化软件，负责多项校企合作项目。",
                "lisi@example.com",
                1));
        repository.saveMember(new LabMember(
                null,
                "王五",
                "讲师",
                "软件工程系",
                null,
                "示例成员：研究方向为数据挖掘与机器学习，参与多项科研课题。",
                "wangwu@example.com",
                2));
    }

    private void seedPublications() {
        // 演示用论文条目：标题与作者均为虚构，避免与真实成果混淆
        repository.savePublication(new LabPublication(null,
                "示例论文：移动群智感知中的任务分配方法综述",
                "张三, 李四, 王五",
                "示例期刊（Demo Journal）", 2025, null, 0));
        repository.savePublication(new LabPublication(null,
                "示例论文：边缘环境下基于强化学习的任务卸载",
                "李四, 王五",
                "示例会议（Demo Conference）", 2025, null, 1));
        repository.savePublication(new LabPublication(null,
                "示例论文：面向多源数据的服务组合优化",
                "张三, 王五",
                "示例期刊（Demo Journal）", 2024, null, 2));
        repository.savePublication(new LabPublication(null,
                "示例论文：群智感知中的隐私保护激励机制",
                "王五, 张三",
                "示例期刊（Demo Journal）", 2023, null, 3));
        repository.savePublication(new LabPublication(null,
                "示例论文：智能化软件的自适应决策框架",
                "张三, 李四",
                "示例会议（Demo Conference）", 2022, null, 4));
    }
}
