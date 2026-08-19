# 贡献指南

感谢参与 anydoc-sdk（anydoc-service Java SDK）的建设。

## 开发环境

- JDK 17+、Maven 3.9+
- 建议同时本地运行一个 anydoc-service 实例（用于 `LiveServiceTest` 联调，可选）

## 构建与测试

```bash
mvn clean install        # 编译 + 全量契约测试（JDK HttpServer 打桩，无需外部服务）
```

契约测试覆盖了服务端全部状态码 → SDK 异常映射、Retry-After 遵循、熔断、
单飞、异步任务轮询等行为。**任何改动必须保持契约测试全绿**。

## 代码规范

1. **向后兼容**：公共 API（`AnydocClient` 接口、record 组件、异常类型）只增不改；
   破坏性变更需在 Issue 中讨论并走大版本号。
2. **核心零 Spring 依赖**：`com.zll.anydoc.spring` 之外不得引用 Spring 类。
3. **错误契约同步**：服务端新增状态码/错误码时，同步更新
   `DefaultAnydocClient` 映射 + 契约测试 + README 契约表。
4. 日志走 SLF4J，不得在 SDK 内打印文档正文（隐私合规）。

## 提交规范

- Commit message 建议遵循 Conventional Commits：`feat:` / `fix:` / `docs:` / `test:` / `refactor:`
- PR 描述包含：动机、改动点、测试结果（`mvn clean install` 输出摘要）

## 版本发布

遵循语义化版本（SemVer）：

- **patch**：缺陷修复，无 API 变化
- **minor**：新增能力（如新端点支持），向后兼容
- **major**：破坏性变更

发布 checklist：更新 `pom.xml` 版本 → 更新 README 版本与路线图 → 打 tag `vX.Y.Z`。
