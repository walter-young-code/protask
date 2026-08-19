package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.BlockPatchRequest;
import com.protask.dto.BlockRequest;
import com.protask.dto.BlockVO;
import com.protask.service.BlockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 块接口（统一块模型 card/view/text/comment）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BlockController {

    private final BlockService blockService;

    /** 查询项目下的块列表（可按类型过滤） */
    @GetMapping("/projects/{projectId}/blocks")
    public Result<List<BlockVO>> list(@PathVariable Long projectId,
                                      @RequestParam(required = false) String type) {
        return Result.ok(blockService.listBlocks(StpUtil.getLoginIdAsLong(), projectId, type));
    }

    /** 在项目下创建块 */
    @PostMapping("/projects/{projectId}/blocks")
    public Result<BlockVO> create(@PathVariable Long projectId,
                                  @Valid @RequestBody BlockRequest request) {
        return Result.ok(blockService.createBlock(StpUtil.getLoginIdAsLong(), projectId, request));
    }

    /** 局部更新块 */
    @PatchMapping("/blocks/{id}")
    public Result<BlockVO> patch(@PathVariable Long id,
                                 @Valid @RequestBody BlockPatchRequest request) {
        return Result.ok(blockService.patchBlock(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 软删除块 */
    @DeleteMapping("/blocks/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        blockService.deleteBlock(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }
}
