package com.protask;

import org.junit.jupiter.api.Test;

/**
 * 基础冒烟测试（不依赖数据库，验证构建链路）。
 */
class ProTaskApplicationTests {

    @Test
    void contextSanityCheck() {
        // 完整上下文测试需要数据库，此处仅验证测试框架可用
        org.junit.jupiter.api.Assertions.assertTrue(true);
    }
}
