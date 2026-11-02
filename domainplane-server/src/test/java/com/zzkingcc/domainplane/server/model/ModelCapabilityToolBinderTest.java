package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.runtime.tool.AnnotatedToolScanner;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.capability.ImageGenTool;
import com.zzkingcc.domainplane.server.capability.SpeechTool;
import com.zzkingcc.domainplane.server.capability.TranscribeTool;
import com.zzkingcc.domainplane.server.capability.VideoGenTool;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.vision.VisionDelegateTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力工具的<b>可用域</b>：由「域（链上）绑没绑可用的对应模型」这一事实推出来，而不是注解写死。
 *
 * <p>钉六件事：</p>
 * <ol>
 *   <li>初始声明是哨兵（对任何域都不可见）——不这样就是 fail-open：注册到首刷之间的窗口
 *       和 binder 失败时都会变成全域可见；</li>
 *   <li>没绑任何模型时，全部能力工具对任何域都不可见；</li>
 *   <li>绑了某族 → 该族工具只在该域与它的子域出现（声明沿链累加）；</li>
 *   <li>解绑 / 档案删除 → 工具跟着消失（落盘即回算）；</li>
 *   <li>video 的两个工具同进同退——单独放出 checkVideo 是"能查任务不能提交任务"的怪状态；</li>
 *   <li>seeImage 的判据是"有主模型 ∧ 有看图的"，只有纯文本主模型时不可见。</li>
 * </ol>
 *
 * @author zzkingcc
 */
@DisplayName("ModelCapabilityToolBinder：能力工具按域绑定动态注册")
class ModelCapabilityToolBinderTest {

    @TempDir
    Path tempDir;

    private ToolRegistry registry;
    private ModelProfileRegistry profiles;
    private ModelCapabilityToolBinder binder;

    private static final List<String> TOOL_NAMES = List.of(
            ImageGenTool.TOOL_NAME,
            VideoGenTool.SUBMIT_TOOL, VideoGenTool.CHECK_TOOL,
            SpeechTool.TOOL_NAME, TranscribeTool.TOOL_NAME,
            VisionDelegateTool.TOOL_NAME);

    @BeforeEach
    void setUp() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("config").toString());
        props.put("domainplane.logging.path", tempDir.resolve("log").toString());
        props.put("domainplane.export.path", tempDir.resolve("export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("test", props));

        profiles = new ModelProfileRegistry(new ModelProfileStore(new StorageLocations(env)));

        // 只为扫注解，依赖全传 null：扫描只读方法上的元数据，不碰字段
        registry = new ToolRegistry();
        registry.registerAll(AnnotatedToolScanner.scan(new ImageGenTool(null, null, null, null)));
        registry.registerAll(AnnotatedToolScanner.scan(new VideoGenTool(null, null, null, null, 90_000L)));
        registry.registerAll(AnnotatedToolScanner.scan(new SpeechTool(null, null, null, null)));
        registry.registerAll(AnnotatedToolScanner.scan(new TranscribeTool(null, null, null, null)));
        registry.registerAll(AnnotatedToolScanner.scan(new VisionDelegateTool(null, null, null)));

        binder = new ModelCapabilityToolBinder(registry, profiles);
        binder.start();   // 挂监听 + 算第一次
    }

    /** 一份可用档案：endpoints / input 决定它能干什么 */
    private static ModelProfile profile(String alias, List<String> endpoints, List<String> input) {
        return new ModelProfile(alias, endpoints, input, List.of("text"),
                "https://api.example.com", "sk-test", "m-" + alias,
                null, null, null, null, null, null, List.of(), null, null);
    }

    /** 模拟管控台那一步：建档 + 绑域（落盘会触发 onChange → 重算可用域） */
    private void bind(String domain, String alias, String family) {
        assertNull(profiles.bindDomain(domain, null, Map.of(family, List.of(alias))),
                "绑定被拒");
    }

    private boolean visible(String tool, String domain) {
        return registry.find(tool)
                .map(r -> r.descriptor().visibleIn(domain))
                .orElse(false);
    }

    @Test
    @DisplayName("初始声明是哨兵：对任何域都不可见（fail-closed，不是挂根域）")
    void initialDeclarationsAreUnbound() {
        for (String tool : TOOL_NAMES) {
            ToolDescriptor descriptor = registry.find(tool)
                    .orElseThrow(() -> new AssertionError("工具未注册：" + tool))
                    .descriptor();
            assertEquals(List.of(ToolDescriptor.UNBOUND_DOMAIN), descriptor.declaredDomains(),
                    tool + " 的初始声明必须是哨兵；挂根域会在 binder 首刷前与失败时漏成全域可见");
            assertFalse(descriptor.visibleIn("default"), tool + " 初始不该对根域可见");
        }
    }

    @Test
    @DisplayName("没绑任何模型：全部能力工具对任何域都不可见")
    void noBinding_toolsInvisibleEverywhere() {
        for (String tool : TOOL_NAMES) {
            assertFalse(visible(tool, "default"), tool);
            assertFalse(visible(tool, "default.sales"), tool);
            assertFalse(visible(tool, "default.sales.eu"), tool);
        }
    }

    @Test
    @DisplayName("绑了生图模型：generateImage 只在该域与子域出现，其余工具不受影响")
    void boundFamily_visibleOnDomainAndDescendants() {
        profiles.save(profile("img-a", List.of(ModelProfile.EP_IMAGES), List.of("text")));
        bind("default.sales", "img-a", ModelProfile.EP_IMAGES);

        assertTrue(visible(ImageGenTool.TOOL_NAME, "default.sales"), "绑定的域必须有工具");
        assertTrue(visible(ImageGenTool.TOOL_NAME, "default.sales.eu"),
                "子域沿链解析得到父域的生图模型，工具也该有（声明沿链累加）");
        assertFalse(visible(ImageGenTool.TOOL_NAME, "default"), "父域没绑：不该因为子域绑了就冒出来");
        assertFalse(visible(ImageGenTool.TOOL_NAME, "default.other"), "兄弟域不继承");
        for (String tool : TOOL_NAMES) {
            if (!ImageGenTool.TOOL_NAME.equals(tool)) {
                assertFalse(visible(tool, "default.sales"), tool + " 不该被生图绑定带出来");
            }
        }
    }

    @Test
    @DisplayName("video 的两个工具同进同退：解绑后一起消失")
    void videoToolsMoveTogether() {
        profiles.save(profile("vid-a", List.of(ModelProfile.EP_VIDEO), List.of("text")));
        bind("default.sales", "vid-a", ModelProfile.EP_VIDEO);
        assertTrue(visible(VideoGenTool.SUBMIT_TOOL, "default.sales"));
        assertTrue(visible(VideoGenTool.CHECK_TOOL, "default.sales"));

        String failure = profiles.bindDomain("default.sales", null,
                Map.of(ModelProfile.EP_VIDEO, List.of()));
        assertNull(failure, "解绑被拒：" + failure);

        assertFalse(visible(VideoGenTool.SUBMIT_TOOL, "default.sales"),
                "提交工具消失后查询工具必须一起消失，否则出现'能查任务不能提交任务'的怪状态");
        assertFalse(visible(VideoGenTool.CHECK_TOOL, "default.sales"));
    }

    @Test
    @DisplayName("档案被删：级联摘掉绑定，工具跟着消失（onChange 回调覆盖 delete 路径）")
    void profileDeleted_toolDisappears() {
        profiles.save(profile("tts-a", List.of(ModelProfile.EP_TTS), List.of("text")));
        bind("default.sales", "tts-a", ModelProfile.EP_TTS);
        assertTrue(visible(SpeechTool.TOOL_NAME, "default.sales"));

        profiles.delete("tts-a");

        assertFalse(visible(SpeechTool.TOOL_NAME, "default.sales"),
                "档案删除后还留着工具 = 模型会去调一个没有语音模型的域");
        assertTrue(profiles.profile("tts-a").isEmpty(), "档案应已删除");
    }

    @Test
    @DisplayName("seeImage：只有纯文本主模型时不可见，链上有声明图片输入的档案才可见")
    void vision_seeImageJudgment() {
        profiles.save(profile("chat-a", List.of(ModelProfile.EP_CHAT), List.of("text")));
        assertNull(profiles.bindDomain("default.sales", List.of("chat-a"), null),
                "绑定被拒");

        assertFalse(visible(VisionDelegateTool.TOOL_NAME, "default.sales"),
                "有主模型但没有看图的：视觉委派必然解析不出，工具不该出现");

        profiles.save(profile("chat-b", List.of(ModelProfile.EP_CHAT), List.of("text", "image")));
        profiles.bindDomain("default.sales", List.of("chat-a", "chat-b"), null);

        assertTrue(visible(VisionDelegateTool.TOOL_NAME, "default.sales"),
                "绑定链上有声明图片输入的档案，seeImage 该出现");
        assertTrue(visible(VisionDelegateTool.TOOL_NAME, "default.sales.eu"), "子域沿链继承");
        assertFalse(visible(VisionDelegateTool.TOOL_NAME, "default.other"), "兄弟域不继承");
    }

    @Test
    @DisplayName("沿链回落：子域自己只绑了 chat，生图绑定在父域——子域也能看到 generateImage")
    void familyFallsBackAlongChain() {
        profiles.save(profile("img-a", List.of(ModelProfile.EP_IMAGES), List.of("text")));
        profiles.save(profile("chat-a", List.of(ModelProfile.EP_CHAT), List.of("text")));
        bind("default.sales", "img-a", ModelProfile.EP_IMAGES);
        // 主模型走 chatAliases 参数（capabilities 里放 chat 会被绑域入口直接拒绝）
        assertNull(profiles.bindDomain("default.sales.eu", List.of("chat-a"), null),
                "绑定被拒");

        assertTrue(visible(ImageGenTool.TOOL_NAME, "default.sales.eu"),
                "子域解析生图时沿链回落到父域的绑定，能解析得出就该看得见");
    }

    @Test
    @DisplayName("重启路径：绑定先于 binder 存在时，@PostConstruct 那一次 refresh 就要挂上")
    void startupWithExistingBindings_toolsVisible() {
        /* 另起一套配置与注册表，模拟"进程重启时 models.json 里已有绑定"：
           这条路径没有任何 onChange 会来救场，首刷漏了就得等用户下次改配置。
           生产上冷启动走的正是它，而 setUp 里那套是"先启动、后绑定"。 */
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("domainplane.settings.path", tempDir.resolve("restart").toString());
        props.put("domainplane.logging.path", tempDir.resolve("restart-log").toString());
        props.put("domainplane.export.path", tempDir.resolve("restart-export").toString());
        env.getPropertySources().addFirst(new MapPropertySource("restart", props));

        ModelProfileRegistry restartProfiles =
                new ModelProfileRegistry(new ModelProfileStore(new StorageLocations(env)));
        restartProfiles.save(profile("img-r", List.of(ModelProfile.EP_IMAGES), List.of("text")));
        assertNull(restartProfiles.bindDomain("default.sales", null,
                Map.of(ModelProfile.EP_IMAGES, List.of("img-r"))), "绑定被拒");

        ToolRegistry restartRegistry = new ToolRegistry();
        restartRegistry.registerAll(AnnotatedToolScanner.scan(new ImageGenTool(null, null, null, null)));
        ModelCapabilityToolBinder restartBinder =
                new ModelCapabilityToolBinder(restartRegistry, restartProfiles);
        restartBinder.start();

        assertTrue(restartRegistry.find(ImageGenTool.TOOL_NAME).orElseThrow()
                        .descriptor().visibleIn("default.sales"),
                "重启后第一次 refresh 必须把已绑定的工具挂上");
    }
}
