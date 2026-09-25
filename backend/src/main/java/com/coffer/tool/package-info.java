/**
 * Agent 自定义工具层：以 {@code @Tool} 注解声明的可调用工具。
 *
 * <p>每个工具必须写明名称与描述，内部调用 Service 层完成业务，
 * 供 Agent 自主决策时调用（如文件解析、标签生成、文件搜索等）。
 */
package com.coffer.tool;
