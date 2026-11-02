package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.config.DomainProperties;
import com.zzkingcc.domainplane.server.settings.DomainPurgeService;
import com.zzkingcc.domainplane.server.settings.DomainSettings;
import com.zzkingcc.domainplane.server.settings.DomainSettingsStore;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.DomainVersionWatcher;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面：域的创建、可调用性切换与删除。
 *
 * 域有两种角色，由 {@code callable} 显式区分：
 *
 *   • 可调用单元（{@code callable=true}）：能作为入口被调用，是"这一个 AI 切片"的入口；
 *   • 装配节点（{@code callable=false}）：只负责把工具 / 提示词 / 模型绑定 / 知识传给后代，不能直接调。
 *
 *
 * 为什么必须显式：只有显式声明，才能把"父域不能当入口"写成一条不受结构变化影响的规则；
 * 而"有没有子域"是随时会变的派生事实：拿它当判据会在新增子域时静默改变调用方的可用性。
 *
 * 删除的边界：只有根域不可删。删除是递归的：该域的全部子孙一并带走，
 * 不向上提升层级；删除前会先删知识库索引，删不干净就拒绝删域。
 *
 * 删除默认是**软删**（F03 软删三阶段）：打标记、进宽限期、到期由调度器硬删；
 * {@code ?force=true} 立即硬删，{@code POST /restore} 撤销。宽限期置 0 时退回立即硬删旧语义。
 *
 * 路径在 {@code /admin/**} 之下，鉴权由凭证拦截器统一处理。
 *
 * @author zzkingcc
 */
@RestController
public class AdminDomainController {

    private static final Logger log = LoggerFactory.getLogger(AdminDomainController.class);

    private final DomainRegistry domainRegistry;
    private final DomainStore domainStore;
    private final ToolRegistry toolRegistry;
    private final DomainSettingsStore domainSettingsStore;
    private final DomainPurgeService purgeService;
    private final DomainProperties domainProperties;
    private final DomainVersionWatcher versionWatcher;

    /** 存储配置：绑知识体时要确认这个体真的存在（否则会给域留一个永远解析不出来的绑定） */
    private final InfraSettingsHolder infraHolder;

    public AdminDomainController(DomainRegistry domainRegistry,
                                 DomainStore domainStore,
                                 ToolRegistry toolRegistry,
                                 DomainSettingsStore domainSettingsStore,
                                 DomainPurgeService purgeService,
                                 DomainProperties domainProperties,
                                 DomainVersionWatcher versionWatcher,
                                 InfraSettingsHolder infraHolder) {
        this.domainRegistry = domainRegistry;
        this.domainStore = domainStore;
        this.toolRegistry = toolRegistry;
        this.domainSettingsStore = domainSettingsStore;
        this.purgeService = purgeService;
        this.domainProperties = domainProperties;
        this.versionWatcher = versionWatcher;
        this.infraHolder = infraHolder;
    }

    /**
     * 创建域（完整路径；链上缺失的祖先一并建出，祖先一律是装配节点）。
     *
     * @param body {@code id} 域标识，须为从 {@code default} 出发的完整路径，
     *             单段只允许字母 / 数字 / 下划线 / 连字符，自身链上不得重复段；
     *             {@code callable} 省略时按"可调用单元"创建
     */
    @PostMapping("/admin/domains")
    public Map<String, Object> create(@RequestBody CreateBody body) {
        if (body == null || body.getId() == null || body.getId().isBlank()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域标识不能为空");
        }
        boolean callable = body.getCallable() == null || body.getCallable();
        assertBaseVersion(body.getBaseVersion());
        DomainRegistry.CreateResult created = domainRegistry.create(body.getId(), callable);
        if (!created.created()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, created.reason());
        }
        // 重新创建同名域 = 撤销作废：不解除的话，工具侧的心跳仍会被撤销集挡住，
        // 域建出来了却永远没有工具声明它，表现为"刚建好的域一个工具都没有"。
        toolRegistry.allowDomains(List.of(Domains.normalize(body.getId())));
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        log.info("[域管理] 已创建域 {}（来源 MANUAL，可调用={}）", body.getId().trim(), callable);
        return view("created", body.getId().trim(), List.of());
    }

    /**
     * 切换某个域的可调用性。
     *
     * 只影响这一个域，不向下传播；切回可调用后，以它为入口的调用立即恢复。
     *
     * @param body {@code callable} 目标值
     */
    @PutMapping("/admin/domains/{id}/callable")
    public Map<String, Object> setCallable(@PathVariable("id") String id,
                                           @RequestBody(required = false) CallableBody body) {
        if (body == null || body.getCallable() == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "缺少 callable（true=可调用单元，false=装配节点）");
        }
        assertBaseVersion(body.getBaseVersion());
        String normalized = Domains.normalize(id);
        // 根域同样服从叶子规则（有子域时它也只是装配节点），因此这里不需要根域特例：
        // 唯一的硬约束是"只有叶子域可以作为可调用单元"。
        DomainRegistry.CallableResult result = domainRegistry.setCallable(normalized, body.getCallable());
        if (!result.changed()) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, result.reason());
        }
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        return view("callable", normalized, List.of());
    }

    /**
     * 切换某个域的「工具执行信息回写」开关（管控台「域空间」页卡片头部那颗开关）。
     *
     * 语义边界（与前端那颗开关一一对应）：
     *   • 关（默认）= 调用方只收到最终答复，收不到工具调用请求与执行结果；
     *   • 审批挂起（待授权工具清单）不归它管：那是功能骨架，恒下发；
     *   • 只对可调用单元（叶子域）有意义：装配节点不能作为入口，给它设了也永远读不到。
     *     因此这里按可调用性校验，非叶子直接拒绝（前端同样只给可调用单元渲染开关）。
     *     装配节点上已存在的设置信息不清除：域将来变回叶子时自动恢复生效。
     *
     * @param body {@code echo} 目标值；true=回写，false=不回写
     */
    @PutMapping("/admin/domains/{id}/tool-call-echo")
    public Map<String, Object> setToolCallEcho(@PathVariable("id") String id,
                                               @RequestBody(required = false) ToolCallEchoBody body) {
        if (body == null || body.getEcho() == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "缺少 echo（true=回写工具执行信息，false=不回写）");
        }
        String normalized = Domains.normalize(id);
        if (!domainRegistry.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + normalized);
        }
        if (!domainRegistry.isCallable(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "域 " + normalized + " 不是可调用单元：本平台只有叶子域可以作为可调用单元，"
                            + "工具执行信息回写只对入口域有意义");
        }
        DomainSettings settings = domainSettingsStore.load();
        settings.getToolCallEcho().put(normalized, body.getEcho());
        domainSettingsStore.save(settings);
        log.info("[域管理] 域 {} 的工具执行信息回写改为 {}", normalized, body.getEcho());
        return view("tool-call-echo", normalized, List.of());
    }

    /**
     * 切换某个域的「执行计划展示」开关：规划前置产出的本轮计划（PLAN 事件）发不给调用方。
     *
     * 语义边界（与前端那颗开关一一对应，与 {@code tool-call-echo} 同构）：
     *   • 关（默认）= 规划照做（模型照样按计划执行），调用方只是收不到 PLAN 事件；
     *   • 规划本身做不做归全局开关 {@code domainplane.planning.enabled} 管，与这里互不约束；
     *   • 只对可调用单元（叶子域）有意义，校验口径与工具回写开关一致；
     *     装配节点上已存在的设置信息不清除：域将来变回叶子时自动恢复生效。
     *
     * @param body {@code display} 目标值；true=下发计划，false=不下发
     */
    @PutMapping("/admin/domains/{id}/plan-display")
    public Map<String, Object> setPlanDisplay(@PathVariable("id") String id,
                                              @RequestBody(required = false) PlanDisplayBody body) {
        if (body == null || body.getDisplay() == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "缺少 display（true=下发本轮执行计划，false=不下发）");
        }
        String normalized = Domains.normalize(id);
        if (!domainRegistry.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + normalized);
        }
        if (!domainRegistry.isCallable(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "域 " + normalized + " 不是可调用单元：本平台只有叶子域可以作为可调用单元，"
                            + "执行计划展示只对入口域有意义");
        }
        DomainSettings settings = domainSettingsStore.load();
        settings.getPlanDisplay().put(normalized, body.getDisplay());
        domainSettingsStore.save(settings);
        log.info("[域管理] 域 {} 的执行计划展示改为 {}", normalized, body.getDisplay());
        return view("plan-display", normalized, List.of());
    }

    /**
     * 给域绑一个知识体（管控台「域空间」卡片上的那个下拉）。
     *
     * <p>绑定是域自己的声明，<b>不继承</b>：父域绑了体，子域不会跟着用——没绑的子域就是
     * 没有知识库。理由很实在：知识体决定"这个域的文档写进哪台机器"，跟着父域走会让
     * 新建的子域在用户不知情的情况下往父域的库里写，且查出来的东西还"看着对"。</p>
     *
     * <p>只允许绑<b>能检索</b>的体（关键字 / 向量）。原文体不参与召回，绑了等于这个域
     * 仍然没有可查的知识，属于"静默无效"的配置错误，保存时就拒绝比事后排查便宜。</p>
     *
     * <p>不校验"同一台机器被多个域共用"：那是合法且常见的用法（同一个向量体服务多个域，
     * 索引名按域区分）。</p>
     *
     * @param body {@code body} 知识体 id；留空 / null = 解绑（这个域没有知识库）
     */
    @PutMapping("/admin/domains/{id}/body")
    public Map<String, Object> setBody(@PathVariable("id") String id,
                                       @RequestBody(required = false) BodyBindBody body) {
        if (body == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "缺少 body（知识体 id；留空表示这个域没有知识库）");
        }
        assertBaseVersion(body.getBaseVersion());
        String normalized = Domains.normalize(id);
        if (!domainRegistry.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + normalized);
        }
        String bodyId = body.getBody() == null || body.getBody().isBlank()
                ? null : body.getBody().trim();
        if (bodyId != null) {
            InfraSettings.KnowledgeBody kb = infraHolder.current().body(bodyId);
            if (kb == null) {
                throw new BaseException(ErrorCode.INVALID_PARAMETER,
                        "知识体不存在：" + bodyId + "（可能刚被删，请刷新存储配置）");
            }
            if (!kb.configured()) {
                throw new BaseException(ErrorCode.INVALID_PARAMETER,
                        "知识体 " + kb.effectiveName() + " 一路都没配（关键字 / 向量都是空的），"
                                + "绑了它这个域仍然没有可查的知识");
            }
            if (infraHolder.current().usableBody(bodyId) == null) {
                throw new BaseException(ErrorCode.INVALID_PARAMETER,
                        "知识体 " + kb.effectiveName() + " 的连接都不可用，先在「存储配置」里补上");
            }
        }
        String previous = domainStore.bodyOf(normalized);
        domainStore.setBody(normalized, bodyId);
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        log.info("[域管理] 域 {} 的知识体从 {} 改为 {}", normalized,
                previous == null ? "未绑定" : previous, bodyId == null ? "未绑定" : bodyId);
        Map<String, Object> result = view("body", normalized, List.of());
        result.put("body", bodyId);
        result.put("previousBody", previous);
        return result;
    }

    /**
     * 删除域（默认软删，F03 软删三阶段）。
     *
     * <b>软删（默认）</b>：整支树打 {@code deletedAt} 标记——域完整留在注册表里，
     * 结构判据（{@code isCallable}）一个字不改，只有新开轮入口拒绝
     * （{@code DOMAIN_PENDING_DELETE}）；进行中的会话与 INTERRUPT 挂起的 resume
     * 不受影响，悬案可落地。宽限期到由 {@code DomainPurgeScheduler} 硬删。
     *
     * <b>{@code ?force=true}</b>：立即硬删，强杀语义（不等在飞会话停止）。
     * <b>宽限期置 0</b>（{@code domainplane.domain.delete-grace=0}）：本端点即旧语义——
     * 立即硬删且停不掉在飞会话就拒删（{@code ORCHESTRATION_FAILED}）。
     *
     * @param force       true=跳过宽限期立即硬删（强杀）
     * @param baseVersion 乐观并发基准（可选）：不等于当前版本号即 409，
     *                    多实例下杜绝后写覆盖先写；不传则不校验
     */
    @DeleteMapping("/admin/domains/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id,
                                      @RequestParam(value = "force", defaultValue = "false") boolean force,
                                      @RequestParam(value = "baseVersion", required = false) Long baseVersion) {
        // 先确认这个域存在且可删，避免白删一轮索引
        String normalized = Domains.normalize(id);
        if (!domainRegistry.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + normalized);
        }
        if (domainRegistry.isBuiltin(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "根域不可删除：" + normalized + "（它是整棵域树的起点）");
        }
        assertBaseVersion(baseVersion);

        if (!force && domainRegistry.isPendingDelete(normalized)) {
            // 已在宽限期内：幂等返回现状。不刷新倒计时——刷新等于给拖延续命
            return softDeletedView(normalized, domainRegistry.pendingDeleteAt(normalized));
        }

        Duration grace = domainProperties.getDeleteGrace();
        if (!force && !grace.isZero() && !grace.isNegative()) {
            return softDelete(normalized, grace);
        }
        // force 或宽限期=0：立即硬删。force=强杀（宽限期思维：不再无限等待）；
        // 宽限期=0 的旧语义=等待在飞会话停止，停不掉即拒删
        DomainPurgeService.PurgeResult result = purgeService.purge(normalized, !force);
        return view("deleted", normalized, result.removed(), result.removedIndices());
    }

    /**
     * 撤销软删（宽限期内有效）。
     *
     * 清除标记即恢复：域本来就在注册表里，结构状态从未被动过。
     * 两类竞态拒绝（{@code DOMAIN_DELETE_BLOCKED}）：硬删清理正在跑；
     * 宽限期已到（调度器随时可能开跑，撤销它等于和定时任务赛跑）。
     */
    @PostMapping("/admin/domains/{id}/restore")
    public Map<String, Object> restore(@PathVariable("id") String id,
                                       @RequestBody(required = false) BaseVersionBody body) {
        String normalized = Domains.normalize(id);
        if (!domainRegistry.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "域不存在：" + normalized);
        }
        Long deletedAt = domainRegistry.pendingDeleteAt(normalized);
        if (deletedAt == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER,
                    "域 " + normalized + " 不在删除宽限期内（没有软删标记可撤销）");
        }
        assertBaseVersion(body == null ? null : body.getBaseVersion());
        if (purgeService.isPurging(normalized)) {
            throw new BaseException(ErrorCode.DOMAIN_DELETE_BLOCKED,
                    "域 " + normalized + " 的删除清理已启动，不可撤销");
        }
        Duration grace = domainProperties.getDeleteGrace();
        if (!grace.isZero() && !grace.isNegative()
                && System.currentTimeMillis() - deletedAt >= grace.toMillis()) {
            throw new BaseException(ErrorCode.DOMAIN_DELETE_BLOCKED,
                    "域 " + normalized + " 宽限期已到，删除随时可能完成，不能撤销；"
                            + "如需恢复请从历史版本库回滚");
        }
        // 与软删对称：整支树一起恢复（软删时就是整支一起打的标记）
        List<String> affected = affectedOf(normalized);
        affected.forEach(domainId -> {
            domainRegistry.clearPendingDelete(domainId);
            domainStore.clearDeleted(domainId);
        });
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        log.info("[域管理] 已撤销域 {} 的软删（含 {} 个子孙）", normalized, affected.size() - 1);
        return view("restored", normalized, List.of());
    }

    /**
     * 历史版本库清单（版本从新到旧）。
     *
     * 管控台版本库页据此展示可回滚的目标；回滚前先用
     * {@code GET /admin/domains/history/{version}} 看 diff。
     */
    @GetMapping("/admin/domains/history")
    public Map<String, Object> history() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (DomainStore.HistoryEntry entry : domainStore.listHistory()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("version", entry.version());
            item.put("modifiedAt", entry.modifiedAt());
            item.put("sizeBytes", entry.sizeBytes());
            item.put("domainCount", entry.domainCount());
            item.put("callableCount", entry.callableCount());
            item.put("pendingDeleteCount", entry.pendingDeleteCount());
            item.put("current", entry.current());
            entries.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("currentVersion", domainStore.currentVersion());
        result.put("historyPath", domainStore.historyPath());
        result.put("entries", entries);
        return result;
    }

    /**
     * 历史版本与当前的 diff（回滚前的影响面预览）。
     *
     * 回滚是有损操作：被回滚掉的时间里创建的域连同其提示词 / 工具绑定一起消失
     * （级联语义），所以必须先把"会新增 / 删除 / 改写哪些域"摆清楚，
     * 不能一个"确定"按钮了事。
     */
    @GetMapping("/admin/domains/history/{version}")
    public Map<String, Object> historyDetail(@PathVariable("version") long version) {
        DomainStore.DomainDeclarations snapshot = domainStore.readSnapshot(version);
        Map<String, Boolean> snapshotCallables = DomainStore.callablesOf(snapshot);
        Map<String, Boolean> current = domainRegistry.manualConfig();

        List<String> added = snapshotCallables.keySet().stream()
                .filter(domainId -> !current.containsKey(domainId))
                .sorted().toList();
        List<String> removed = current.keySet().stream()
                .filter(domainId -> !snapshotCallables.containsKey(domainId))
                .sorted().toList();
        List<Map<String, Object>> changed = new ArrayList<>();
        snapshotCallables.forEach((domainId, callable) -> {
            Boolean now = current.get(domainId);
            if (now != null && !now.equals(callable)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", domainId);
                item.put("from", now);
                item.put("to", callable);
                changed.add(item);
            }
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("version", version);
        result.put("current", version == domainStore.currentVersion());
        result.put("snapshotDomainCount", snapshotCallables.size());
        result.put("snapshotPendingDeletes", DomainStore.markersOf(snapshot).keySet());
        result.put("currentPendingDeletes", domainStore.pendingDeletes().keySet());
        result.put("added", added);
        result.put("removed", removed);
        result.put("callableChanged", changed);
        return result;
    }

    /**
     * 回滚到历史版本。
     *
     * 回滚即普通变更：把快照内容作为新版本落盘（version+1），不是把文件倒回旧版本号——
     * 否则会出现比"当前"还旧的幽灵版本被再次选中，版本比较随之失效。
     * 注册表整体重建后立即按当前工具声明重放派生域，不让入口出现空窗。
     */
    @PostMapping("/admin/domains/rollback")
    public Map<String, Object> rollback(@RequestBody RollbackBody body) {
        if (body == null || body.getVersion() == null) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, "缺少 version（要回滚到哪个历史版本）");
        }
        assertBaseVersion(body.getBaseVersion());
        DomainStore.DomainDeclarations snapshot = domainStore.readSnapshot(body.getVersion());
        Map<String, Boolean> callables = DomainStore.callablesOf(snapshot);
        Map<String, Long> markers = DomainStore.markersOf(snapshot);

        Map<String, Boolean> before = domainRegistry.manualConfig();
        domainRegistry.replaceDeclarations(callables, markers);
        // 派生域立即重建：不等工具心跳，否则回滚后 derived 域会短时间从入口消失
        domainRegistry.ensureChains(toolRegistry.knownDomains());
        // 知识体绑定跟着一起回滚：只回可调用性不回绑定，会出现"结构回到过去、
        // 知识库还指着回滚前那个体"的错位，而且不会报错，只是查出来的东西不对
        domainStore.commitRollback(callables, markers, snapshot.isCallableMigrated(),
                DomainStore.bodiesOf(snapshot));

        List<String> added = callables.keySet().stream()
                .filter(domainId -> !before.containsKey(domainId)).sorted().toList();
        List<String> removed = before.keySet().stream()
                .filter(domainId -> !callables.containsKey(domainId)).sorted().toList();
        log.warn("[域管理] 已回滚域配置到快照 v{}（新版本 v{}）：新增 {} 个域声明、移除 {} 个",
                body.getVersion(), domainStore.currentVersion(), added.size(), removed.size());

        Map<String, Object> result = view("rolled-back", null, List.of());
        result.put("rolledBackFrom", body.getVersion());
        result.put("newVersion", domainStore.currentVersion());
        result.put("added", added);
        result.put("removed", removed);
        return result;
    }

    /**
     * 域存储现状（版本号 / 落后标记 / 路径 / 宽限期配置），供管控台版本信息展示。
     */
    @GetMapping("/admin/domains/storage")
    public Map<String, Object> storage() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("version", domainStore.currentVersion());
        result.put("behind", versionWatcher.isBehind());
        result.put("settingsFile", domainStore.filePath());
        result.put("historyPath", domainStore.historyPath());
        result.put("deleteGraceMs", domainProperties.getDeleteGrace().toMillis());
        result.put("pendingDeletes", domainStore.pendingDeletes());
        return result;
    }

    /** 软删：整支树打标记（自身 + 全部子孙），落盘 = version+1 的普通变更 */
    private Map<String, Object> softDelete(String normalized, Duration grace) {
        // 为什么是整支树：删父域的语义是"这一支都没了"。只标父不标子会留下
        // 仍可调用的子孙，与硬删的递归语义相悖，管控台上也会看到"删了却还在"
        List<String> affected = affectedOf(normalized);
        long now = System.currentTimeMillis();
        affected.forEach(domainId -> {
            domainRegistry.markPendingDelete(domainId, now);
            domainStore.markDeleted(domainId);
        });
        // 一次落盘 = version+1 + 历史快照 + 版本信号广播，撤销删除同理
        domainStore.saveDeclarations(domainRegistry.manualConfig());
        long hardDeleteAt = now + grace.toMillis();
        log.info("[域管理] 域 {} 已软删（含 {} 个子孙，共 {} 个），宽限期至 {}（{}），可撤销",
                normalized, affected.size() - 1, affected.size(),
                Instant.ofEpochMilli(hardDeleteAt), grace);
        Map<String, Object> result = softDeletedView(normalized, now);
        result.put("hardDeleteAt", hardDeleteAt);
        result.put("affected", affected);
        result.put("graceMs", grace.toMillis());
        return result;
    }

    private Map<String, Object> softDeletedView(String normalized, Long deletedAt) {
        Map<String, Object> result = view("soft-deleted", normalized, List.of());
        result.put("deletedAt", deletedAt);
        result.put("pendingDelete", true);
        return result;
    }

    /** 自身 + 全部子孙（顺序：子孙在前，与硬删的清理顺序一致） */
    private List<String> affectedOf(String normalized) {
        List<String> affected = new ArrayList<>(domainRegistry.descendantsOf(normalized));
        affected.add(normalized);
        return affected;
    }

    /**
     * 乐观并发校验：管理端携带的基准版本不等于当前版本即拒。
     *
     * 多实例下这是"后写覆盖先写"的唯一防线（不需要 leader 选举）；
     * 单机下它等价于"页面已过期"。不传则不校验——老 Console 不传也能用。
     */
    private void assertBaseVersion(Long baseVersion) {
        if (baseVersion != null && baseVersion != domainStore.currentVersion()) {
            throw new BaseException(ErrorCode.VERSION_CONFLICT,
                    "域配置版本已变更（当前 v" + domainStore.currentVersion()
                            + "，基于 v" + baseVersion + "）：请刷新后重试");
        }
    }

    /**
     * 统一返回体：动作 + 当前域全貌，便于管控台一次刷新。
     */
    private Map<String, Object> view(String action, String domainId, List<String> removed,
                                     List<String> removedIndices) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("action", action);
        result.put("domain", domainId);
        result.put("removed", removed);
        result.put("removedIndices", removedIndices);
        result.put("manualDomains", domainRegistry.manualIds());
        result.put("callableDomains", domainRegistry.callableIds());
        result.put("settingsFile", domainStore.filePath());
        return result;
    }

    private Map<String, Object> view(String action, String domainId, List<String> removed) {
        return view(action, domainId, removed, List.of());
    }

    /** 创建请求体 */
    @Data
    public static class CreateBody {
        /** 域标识 */
        private String id;

        /** 是否建为可调用单元；省略 = true */
        private Boolean callable;

        /** 乐观并发基准（可选）：基于哪个版本发起本次变更 */
        private Long baseVersion;
    }

    /** 可调用性切换请求体 */
    @Data
    public static class CallableBody {
        /** true=可调用单元，false=装配节点 */
        private Boolean callable;

        /** 乐观并发基准（可选） */
        private Long baseVersion;
    }

    /** 只带版本基准的请求体（撤销软删用） */
    @Data
    public static class BaseVersionBody {
        /** 乐观并发基准（可选） */
        private Long baseVersion;
    }

    /** 回滚请求体 */
    @Data
    public static class RollbackBody {
        /** 要回滚到的历史版本号 */
        private Long version;

        /** 乐观并发基准（可选） */
        private Long baseVersion;
    }

    /** 工具执行信息回写开关请求体 */
    @Data
    public static class ToolCallEchoBody {
        /** true=回写工具调用请求与执行结果，false=只回最终答复 */
        private Boolean echo;
    }

    /** 执行计划展示开关请求体 */
    @Data
    public static class PlanDisplayBody {
        /** true=下发本轮执行计划（PLAN 事件），false=不下发 */
        private Boolean display;
    }

    /** 知识体绑定请求体 */
    @Data
    public static class BodyBindBody {
        /** 知识体 id；留空 = 解绑（这个域没有知识库） */
        private String body;

        /** 乐观并发基准（可选） */
        private Long baseVersion;
    }
}
