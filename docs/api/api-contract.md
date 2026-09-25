# 前后端 API 契约维护

后端通过 Springdoc 暴露 OpenAPI 文档：

```text
http://127.0.0.1:8080/v3/api-docs
```

前端使用 `openapi-typescript` 生成契约类型，生成文件位于：

```text
frontend/src/api/generated/schema.ts
```

## 更新流程

1. 启动后端开发服务。
2. 在 `frontend` 目录执行 `npm run api:generate`。
3. 检查生成文件差异，确认路径、字段、枚举和可空性变化符合预期。
4. 执行 `npm run api:check`，确认生成文件没有未提交差异。
5. 执行前端类型检查和生产构建。

接口 DTO 的 API 类型应通过 `frontend/src/api/types.ts` 的兼容层使用生成
schema；仅属于页面状态或交互状态的类型可以继续手写。

## 变更约束

- 不要直接编辑 `frontend/src/api/generated/schema.ts`。
- 后端新增或删除枚举值时，必须重新生成并检查前端编译结果。
- 后端 DTO 字段变更时，应同时检查接口调用、组件展示和请求参数。
- `multipart/form-data` 和流式接口可以保留手写请求封装，但响应 DTO 仍应复用生成类型。
