package com.zzkingcc.domainplane.server.controller;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.server.tool.ToolTextOverlay;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「工具调用 / 熔断」页的工具目录接口。
 *
 * <p>清单是合成视图：注册表给生效值，覆盖层给注解基线与覆盖状态。
 * 不复用 {@code GET /admin/tools} —— 那个接口返回裸 {@code ToolDescriptor} 列表，
 * 形状被脚本依赖，加字段会破坏它。</p>
 *
 * <p>只有 {@code LOCAL} 能写。前端不显示编辑入口只是交互，真正的边界在这里：
 * 远程与 MCP 的说明由上游声明，心跳一来就是全量覆盖，平台插手只会得到
 * 「改完生效、下次心跳又变回去」。</p>
 */
@RestController
@RequestMapping("/admin/tool-catalog")
public class AdminToolCatalogController {

    private final ToolTextOverlay overlay;

    public AdminToolCatalogController(ToolTextOverlay overlay) {
        this.overlay = overlay;
    }

    /** 保存文案：全量替换语义，空白说明 / 空白参数说明都表示清除该项 */
    public record TextBody(String description, Map<String, String> paramDescriptions) {
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", overlay.catalog());
        return out;
    }

    @PutMapping("/{name:.+}/text")
    public Map<String, Object> save(@PathVariable("name") String name,
                                    @RequestBody(required = false) TextBody body) {
        TextBody effective = body == null ? new TextBody(null, Map.of()) : body;
        guard(() -> overlay.save(name, effective.description(), effective.paramDescriptions()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "已保存 " + name + " 的工具说明与参数说明");
        return out;
    }

    @DeleteMapping("/{name:.+}/text")
    public Map<String, Object> reset(@PathVariable("name") String name) {
        guard(() -> overlay.reset(name));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "已恢复 " + name + " 的注解原文");
        return out;
    }

    /** 覆盖层的参数错误统一转成 INVALID_PARAMETER，把原因原样带给页面 */
    private void guard(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new BaseException(ErrorCode.INVALID_PARAMETER, e.getMessage());
        }
    }
}
